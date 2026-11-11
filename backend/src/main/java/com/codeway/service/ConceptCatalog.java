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
            new Track("solid", 2, "SOLID principles", "Five rules that keep object-oriented code easy to change.", false),
            new Track("patterns", 3, "Design patterns", "Proven, named solutions to problems that keep coming back.", false),
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

    public List<Track> tracks() { return TRACKS; }

    public Optional<Track> track(String id) { return TRACKS.stream().filter(t -> t.id().equals(id)).findFirst(); }

    /** Concepts in learning order (track order, then listed order). Same concepts in every language. */
    public List<Concept> list(String language) { return LANGUAGES.contains(language) ? CONCEPTS : List.of(); }

    public Optional<Concept> find(String language, String id) {
        return list(language).stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** True when auto-graded practice is open for this concept (its track has practice) in this language. */
    public boolean practiceOpen(String language, String conceptId) {
        return LANGUAGES.contains(language) && find(language, conceptId).flatMap(c -> track(c.track())).map(Track::practice).orElse(false);
    }
}
