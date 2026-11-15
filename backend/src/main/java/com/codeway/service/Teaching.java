package com.codeway.service;

import com.codeway.domain.Challenge;
import com.codeway.runner.Lang;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Keeps lessons and challenges in step: what each concept's lesson must stress, which ideas are not yet taught, and a check
 * that rejects a generated challenge whose starter or reference solution relies on a feature the learner has not been taught.
 */
final class Teaching {
    private Teaching() {}

    /** Bump when the lesson prompts change: stored AI lessons from an older version are written again (once). */
    static final int LESSON_VERSION = 2;
    /** Bump when the challenge prompts or checks change: older generated challenges stop being served (they are kept, not deleted). */
    static final int CONTENT_VERSION = 2;

    /**
     * @param focus    what this lesson is about, in one line the lesson must make unmistakable
     * @param contrast the neighbouring idea learners mix it up with, and how the lesson must tell them apart (null: none)
     * @param avoid    ideas that come later in the path and must not appear in the lesson or its practice (null: none)
     */
    record Note(String focus, String contrast, String avoid) {}

    private static final Map<String, Note> NOTES = Map.of(
            "classes-objects", new Note("A class is a blueprint, an object is one thing built from it, and each object keeps its own values.",
                    null, "inheritance, abstract classes, interfaces"),
            "encapsulation", new Note("An object keeps its data private and offers a few safe methods, so it can protect its own rules.",
                    "Encapsulation vs just having getters and setters: a setter that accepts anything protects nothing.",
                    "inheritance, abstract classes, interfaces"),
            "inheritance", new Note("A subclass reuses everything from its superclass and then adds to it or changes some of it.",
                    "Inheritance vs composition: 'is-a' (a Dog is an Animal) vs 'has-a' (a Car has an Engine). Also say that the NEXT lesson, polymorphism, is what you can do once classes inherit.",
                    "abstract classes, interfaces, writing code against a general type (that is polymorphism, the next lesson)"),
            "polymorphism", new Note("The same method call runs different code depending on which kind of object it is called on, decided while the program runs.",
                    "Inheritance vs polymorphism. Inheritance is how classes SHARE code: Dog extends Animal, so a Dog gets what an Animal has. Polymorphism is what that lets you DO: write one piece of code against the general type and have each real object do its own version. Inheritance is the setup, polymorphism is the payoff. Include a small side-by-side table (what it is, when it is decided, what the code looks like) and a two-question test for 'am I looking at inheritance or polymorphism?'.",
                    "abstract classes and abstract methods (taught in the next lesson, Abstraction), interfaces (taught after that). Use an ordinary base class whose method the subclasses override."),
            "abstraction", new Note("Hide how something works behind a simple contract. An abstract class declares what every subclass must do, without saying how.",
                    "Abstraction vs encapsulation: abstraction decides WHAT you show (the contract); encapsulation protects the data behind it. Also: abstract class now, interface in the next lesson.",
                    "interfaces (the next lesson)"));

    static Note note(String concept) { return NOTES.get(concept); }

    // ---- what a challenge may rely on ----

    /** A feature of a language and the concept that teaches it; owner null means "needs an explanation wherever it is used". */
    private record Token(String owner, String word, Pattern re) {}

    private static Token t(String owner, String word, String regex) { return new Token(owner, word, Pattern.compile(regex, Pattern.MULTILINE)); }

    private static final Map<Lang, List<Token>> TOKENS = Map.of(
            Lang.JAVA, List.of(
                    t("inheritance", "extends", "\\bextends\\b"), t("inheritance", "super", "\\bsuper\\b"), t("inheritance", "@Override", "@Override"),
                    t("inheritance", "protected", "\\bprotected\\b"), t("abstraction", "abstract", "\\babstract\\b"),
                    t("interfaces", "interface", "\\binterface\\b"), t("interfaces", "implements", "\\bimplements\\b"),
                    t(null, "instanceof", "\\binstanceof\\b"), t(null, "getClass", "\\bgetClass\\s*\\("), t(null, "getSimpleName", "\\bgetSimpleName\\b"),
                    t(null, "enum", "\\benum\\b"), t(null, "record", "\\brecord\\s+\\w+\\s*\\("), t(null, "stream", "\\.stream\\s*\\(")),
            Lang.PYTHON, List.of(
                    t("inheritance", "super", "\\bsuper\\s*\\("), t("inheritance", "inheritance", "^\\s*class\\s+\\w+\\s*\\(\\s*(?!object\\b)[A-Za-z_]"),
                    t("abstraction", "ABC", "\\bABC\\b"), t("abstraction", "abstractmethod", "abstractmethod"), t("interfaces", "Protocol", "\\bProtocol\\b"),
                    t(null, "isinstance", "\\bisinstance\\s*\\("), t(null, "__class__", "__class__"), t(null, "type(", "\\btype\\s*\\("),
                    t(null, "lambda", "\\blambda\\b"), t(null, "@staticmethod", "@staticmethod"), t(null, "@classmethod", "@classmethod"),
                    t(null, "dataclass", "\\bdataclass\\b")),
            Lang.JAVASCRIPT, List.of(
                    t("inheritance", "extends", "\\bextends\\b"), t("inheritance", "super", "\\bsuper\\b"),
                    t(null, "instanceof", "\\binstanceof\\b"), t(null, "constructor.name", "constructor\\.name"), t(null, "static", "\\bstatic\\b")),
            Lang.CPP, List.of(
                    t("inheritance", "public", ":\\s*(public|protected|private)\\s+\\w+"), t("inheritance", "override", "\\boverride\\b"), t("inheritance", "virtual", "\\bvirtual\\b"),
                    t("abstraction", "= 0", "=\\s*0\\s*;"),
                    t(null, "dynamic_cast", "\\bdynamic_cast\\b"), t(null, "typeid", "\\btypeid\\b"), t(null, "template", "\\btemplate\\s*<"),
                    t(null, "std::function", "std::function")),
            Lang.CSHARP, List.of(
                    t("inheritance", "base", "\\bbase\\s*[.(]"), t("inheritance", "override", "\\boverride\\b"), t("inheritance", "virtual", "\\bvirtual\\b"),
                    t("inheritance", "protected", "\\bprotected\\b"), t("abstraction", "abstract", "\\babstract\\b"), t("interfaces", "interface", "\\binterface\\b"),
                    t(null, "typeof", "\\btypeof\\s*\\("), t(null, "GetType", "\\.GetType\\s*\\("), t(null, "is", "\\bis\\s+[A-Za-z_]"),
                    t(null, "delegate", "\\bdelegate\\b"), t(null, "Linq", "System\\.Linq"), t(null, "record", "\\brecord\\s+(class\\s+|struct\\s+)?\\w+"),
                    t(null, "yield", "\\byield\\b"), t(null, "async", "\\basync\\b")));

    /** Code without comments and string literals, so a word in a comment or in printed text is not mistaken for a feature. */
    static String code(String src, Lang lang) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = lang == Lang.PYTHON ? s.replaceAll("(?m)#.*$", "").replaceAll("(?s)\"\"\".*?\"\"\"", " ") : s.replaceAll("(?m)//.*$", "");
        return s.replaceAll("\"(?:[^\"\\\\\\n]|\\\\.)*\"", "\"\"").replaceAll("'(?:[^'\\\\\\n]|\\\\.)*'", "''");
    }

    /**
     * Null when the starter and reference solution only use what the learner has been taught, else why not. A feature taught by a
     * later concept is never allowed; one taught by this concept, or one no concept owns (reflection, lambdas...), must appear
     * in the lesson or be explained in the challenge description.
     */
    static String untaught(Challenge ch, ConceptCatalog catalog, String lessonMd) {
        var concept = catalog.find(ch.language, ch.concept);
        if (concept.isEmpty() || !"oop".equals(concept.get().track()) || ch.guideJson != null) return null;
        Lang lang = Lang.of(ch.language);
        int here = catalog.position(ch.concept);
        String known = ((lessonMd == null ? "" : lessonMd) + "\n" + ch.descriptionMd).toLowerCase(Locale.ROOT);
        Set<String> problems = new LinkedHashSet<>();
        for (var files : List.of(Json.files(ch.starterFilesJson), Json.files(ch.referenceSolutionJson))) {
            for (var f : files.entrySet()) {
                String src = code(f.getValue(), lang);
                for (Token tk : TOKENS.get(lang)) {
                    if (!tk.re.matcher(src).find()) continue;
                    if (tk.owner != null) {
                        int at = catalog.position(tk.owner);
                        if (at > here) { problems.add("uses `" + tk.word + "`, which is taught later in \"" + title(catalog, tk.owner) + "\""); continue; }
                        if (at < here) continue;               // an earlier concept: a prerequisite
                    }
                    if (lessonMd != null && !known.contains(tk.word.toLowerCase(Locale.ROOT)))
                        problems.add("uses `" + tk.word + "`, which neither the lesson nor your description explains");
                }
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems)
                + ". Rewrite the exercise so the learner only needs what the lesson teaches, or add a \"## New in this challenge\" section that explains it.";
    }

    private static String title(ConceptCatalog catalog, String id) {
        return catalog.find("java", id).map(ConceptCatalog.Concept::title).orElse(id);
    }
}
