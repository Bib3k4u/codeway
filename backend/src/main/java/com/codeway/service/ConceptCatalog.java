package com.codeway.service;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConceptCatalog {
    public record Concept(String id, String title, String summary, String symbol) {}

    public static final String JAVA = "java";
    public static final List<String> LANGUAGES = List.of("java", "python", "javascript");
    public static final Set<String> PRACTICE = Set.of("java");

    public static String label(String language) {
        return switch (language) { case "python" -> "Python"; case "javascript" -> "JavaScript"; default -> "Java"; };
    }

    private final List<Concept> java = List.of(
            new Concept("classes-objects", "Classes & Objects", "Model things with fields, constructors and methods.", "cube"),
            new Concept("encapsulation", "Encapsulation", "Protect state and expose a safe, small surface.", "lock"),
            new Concept("inheritance", "Inheritance", "Reuse and specialise behaviour with extends and super.", "branch"),
            new Concept("polymorphism", "Polymorphism", "One interface, many behaviours at runtime.", "shapes"),
            new Concept("abstraction", "Abstraction", "Hide detail behind abstract classes and contracts.", "layers"),
            new Concept("interfaces", "Interfaces", "Define capabilities that unrelated classes can share.", "plug"));

    public List<Concept> list(String language) { return LANGUAGES.contains(language) ? java : List.of(); }

    public Optional<Concept> find(String language, String id) {
        return list(language).stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
