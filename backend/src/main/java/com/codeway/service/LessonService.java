package com.codeway.service;

import com.codeway.ai.AiClient;
import com.codeway.domain.Lesson;
import com.codeway.repo.LessonRepo;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LessonService {
    private final LessonRepo repo;
    private final AiClient ai;
    private final ConceptCatalog catalog;

    public LessonService(LessonRepo repo, AiClient ai, ConceptCatalog catalog) { this.repo = repo; this.ai = ai; this.catalog = catalog; }

    /** Generation is attempted again for a stored plain-text fallback lesson at most this often (per lesson). */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(30);
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    /**
     * Lessons are the same for everyone, so each (language, concept) is written by the AI at most once and then served
     * from the database to every learner. Only one request generates a given lesson at a time; the others wait and use
     * its result instead of calling the AI themselves.
     */
    public Lesson get(String language, String concept) {
        String key = language + "/" + concept;
        var existing = repo.findByLanguageAndConcept(language, concept);
        if (existing.isPresent() && !needsAi(existing.get(), key)) return existing.get();
        var c = catalog.find(language, concept).orElseThrow(() -> new IllegalArgumentException("Unknown concept"));
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            existing = repo.findByLanguageAndConcept(language, concept);   // someone may have finished it while we waited
            if (existing.isPresent() && !needsAi(existing.get(), key)) return existing.get();
            return generate(c, language, key, existing.orElse(null));
        }
    }

    /** A stored fallback is replaced once AI works, but not on every view: after a failure it waits {@link #RETRY_AFTER_FAILURE}. */
    private boolean needsAi(Lesson l, String key) {
        if (!"fallback".equals(l.source) || !ai.isConfigured()) return false;
        Instant failed = lastFailure.get(key);
        return failed == null || failed.plus(RETRY_AFTER_FAILURE).isBefore(Instant.now());
    }

    private Lesson generate(ConceptCatalog.Concept c, String language, String key, Lesson existing) {
        Lesson l = existing != null ? existing : new Lesson();
        l.language = language; l.concept = c.id();
        String md = null;
        if (ai.isConfigured()) {
            try {
                // Keep lessons consistent across languages: translate an existing one (same story and examples)
                // rather than writing a different lesson for each language.
                Lesson from = null;
                for (String other : ConceptCatalog.LANGUAGES) {
                    if (other.equals(language)) continue;
                    var o = repo.findByLanguageAndConcept(other, c.id()).filter(x -> "ai".equals(x.source));
                    if (o.isPresent() && (from == null || ConceptCatalog.JAVA.equals(other))) from = o.get();
                }
                String system = "You are a warm, precise " + ConceptCatalog.label(language) + " instructor writing a short interactive lesson in Markdown. Use ## headings, short paragraphs and runnable code blocks. No preamble.";
                md = from == null ? ai.chat(ai.model(), system, lessonPrompt(c, language), false)
                        : ai.chat(ai.model(), system, translatePrompt(from, language), false);
                lastFailure.remove(key);
            } catch (AiClient.QuotaExceeded q) {
                // Today's AI allowance is used up: show the plain lesson now but save nothing, so the next learner
                // (or tomorrow) can still generate the real one.
                Lesson plain = new Lesson();
                plain.language = language; plain.concept = c.id(); plain.source = "fallback"; plain.contentMd = fallbackContent(c, language);
                return plain;
            } catch (Exception e) {
                lastFailure.put(key, Instant.now());
                System.err.println("[lesson] AI failed, using fallback: " + e.getMessage());
            }
        }
        l.source = md != null ? "ai" : "fallback";
        l.contentMd = md != null ? md : fallbackContent(c, language);
        try {
            return repo.save(l);
        } catch (DataIntegrityViolationException raced) {                 // another server instance saved it first
            return repo.findByLanguageAndConcept(language, c.id()).orElse(l);
        }
    }

    private static String fallbackContent(ConceptCatalog.Concept c, String language) {
        if (ConceptCatalog.JAVA.equals(language) && FALLBACK.containsKey(c.id())) return FALLBACK.get(c.id());
        return "## " + c.title() + " in " + ConceptCatalog.label(language) + "\n\n" + c.summary()
                + "\n\nThe full lesson isn't ready yet. Check back soon.";
    }

    /** Same lesson in another language: keep headings, story and examples; rewrite only code and language notes. */
    static String translatePrompt(Lesson source, String language) {
        String to = ConceptCatalog.label(language), from = ConceptCatalog.label(source.language);
        return "Rewrite this " + from + " lesson for " + to + ". Keep exactly the same headings, order, story, class names (adapted to "
                + to + " naming conventions) and examples, so a learner switching languages sees the same lesson. Convert every code block to"
                + " idiomatic, runnable " + to + " in fenced ```" + language + " blocks, and replace " + from + "-specific remarks with the "
                + to + " equivalent. Output only the Markdown.\n\n" + source.contentMd;
    }

    /** What a good lesson looks like differs by track: a concept, a principle, a pattern, or a system design. */
    static String lessonPrompt(ConceptCatalog.Concept c, String language) {
        String lang = ConceptCatalog.label(language);
        String fence = " Use fenced ```" + language + " code blocks.";
        return switch (c.track()) {
            case "solid" -> "Write a lesson on the SOLID principle \"" + c.title() + "\" (" + c.summary() + ") in " + lang
                    + " for a learner who knows classes, inheritance and interfaces." + fence
                    + " Sections: ## The idea, ## A violation (small realistic code that breaks the principle and why it hurts),"
                    + " ## The fix (the same code refactored), ## How to spot it in real code, ## Try it (one sentence). Keep it under 600 words.";
            case "patterns" -> "Write a lesson on the " + c.group() + " design pattern \"" + c.title() + "\" (" + c.summary() + ") in " + lang
                    + " for a learner who knows OOP and SOLID." + fence
                    + " Sections: ## The problem (a concrete situation), ## The pattern (roles and how they collaborate), ## Example (one runnable program),"
                    + " ## When to use it and when not, ## Try it (one sentence). Keep it under 650 words.";
            case "lld" -> c.id().equals("lld-approach")
                    ? "Write a lesson on how to approach a low-level design problem in " + lang + ": " + c.summary() + fence
                      + " Sections: ## The process (clarify requirements, find entities, define relationships and responsibilities, apply SOLID and patterns, write code),"
                      + " ## Worked example (a small vending machine, with a class list and key code), ## Common mistakes, ## Try it (one sentence). Keep it under 700 words."
                    : "Write a low-level design walkthrough for \"" + c.title() + "\" (" + c.summary() + ") in " + lang + "." + fence
                      + " Sections: ## Requirements (functional, with scope limits), ## Core entities, ## Class design (responsibilities, relationships, patterns used),"
                      + " ## Code (the key classes, runnable and concise), ## Extensions and trade-offs, ## Try it (one sentence). Keep it under 800 words.";
            default -> "Write a lesson on \"" + c.title() + "\" in " + lang + " for a learner who understands basic syntax." + fence
                    + " Sections: ## The idea, ## How it works (with a runnable example), ## Common mistakes, ## Try it (one sentence). Keep it under 450 words.";
        };
    }

    private static final Map<String, String> FALLBACK = Map.of(
            "classes-objects", """
                    ## The idea
                    A **class** is a blueprint; an **object** is one thing built from it, with its own state.

                    ## How it works
                    ```java
                    public class Counter {
                        private int count;
                        public Counter(int start) { this.count = start; }
                        public void increment() { count++; }
                        public int value() { return count; }
                    }
                    Counter c = new Counter(5);
                    c.increment();          // c.value() == 6
                    ```

                    ## Common mistakes
                    - Forgetting `new`, or shadowing a field with a parameter and skipping `this.`

                    ## Try it
                    Model a small real-world thing with fields, a constructor and two methods.
                    """,
            "encapsulation", """
                    ## The idea
                    **Encapsulation** keeps an object's data private and exposes only safe operations, so the object can protect its own rules.

                    ## How it works
                    ```java
                    public class Thermostat {
                        private double target = 20;
                        public double getTarget() { return target; }
                        public void setTarget(double t) {
                            if (t < 5 || t > 30) throw new IllegalArgumentException("out of range");
                            this.target = t;
                        }
                    }
                    ```
                    Callers can never put the object in an invalid state.

                    ## Common mistakes
                    - Public fields, or getters/setters that simply expose everything with no validation.

                    ## Try it
                    Protect a balance so it can never go negative.
                    """,
            "inheritance", """
                    ## The idea
                    A subclass **extends** a superclass to reuse its behaviour and add or specialise its own.

                    ## How it works
                    ```java
                    class Employee {
                        protected final double base;
                        Employee(double base) { this.base = base; }
                        double pay() { return base; }
                    }
                    class Manager extends Employee {
                        private final double bonus;
                        Manager(double base, double bonus) { super(base); this.bonus = bonus; }
                        @Override double pay() { return super.pay() + bonus; }
                    }
                    ```

                    ## Common mistakes
                    - Forgetting to call `super(...)`, or using inheritance where "has-a" fits better.

                    ## Try it
                    Extend a class and build on its method with `super`.
                    """,
            "polymorphism", """
                    ## The idea
                    **Polymorphism** lets code work with a general type while each object runs its own version of a method at runtime.

                    ## How it works
                    ```java
                    abstract class Shape { abstract double area(); }
                    class Circle extends Shape {
                        double r; Circle(double r) { this.r = r; }
                        @Override double area() { return Math.PI * r * r; }
                    }
                    class Square extends Shape {
                        double s; Square(double s) { this.s = s; }
                        @Override double area() { return s * s; }
                    }
                    List<Shape> shapes = List.of(new Circle(1), new Square(2));
                    for (Shape s : shapes) System.out.println(s.area()); // correct area each time
                    ```

                    ## Common mistakes
                    - Overloading when you meant to override. Always add `@Override`.

                    ## Try it
                    Treat different shapes uniformly through the `Shape` type.
                    """);
}
