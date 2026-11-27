package com.codeway.service;

import com.codeway.runner.Lang;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * The learning path: tracks in the order they should be learned (OOP, then SOLID, then design patterns, then
 * low-level design), each with its concepts. Every concept has a lesson in every language; practice (generated,
 * auto-graded challenges) is open per track.
 */
@Component
public class ConceptCatalog {
    public record Track(String id, int order, String title, String summary, boolean practice) {}
    /** @param group optional sub-heading inside a track (e.g. "Creational" for design patterns) */
    public record Concept(String id, String track, String group, String title, String summary, String symbol) {}

    public static final String JAVA = "java";
    public static final List<String> LANGUAGES = Arrays.stream(Lang.values()).map(l -> l.id).toList();

    public static String label(String language) { return Lang.of(language).label; }

    public static final List<Track> TRACKS = List.of(
            new Track("oop", 1, "Object-oriented programming", "Model the world in classes and objects: the foundation everything else builds on.", true),
            new Track("solid", 2, "SOLID principles", "Five rules that keep object-oriented code easy to change.", true),
            new Track("patterns", 3, "Design patterns", "Proven, named solutions to problems that keep coming back.", true),
            new Track("lld", 4, "Low-level design", "Turn requirements into classes and working code for real systems.", false));

    private static final List<Concept> CONCEPTS = List.of(
            // 1. OOP
            new Concept("classes-objects", "oop", null, "Classes & Objects", "Model things with fields, constructors and methods.", "cube"),
            new Concept("encapsulation", "oop", null, "Encapsulation", "Protect state and expose a safe, small surface.", "lock"),
            new Concept("inheritance", "oop", null, "Inheritance", "Reuse and specialise behaviour in subclasses.", "branch"),
            new Concept("polymorphism", "oop", null, "Polymorphism", "One interface, many behaviours at runtime.", "shapes"),
            new Concept("abstraction", "oop", null, "Abstraction", "Hide detail behind abstract types and contracts.", "layers"),
            new Concept("interfaces", "oop", null, "Interfaces", "Define capabilities that unrelated classes can share.", "plug"),
            // 2. SOLID
            new Concept("single-responsibility", "solid", null, "Single responsibility", "A class should have one reason to change.", "target"),
            new Concept("open-closed", "solid", null, "Open/closed", "Add behaviour by extending, not by editing what works.", "door"),
            new Concept("liskov-substitution", "solid", null, "Liskov substitution", "Subtypes must keep the promises of their base type.", "swap"),
            new Concept("interface-segregation", "solid", null, "Interface segregation", "Many small interfaces beat one that does everything.", "split"),
            new Concept("dependency-inversion", "solid", null, "Dependency inversion", "Depend on abstractions, not on concrete classes.", "invert"),
            // 3. Design patterns
            new Concept("singleton", "patterns", "Creational", "Singleton", "Exactly one instance, reachable from anywhere, and its costs.", "one"),
            new Concept("factory-method", "patterns", "Creational", "Factory method", "Let subclasses or a factory decide which class to create.", "factory"),
            new Concept("builder", "patterns", "Creational", "Builder", "Assemble complex objects step by step, readably.", "blocks"),
            new Concept("adapter", "patterns", "Structural", "Adapter", "Make an incompatible class fit the interface you need.", "plug"),
            new Concept("decorator", "patterns", "Structural", "Decorator", "Add responsibilities by wrapping, not by subclassing.", "layers"),
            new Concept("facade", "patterns", "Structural", "Facade", "One simple entry point in front of a complex subsystem.", "facade"),
            new Concept("strategy", "patterns", "Behavioral", "Strategy", "Swap algorithms at runtime behind one interface.", "route"),
            new Concept("observer", "patterns", "Behavioral", "Observer", "Notify many listeners when something changes.", "bell"),
            new Concept("command", "patterns", "Behavioral", "Command", "Turn requests into objects you can queue and undo.", "terminal"),
            new Concept("state", "patterns", "Behavioral", "State", "Change behaviour when an object's state changes.", "toggle"),
            // 4. Low-level design
            new Concept("lld-approach", "lld", null, "How to approach LLD", "Requirements, entities, relationships, then code.", "compass"),
            new Concept("parking-lot", "lld", null, "Parking lot", "Spots, vehicles, tickets and fees.", "car"),
            new Concept("library-system", "lld", null, "Library system", "Books, members, loans and fines.", "book"),
            new Concept("elevator", "lld", null, "Elevator system", "Requests, scheduling and state for a bank of lifts.", "elevator"),
            new Concept("splitwise", "lld", null, "Splitwise", "Groups, expenses and settling up balances.", "split"),
            new Concept("rate-limiter", "lld", null, "Rate limiter", "Token bucket and sliding window, behind one interface.", "gauge"));

    /**
     * Patterns with the guided "diagnose, then refactor in steps" practice. Empty on purpose: learners found it long and hard to follow,
     * so design patterns now use the same generated, difficulty-scaled exercises as the SOLID principles. The hand-written scenarios under
     * resources/patterns and the guided screens stay for attempts already in progress; list a concept id here to bring them back.
     */
    public static final Set<String> GUIDED = Set.of();

    public boolean isGuided(String conceptId) { return GUIDED.contains(conceptId); }

    /**
     * From SOLID on, the main file is written for the learner (the demo of how the classes are used) and is read-only: the learner
     * writes only the other classes, and the hidden tests don't depend on it. Until OOP is done the learner writes their own main.
     * Guided challenges (see GUIDED) ship their own working main and are left alone.
     */
    public boolean mainProvided(String conceptId) {
        var c = find("java", conceptId);
        return c.isPresent() && ("solid".equals(c.get().track()) || "patterns".equals(c.get().track()) || "lld".equals(c.get().track())) && !isGuided(conceptId);
    }

    /**
     * What a learner should already know before a concept's lesson and practice: concepts from earlier in the path.
     * Basic syntax (variables, methods, printing) is assumed everywhere. The lesson and challenge prompts use these lists,
     * and the app shows them as "Before you start" links.
     */
    private static final Map<String, List<String>> REQUIRES = Map.ofEntries(
            Map.entry("encapsulation", List.of("classes-objects")),
            Map.entry("inheritance", List.of("classes-objects", "encapsulation")),
            Map.entry("polymorphism", List.of("classes-objects", "inheritance")),
            Map.entry("abstraction", List.of("inheritance", "polymorphism")),
            Map.entry("interfaces", List.of("polymorphism", "abstraction")),
            Map.entry("single-responsibility", List.of("classes-objects", "encapsulation")),
            Map.entry("open-closed", List.of("polymorphism", "abstraction", "single-responsibility")),
            Map.entry("liskov-substitution", List.of("inheritance", "polymorphism")),
            Map.entry("interface-segregation", List.of("interfaces", "single-responsibility")),
            Map.entry("dependency-inversion", List.of("interfaces", "abstraction")),
            Map.entry("singleton", List.of("classes-objects", "encapsulation")),
            Map.entry("builder", List.of("classes-objects", "encapsulation")),
            Map.entry("facade", List.of("classes-objects")),
            Map.entry("factory-method", List.of("abstraction", "polymorphism")),
            Map.entry("adapter", List.of("interfaces")),
            Map.entry("decorator", List.of("interfaces", "open-closed")),
            Map.entry("strategy", List.of("interfaces", "open-closed")),
            Map.entry("observer", List.of("interfaces", "dependency-inversion")),
            Map.entry("command", List.of("interfaces")),
            Map.entry("state", List.of("polymorphism", "interfaces")));

    /** The concepts to know first, in learning order. */
    public List<Concept> requires(String conceptId) {
        return REQUIRES.getOrDefault(conceptId, List.of()).stream()
                .map(id -> CONCEPTS.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null)).filter(Objects::nonNull).toList();
    }

    /** Position of a concept in the learning path (0-based), or -1. */
    public int position(String conceptId) {
        for (int i = 0; i < CONCEPTS.size(); i++) if (CONCEPTS.get(i).id().equals(conceptId)) return i;
        return -1;
    }

    public List<Track> tracks() { return TRACKS; }

    public Optional<Track> track(String id) { return TRACKS.stream().filter(t -> t.id().equals(id)).findFirst(); }

    /** Concepts in learning order (track order, then listed order). Same concepts in every language. */
    public List<Concept> list(String language) { return LANGUAGES.contains(language) ? CONCEPTS : List.of(); }

    public Optional<Concept> find(String language, String id) {
        return list(language).stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** True when auto-graded practice is open for this concept (its track has practice) in this language. */
    public boolean practiceOpen(String language, String conceptId) {
        if (!LANGUAGES.contains(language)) return false;
        var c = find(language, conceptId);
        if (c.isEmpty()) return false;
        return track(c.get().track()).map(Track::practice).orElse(false);
    }
}
