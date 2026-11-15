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

    /** True for an AI lesson written with an older prompt: it is written again, once, when someone next opens it. */
    static boolean outdated(Lesson l) { return "ai".equals(l.source) && (l.version == null || l.version < Teaching.LESSON_VERSION); }

    /** A stored fallback (or outdated lesson) is replaced once AI works, but not on every view: after a failure it waits {@link #RETRY_AFTER_FAILURE}. */
    private boolean needsAi(Lesson l, String key) {
        if ((!"fallback".equals(l.source) && !outdated(l)) || !ai.isConfigured()) return false;
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
                    var o = repo.findByLanguageAndConcept(other, c.id()).filter(x -> "ai".equals(x.source) && !outdated(x));
                    if (o.isPresent() && (from == null || ConceptCatalog.JAVA.equals(other))) from = o.get();
                }
                String system = "You are a warm, precise " + ConceptCatalog.label(language) + " instructor writing a short interactive lesson in Markdown. Use ## headings, short paragraphs and runnable code blocks. No preamble.";
                md = from == null ? ai.chat(ai.model(), system, lessonPrompt(c, language), false)
                        : ai.chat(ai.model(), system, translatePrompt(from, language), false);
                lastFailure.remove(key);
            } catch (AiClient.QuotaExceeded q) {
                // Today's AI allowance is used up: show the plain lesson now but save nothing, so the next learner
                // (or tomorrow) can still generate the real one. An older AI lesson is better than the plain one.
                if (outdated(l)) return l;
                Lesson plain = new Lesson();
                plain.language = language; plain.concept = c.id(); plain.source = "fallback"; plain.contentMd = fallbackContent(c, language);
                return plain;
            } catch (Exception e) {
                lastFailure.put(key, Instant.now());
                System.err.println("[lesson] AI failed, using fallback: " + e.getMessage());
                if (outdated(l)) return l;                       // keep the older AI lesson rather than replace it with the plain one
            }
        }
        l.source = md != null ? "ai" : "fallback";
        if (md != null) l.version = Teaching.LESSON_VERSION;
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
    String lessonPrompt(ConceptCatalog.Concept c, String language) {
        String lang = ConceptCatalog.label(language);
        String fence = " Use fenced ```" + language + " code blocks." + CARE;
        String before = beforeYouStart(c);
        return switch (c.track()) {
            case "solid" -> "Write a lesson on the SOLID principle \"" + c.title() + "\" (" + c.summary() + ") in " + lang
                    + " for a learner who knows classes, inheritance and interfaces." + fence + before
                    + " Sections: ## Before you start, ## The idea, ## A violation (small realistic code that breaks the principle and why it hurts),"
                    + " ## The fix (the same code refactored), ## How to spot it in real code, ## Try it (one sentence on the kind of exercise that follows). Keep it under 650 words.";
            case "patterns" -> "Write a lesson on the " + c.group() + " design pattern \"" + c.title() + "\" (" + c.summary() + ") in " + lang
                    + " for a learner who knows OOP and SOLID." + fence + before
                    + " Sections: ## Before you start, ## The problem (a concrete situation), ## The pattern (roles and how they collaborate), ## Example (one runnable program),"
                    + " ## When to use it and when not, ## Try it (one sentence on the kind of exercise that follows). Keep it under 700 words.";
            case "lld" -> c.id().equals("lld-approach")
                    ? "Write a lesson on how to approach a low-level design problem in " + lang + ": " + c.summary() + fence
                      + " Sections: ## The process (clarify requirements, find entities, define relationships and responsibilities, apply SOLID and patterns, write code),"
                      + " ## Worked example (a small vending machine, with a class list and key code), ## Common mistakes, ## Try it (one sentence). Keep it under 700 words."
                    : "Write a low-level design walkthrough for \"" + c.title() + "\" (" + c.summary() + ") in " + lang + "." + fence
                      + " Sections: ## Requirements (functional, with scope limits), ## Core entities, ## Class design (responsibilities, relationships, patterns used),"
                      + " ## Code (the key classes, runnable and concise), ## Extensions and trade-offs, ## Try it (one sentence). Keep it under 800 words.";
            default -> oopPrompt(c, lang, fence, before);
        };
    }

    /** Shared by every lesson: say only what is certainly true, and never leave a word unexplained. */
    private static final String CARE = " Only state facts you are certain are true about the language and its standard library; if unsure, leave it out."
            + " Explain every keyword, annotation and library call the first time it appears, in plain words, right next to the code."
            + " Do not use any feature that this lesson does not explain.";

    /** The prerequisites, so the lesson can open by saying what the learner should already know. */
    private String beforeYouStart(ConceptCatalog.Concept c) {
        var req = catalog.requires(c.id());
        if (req.isEmpty()) return " Under ## Before you start say that this lesson only needs basic syntax: variables, methods, printing.";
        return " Under ## Before you start list these earlier lessons, each with a one-line reminder of what it taught, and say to read them first if anything is unfamiliar: "
                + req.stream().map(r -> r.title() + " (" + r.summary() + ")").collect(java.util.stream.Collectors.joining("; ")) + ".";
    }

    /** The OOP track: concepts that learners mix up, so each lesson is explicit about what it covers, how it differs from its neighbour, and which words are new. */
    private static String oopPrompt(ConceptCatalog.Concept c, String lang, String fence, String before) {
        Teaching.Note n = Teaching.note(c.id());
        StringBuilder p = new StringBuilder("Write a lesson on \"" + c.title() + "\" in " + lang + " for a learner who understands basic syntax.").append(fence).append(before);
        if (n != null) {
            p.append(" The one thing this lesson must make clear: ").append(n.focus());
            if (n.avoid() != null) p.append(" These ideas are taught later, so do not use or mention them: ").append(n.avoid()).append(".");
        }
        p.append(" Sections, in this order: ## Before you start, ## The idea (plain words, one everyday analogy, at most four sentences),")
         .append(" ## How it works (build ONE example up in small steps, each step a runnable block followed by what changed and why; comment the new lines),");
        if (n != null && n.contrast() != null)
            p.append(" ## ").append(contrastHeading(c.id())).append(" (").append(n.contrast()).append("),");
        p.append(" ## New words (a short list of every term and keyword the lesson introduced, one line each, in the order they appeared),")
         .append(" ## Common mistakes, ## Try it (two sentences: what the practice exercise will ask you to build, and which of the words above it uses). Keep it between 600 and 900 words.");
        return p.toString();
    }

    private static String contrastHeading(String concept) {
        return switch (concept) {
            case "polymorphism" -> "Inheritance vs polymorphism";
            case "inheritance" -> "Is-a vs has-a";
            case "abstraction" -> "Abstraction vs encapsulation";
            case "encapsulation" -> "Private data vs plain getters and setters";
            default -> "How this differs from its neighbour";
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
                    ## Before you start
                    You should know **classes and objects**, and **inheritance** (a subclass extends a superclass and can override its methods). Read those lessons first if either is unfamiliar.

                    ## The idea
                    **Polymorphism** means "many forms". You call the same method, and the object you called it on decides what happens. Think of a TV remote: the *power* button is the same, but each device does its own thing when you press it.

                    ## How it works
                    Start with a base class and two subclasses that each override `sound()`:
                    ```java
                    class Animal {
                        String sound() { return "..."; }          // a default
                    }
                    class Dog extends Animal {
                        @Override String sound() { return "Woof"; } // Dog's own version
                    }
                    class Cat extends Animal {
                        @Override String sound() { return "Meow"; } // Cat's own version
                    }
                    ```
                    Now write code that only knows about `Animal`:
                    ```java
                    Animal a = new Dog();      // a variable of the general type Animal holds a Dog
                    System.out.println(a.sound());   // prints Woof

                    Animal[] pets = { new Dog(), new Cat() };
                    for (Animal pet : pets) {
                        System.out.println(pet.sound());   // Woof, then Meow
                    }
                    ```
                    The loop is written once and never asks "is this a Dog or a Cat?". Java looks at the real object when the line runs and picks that class's `sound()`. A new `Cow` class would work in the same loop without changing it.

                    ## Inheritance vs polymorphism
                    | | Inheritance | Polymorphism |
                    |---|---|---|
                    | What is it? | Classes **share** code: `Dog extends Animal` | One call, **different behaviour** per object |
                    | Where do you see it? | The word `extends` | A call like `pet.sound()` on a general type |
                    | What does it give you? | Less repeated code | Code that works for any subclass |

                    Two questions help: *Is a class getting things from another class?* That is inheritance. *Is one line of code doing different things for different objects?* That is polymorphism. Inheritance is the setup, polymorphism is the payoff.

                    ## New words
                    - **Override**: a subclass writes its own version of a method it inherited. `@Override` asks Java to check that you really are replacing one.
                    - **General type**: a variable whose type is the superclass (`Animal`) and which can hold any subclass object.

                    ## Common mistakes
                    - Misspelling the method name or changing its parameters: that creates a new method instead of overriding. `@Override` makes Java tell you.
                    - Calling a method that exists only on the subclass through the general type: `Animal a = new Dog();` can only call what `Animal` has.

                    ## Try it
                    Build a base class and two subclasses that each override one method, then call it on a list of the general type. You will use `extends`, `@Override` and a variable of the general type.
                    """);
}
