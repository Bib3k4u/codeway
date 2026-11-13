package com.codeway.service;

import com.codeway.domain.Challenge;
import com.codeway.runner.Lang;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Guided (design pattern) challenges: the learner sees working code that breaks a principle, diagnoses which one, then
 * refactors it in small tested steps and only afterwards learns the pattern's name. This holds the format rules, the
 * answer key (ours, never the AI's) and the prompt that turns a hand-written anchor scenario into fresh variants.
 */
final class GuidedPrompts {
    private GuidedPrompts() {}

    static final List<String[]> PRINCIPLES = List.of(
            new String[]{"srp", "Single Responsibility"}, new String[]{"ocp", "Open/Closed"}, new String[]{"lsp", "Liskov Substitution"},
            new String[]{"isp", "Interface Segregation"}, new String[]{"dip", "Dependency Inversion"});

    /** Principles that count as a right answer to "what does this code break?" for each pattern (first = the main one). */
    static final Map<String, List<String>> ANSWERS = Map.of(
            "strategy", List.of("ocp"),
            "state", List.of("ocp"),
            "observer", List.of("dip"),
            "factory-method", List.of("dip", "ocp"),
            "decorator", List.of("ocp", "srp"));

    private static final Map<String, List<String>> TWISTS = Map.of(
            "strategy", List.of("an unknown type should fall back to a default rule", "a rule needs a second input besides the main one", "rules can be swapped while the program runs"),
            "observer", List.of("a listener can unsubscribe, even while a notification is being delivered", "listeners only care about some events", "the subject reports what changed, not just the new value"),
            "factory-method", List.of("an unknown kind should fail with a clear message", "new kinds can be registered without touching the factory", "the product needs one constructor argument"),
            "decorator", List.of("the order the wrappers are stacked in changes the result", "a wrapper changes both a price and a description", "the same wrapper can be applied twice"));

    private static final List<String> DOMAINS = List.of(
            "insurance premiums", "discount rules for a shop", "tax by region", "document export formats", "log message formatting", "game damage types",
            "ticket pricing", "sensor alerts", "order notifications", "report rendering", "text transformations", "delivery slots", "loyalty points",
            "meal ordering", "subscription plans", "file compression levels", "support-ticket routing", "workout plans");

    /** The five principles as quiz options, and our answer key; the AI only writes the question and the explanation. */
    static String normalize(String guideJson, String concept) {
        try {
            ObjectNode g = (ObjectNode) Json.M.readTree(guideJson);
            ObjectNode quiz = g.has("quiz") && g.get("quiz").isObject() ? (ObjectNode) g.get("quiz") : g.putObject("quiz");
            ArrayNode options = quiz.putArray("options");
            for (String[] p : PRINCIPLES) options.addObject().put("id", p[0]).put("text", p[1]);
            ArrayNode answers = quiz.putArray("answers");
            ANSWERS.getOrDefault(concept, List.of("ocp")).forEach(answers::add);
            return Json.M.writeValueAsString(g);
        } catch (Exception e) {
            return guideJson;
        }
    }

    private static final Pattern STEP_TEST = Pattern.compile("^S(\\d+):");

    /** Null when a guide is well-formed and agrees with the tests, else why not. */
    static String checkGuide(String guideJson, List<String> testNames) {
        try {
            JsonNode g = Json.M.readTree(guideJson);
            JsonNode quiz = g.path("quiz");
            if (quiz.path("question").asText("").isBlank() || quiz.path("explanation").asText("").isBlank()) return "guide: quiz needs a question and an explanation";
            Set<String> ids = new HashSet<>();
            for (JsonNode o : quiz.path("options")) { if (o.path("id").asText("").isBlank() || o.path("text").asText("").isBlank()) return "guide: bad quiz option"; ids.add(o.path("id").asText()); }
            if (ids.size() < 3 || ids.size() > 6) return "guide: quiz needs 3 to 6 options";
            if (!quiz.path("answers").isArray() || quiz.path("answers").isEmpty()) return "guide: quiz needs answers";
            for (JsonNode a : quiz.path("answers")) if (!ids.contains(a.asText())) return "guide: answer " + a.asText() + " is not an option";
            JsonNode steps = g.path("steps");
            if (!steps.isArray() || steps.size() < 2 || steps.size() > 6) return "guide: 2 to 6 steps are needed";
            for (JsonNode s : steps) if (s.path("title").asText("").isBlank() || s.path("md").asText("").isBlank()) return "guide: every step needs a title and md";
            JsonNode r = g.path("reveal");
            for (String k : List.of("pattern", "principle", "summary")) if (r.path(k).asText("").isBlank()) return "guide: reveal." + k + " is missing";
            int[] perStep = new int[steps.size() + 1];
            int guards = 0;
            for (String name : testNames) {
                if (name.startsWith("Guard:")) { guards++; continue; }
                var m = STEP_TEST.matcher(name);
                if (!m.find() || Integer.parseInt(m.group(1)) < 1 || Integer.parseInt(m.group(1)) > steps.size()) return "guide: test name must start with \"Guard:\" or \"S1:\"..\"S" + steps.size() + ":\": " + name;
                perStep[Integer.parseInt(m.group(1))]++;
            }
            if (guards == 0) return "guide: at least one Guard: test is needed";
            for (int i = 1; i <= steps.size(); i++) if (perStep[i] == 0) return "guide: step " + i + " has no tests";
            return null;
        } catch (Exception e) {
            return "guide is not valid JSON: " + e.getMessage();
        }
    }

    /** A fresh scenario for the same pattern, modelled on the hand-written anchor. */
    static String variant(Lang lang, ConceptCatalog.Concept c, int difficulty, String scaffolding, String avoid, Challenge anchor, String langRules) {
        Random rnd = ThreadLocalRandom.current();
        List<String> domains = new ArrayList<>(DOMAINS);
        Collections.shuffle(domains, rnd);
        List<String> twists = TWISTS.getOrDefault(c.id(), List.of("a small extra requirement of your choice"));
        String twist = twists.get(rnd.nextInt(twists.size()));
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("title", anchor.title);
        example.put("descriptionMd", anchor.descriptionMd);
        example.put("expectedMinutes", anchor.expectedMinutes);
        example.put("starterFiles", Json.files(anchor.starterFilesJson));
        example.put("referenceSolution", Json.files(anchor.referenceSolutionJson));
        example.put("tests", Json.files(anchor.testsJson));
        example.put("hints", Json.strings(anchor.hintsJson));
        try { example.put("guide", Json.M.readValue(anchor.guideJson, Map.class)); } catch (Exception ignored) { }
        String scaffoldRule = switch (scaffolding) {
            case "HEAVY" -> "Stub files are complete signatures with a TODO comment naming the step.";
            case "PARTIAL" -> "Stub files are empty types with a one-line TODO naming the step; the learner writes the signatures.";
            default -> "Stub files are empty types with only a TODO naming the step; the brief gives exact names but no signatures.";
        };
        return """
                Create ONE new guided %1$s exercise that teaches the same design lesson as the EXAMPLE below, in a DIFFERENT domain.
                Concept: %2$s. Difficulty %3$d of 5. %4$s
                Domain: pick whichever of these fits naturally: %5$s. Twist to build in: %6$s.
                Avoid repeating these existing titles and their domains: %7$s

                How a guided exercise works: the learner reads WORKING code with a design problem (it breaks a SOLID principle),
                answers which principle that is, then refactors it in 3 or 4 small steps, each with its own tests. The old behaviour
                must keep working at every step ("Guard:" tests). The learner is NOT told the pattern's name until they finish.

                EXAMPLE (Java). Copy its structure, JSON shape and test-name convention exactly, but not its domain:
                %8$s

                Return JSON with exactly these keys: title, descriptionMd, expectedMinutes, starterFiles, referenceSolution, tests, hints, guide.
                Rules:
                %9$s
                - Never write the pattern's name (or the word "pattern") in title, descriptionMd, step titles or step md. The "guide.reveal" is where it is named.
                - starterFiles: the problem code, which WORKS, plus an empty stub file for every NEW type the step tests refer to, each with a TODO naming its
                  step, so everything compiles and runs from the start. %10$s Include the main file, demonstrating the old behaviour.
                - referenceSolution: every file, complete; all tests pass.
                - tests: one file. Test names start with "Guard:" (2 or 3 tests of the OLD behaviour, which must pass on the starter AND the reference) or "S1:", "S2:"...
                  (the steps, 1 to 3 tests each; step N's tests only need what step N's md asks for). The LAST step adds a brand-new variant defined INSIDE the test
                  file (a new rule, listener, product or wrapper) and checks it works without the learner changing existing classes.
                - guide.steps: 3 or 4 items {"title","md"}; md says exactly what to do and the exact names to use. guide.quiz: {"question","explanation"} only
                  (the options and the answer are added for you); the question asks which principle the code breaks most clearly, and the explanation says why,
                  pointing at the classes or lines. guide.reveal: {"pattern","principle","roles":[{"name","is"}],"summary"} where roles map the pattern's roles to the
                  learner's classes, and summary is 2 or 3 sentences including when NOT to use it.
                - hints: 3 strings: a guiding question, the key idea without naming the pattern, a partial snippet.
                - descriptionMd (at least 120 words): the story, what must keep working, what the learner will do at a high level, "## Example" with the exact
                  output of the main file, and "Format:" notes on how it prints.
                """.formatted(lang.label, c.title(), difficulty, scaffoldRule, String.join("; ", domains.subList(0, 3)), twist,
                avoid.isBlank() ? "none" : avoid, Json.write(example), langRules, scaffoldRule);
    }
}
