package com.codeway.service;

import com.codeway.ai.AiClient;
import com.codeway.domain.*;
import com.codeway.repo.*;
import com.codeway.runner.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChallengeService {
    private static final int MAX_GENERATION_TRIES = 3;

    private final ChallengeRepo challenges;
    private final AttemptRepo attempts;
    private final CodeRunner runner;
    private final AiClient ai;
    private final ConceptCatalog catalog;

    public ChallengeService(ChallengeRepo challenges, AttemptRepo attempts, CodeRunner runner, AiClient ai, ConceptCatalog catalog) {
        this.challenges = challenges; this.attempts = attempts; this.runner = runner; this.ai = ai; this.catalog = catalog;
    }

    public boolean hasContent(String language, String concept) {
        if (!catalog.practiceOpen(language, concept)) return false;
        return ai.isConfigured() || !challenges.findByLanguageAndConceptAndValidatedTrue(language, concept).isEmpty();
    }

    /** Pipeline: bank (unseen, exact match) -> generate+validate -> nearest unseen -> nearest seen. */
    public Challenge pick(Long userId, String language, String concept, int difficulty, String scaffolding) {
        var bank = challenges.findByLanguageAndConceptAndValidatedTrue(language, concept);
        Map<Long, Long> lastSeen = new HashMap<>();
        for (Attempt a : attempts.findByUserId(userId)) {
            long t = a.startedAt.toEpochMilli();
            lastSeen.merge(a.challengeId, t, Math::max);
        }
        List<Challenge> unseen = bank.stream().filter(c -> !lastSeen.containsKey(c.id)).toList();

        var exact = unseen.stream().filter(c -> c.difficulty == difficulty && c.scaffolding.equals(scaffolding)).findFirst();
        if (exact.isPresent()) return exact.get();

        if (ai.isConfigured()) {
            Challenge g = generate(language, concept, difficulty, scaffolding, bank);
            if (g != null) return g;
        }

        Comparator<Challenge> nearest = Comparator.comparingInt((Challenge c) -> Math.abs(c.difficulty - difficulty))
                .thenComparingInt(c -> c.scaffolding.equals(scaffolding) ? 0 : 1);
        var pickUnseen = unseen.stream().min(nearest);
        if (pickUnseen.isPresent()) return pickUnseen.get();
        return bank.stream()
                .min(Comparator.comparingLong((Challenge c) -> lastSeen.getOrDefault(c.id, 0L)).thenComparing(nearest))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No challenges are ready for this concept yet. Please try again later, or pick another concept."));
    }

    // ---- generation ----
    Challenge generate(String language, String concept, int difficulty, String scaffolding, List<Challenge> bank) {
        var c = catalog.find(language, concept).orElseThrow();
        String avoid = bank.stream().map(x -> x.title).collect(Collectors.joining("; "));
        for (int attempt = 1; attempt <= MAX_GENERATION_TRIES; attempt++) {
            try {
                Lang lang = Lang.of(language);
                String raw = ai.chat(ai.model(), system(lang), userPrompt(lang, c, difficulty, scaffolding, avoid), true);
                Challenge ch = parse(raw, language, concept, difficulty, scaffolding);
                String problem = validate(ch);
                if (problem == null) {
                    ch.validated = true;
                    ch.origin = "ai";
                    return challenges.save(ch);
                }
                System.err.println("[challenge] generation " + attempt + " rejected: " + problem);
            } catch (Exception e) {
                System.err.println("[challenge] generation " + attempt + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    // ---- the same exercise in another language ----
    /** The family a challenge belongs to: itself, or the challenge it was translated from. */
    public static long family(Challenge ch) { return ch.familyId != null ? ch.familyId : ch.id; }

    /** This exercise in {@code language}: an existing translation, or a new validated one. Null if neither works. */
    public Challenge inLanguage(Challenge src, String language) {
        if (src.language.equals(language)) return src;
        long fam = family(src);
        var existing = challenges.findByLanguageAndConceptAndFamilyIdAndValidatedTrue(language, src.concept, fam);
        if (!existing.isEmpty()) return existing.get(0);
        // an original in the target language whose family is this one cannot exist; the only other match is the root
        var root = challenges.findById(fam).filter(c -> c.language.equals(language) && c.validated);
        if (root.isPresent()) return root.get();
        if (!ai.isConfigured()) return null;
        Lang lang = Lang.of(language);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = ai.chat(ai.model(), system(lang), translatePrompt(src, lang), true);
                Challenge ch = parse(raw, language, src.concept, src.difficulty, src.scaffolding);
                ch.expectedMinutes = src.expectedMinutes;
                String problem = validate(ch);
                if (problem == null) {
                    ch.validated = true; ch.origin = "ai-translation"; ch.familyId = fam;
                    return challenges.save(ch);
                }
                System.err.println("[challenge] translation " + attempt + " rejected: " + problem);
            } catch (Exception e) {
                System.err.println("[challenge] translation " + attempt + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    private static String translatePrompt(Challenge src, Lang lang) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("title", src.title);
        source.put("descriptionMd", src.descriptionMd);
        source.put("starterFiles", Json.files(src.starterFilesJson));
        source.put("referenceSolution", Json.files(src.referenceSolutionJson));
        source.put("tests", Json.files(src.testsJson));
        source.put("hints", Json.strings(src.hintsJson));
        return """
                Translate this %1$s exercise into %2$s. It must be the SAME exercise: same title, same story, same classes and
                behaviour, same requirements, same test cases in the same order with the same names, and the same scaffolding
                (TODO gaps in the same places). Only adapt naming and idioms to %2$s (e.g. snake_case methods in Python),
                and rewrite every signature in descriptionMd for %2$s.

                Source exercise (JSON):
                %3$s

                Return JSON with exactly these keys: title, descriptionMd, expectedMinutes, starterFiles, referenceSolution, tests, hints.
                Rules for %2$s:
                %4$s
                - Include %5$s in starterFiles and referenceSolution; tests is a single file %6$s.
                - The starter must NOT pass the tests; the reference solution must pass all of them.
                - descriptionMd keeps the same sections; its Example block must be exactly what the reference %5$s prints.
                """.formatted(Lang.of(src.language).label, lang.label, Json.write(source), langRules(lang), lang.mainFile, lang.testFile);
    }

    /** Index just past the statement starting at {@code start}: where its parentheses balance again. */
    static int statementEnd(String[] lines, int start) {
        int depth = 0; boolean opened = false;
        for (int i = start; i < lines.length; i++) {
            boolean inStr = false; char q = 0;
            String l = lines[i];
            for (int j = 0; j < l.length(); j++) {
                char c = l.charAt(j);
                if (inStr) { if (c == '\\') j++; else if (c == q) inStr = false; continue; }
                if (c == '"' || c == '\'' || c == '`') { inStr = true; q = c; }
                else if (c == '(') { depth++; opened = true; }
                else if (c == ')') depth--;
            }
            if (opened && depth <= 0) return i + 1;
        }
        return Math.min(lines.length, start + 1);
    }

    // ---- visible examples vs hidden tests ----
    /** How many tests (the first ones) are shown as worked examples; the rest stay hidden until revealed. */
    public static int visibleCount(int total) { return total <= 1 ? total : Math.max(1, Math.min(3, total / 2)); }

    /**
     * Source snippets of the visible tests, so learners can read what is checked. For Java, JavaScript and C++ a test
     * is its run(...) call and the lambda after it; in Python a test may use a helper def written above the call.
     */
    public static List<Map<String, String>> examples(Challenge ch) {
        List<String> names = testNames(ch);
        int n = visibleCount(names.size());
        Lang lang = Lang.of(ch.language);
        String src = Json.files(ch.testsJson).getOrDefault(lang.testFile, "");
        String[] lines = src.split("\n", -1);
        List<Integer> calls = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(lang.testCall)) calls.add(i);
        List<Map<String, String>> out = new ArrayList<>();
        for (int k = 0; k < n && k < names.size(); k++) {
            String name = names.get(k);
            int at = -1;
            for (int c : calls) if (lines[c].contains(name)) { at = c; break; }
            String code = "";
            if (at >= 0) {
                int idx = calls.indexOf(at);
                int from = at, to;
                if (lang == Lang.PYTHON) {
                    // a helper def written just above the call belongs to this test
                    from = idx > 0 ? calls.get(idx - 1) + 1 : at;
                    while (from < at && (lines[from].isBlank() || lines[from].startsWith("import") || lines[from].startsWith("from "))) from++;
                }
                to = statementEnd(lines, at);
                List<String> block = new ArrayList<>(Arrays.asList(lines).subList(from, Math.min(to, from + 16)));
                if (to - from > 16) block.add("    ...");
                code = String.join("\n", block).stripIndent().strip();
            }
            out.add(Map.of("name", name, "code", code));
        }
        return out;
    }

    /** Returns null when the challenge is sound, otherwise the reason it was rejected. */
    public String validate(Challenge ch) {
        Map<String, String> tests = Json.files(ch.testsJson);
        Map<String, String> starter = Json.files(ch.starterFilesJson);
        Map<String, String> ref = Json.files(ch.referenceSolutionJson);
        Lang lang = Lang.of(ch.language);
        if (!tests.containsKey(lang.testFile)) return "tests must include " + lang.testFile;
        if (!ref.containsKey(lang.mainFile)) return "referenceSolution must include " + lang.mainFile;
        for (String f : concat(starter, ref)) {
            if (!lang.fileName.matcher(f).matches()) return "bad file name " + f;
            if (f.equals(lang.testFile) || f.toLowerCase(Locale.ROOT).startsWith("check.")) return "learner files must not include " + f;
        }
        RunResult good = runner.run(lang.id, ref, tests, CodeRunner.Mode.TESTS);
        if (!good.compiled()) return "reference does not compile: " + good.diagnostics();
        if (good.total() < 3) return "needs at least 3 tests, got " + good.total() + " | stderr: " + good.stderr() + " | stdout: " + good.stdout();
        if (!good.allPassed()) return "reference fails: " + good.tests().stream().filter(t -> !t.passed()).toList() + " " + good.stderr();
        ch.testNamesJson = Json.write(good.tests().stream().map(RunResult.TestResult::name).toList());
        RunResult start = runner.run(lang.id, starterWithMain(lang.id, starter), tests, CodeRunner.Mode.TESTS);
        if (start.allPassed()) return "starter already passes all tests";
        return null;
    }

    /** A runnable main file for each language, used when the starter doesn't include one. */
    static String mainBoilerplate(Lang lang) {
        return switch (lang) {
            case JAVA -> """
                    public class Main {
                        public static void main(String[] args) {
                            // TODO: create objects here and print results to show your code working
                        }
                    }
                    """;
            case PYTHON -> """
                    def main():
                        # TODO: create objects here and print results to show your code working
                        pass


                    if __name__ == "__main__":
                        main()
                    """;
            case JAVASCRIPT -> """
                    // TODO: require your classes, create objects here and print results with console.log
                    function main() {
                    }

                    main();
                    """;
            case CPP -> """
                    #include <iostream>

                    int main() {
                        // TODO: create objects here and print results to show your code working
                        return 0;
                    }
                    """;
        };
    }

    /** Starter files as shown to the learner: always includes the language's main file with an entry point. */
    public static Map<String, String> starterWithMain(String language, Map<String, String> starter) {
        Lang lang = Lang.of(language);
        Map<String, String> m = new LinkedHashMap<>(starter);
        String main = m.get(lang.mainFile);
        boolean ok = main != null && switch (lang) {
            case JAVA -> main.contains("static void main");
            case CPP -> main.contains("main(");
            default -> !main.isBlank();
        };
        if (!ok) m.put(lang.mainFile, mainBoilerplate(lang));
        return m;
    }

    private static final Pattern TEST_NAME = Pattern.compile("(?:Check\\.run|check\\.run|check::run)\\(\\s*([\"'`])((?:(?!\\1)[^\\\\]|\\\\.)*)\\1");

    /** Test names as the reference run reported them; older challenges fall back to reading the test source. */
    public static List<String> testNames(Challenge ch) {
        if (ch.testNamesJson != null) return Json.strings(ch.testNamesJson);
        List<String> names = new ArrayList<>();
        for (String body : Json.files(ch.testsJson).values()) {
            var m = TEST_NAME.matcher(body);
            while (m.find()) names.add(m.group(2));
        }
        return names;
    }

    /**
     * If the program stopped before every hidden test ran, adds the missing ones as failed, using the names from the
     * reference run, so a crash can't look like a smaller, passing test suite.
     */
    public static RunResult withAllTests(Challenge ch, RunResult r) {
        if (!r.compiled()) return hideTestDiagnostics(ch, r);
        List<String> names = testNames(ch);
        if (r.tests().size() >= names.size()) return r;
        Set<String> seen = new HashSet<>();
        r.tests().forEach(t -> seen.add(t.name()));
        List<RunResult.TestResult> all = new ArrayList<>(r.tests());
        for (String n : names)
            if (all.size() < names.size() && !seen.contains(n))
                all.add(new RunResult.TestResult(n, false, r.timedOut() ? "Did not finish: time limit exceeded" : "Did not run: the program stopped early"));
        return new RunResult(r.compiled(), r.diagnostics(), r.stdout(), r.stderr(), all, r.timedOut(), r.exitCode(), r.millis());
    }

    /**
     * Compiler errors located in the hidden test files are knock-on effects of a learner mistake; show only the
     * learner's own files when there are any errors there.
     */
    static RunResult hideTestDiagnostics(Challenge ch, RunResult r) {
        Set<String> hidden = Json.files(ch.testsJson).keySet();
        var own = r.diagnostics().stream().filter(d -> !hidden.contains(d.file()) && !d.file().startsWith("check.") && !d.file().startsWith("Check.")).toList();
        if (own.isEmpty() || own.size() == r.diagnostics().size()) return r;
        return new RunResult(r.compiled(), own, r.stdout(), r.stderr(), r.tests(), r.timedOut(), r.exitCode(), r.millis());
    }

    /** Computes (once) what the reference solution's Main prints, shown to learners as the example output. */
    public String sampleOutput(Challenge ch) {
        if (ch.sampleOutput == null) {
            var ref = Json.files(ch.referenceSolutionJson);
            RunResult r = runner.run(ch.language, ref, Map.of(), CodeRunner.Mode.MAIN);
            ch.sampleOutput = r.compiled() && r.exitCode() == 0 ? r.stdout() : "";
            challenges.save(ch);
        }
        return ch.sampleOutput;
    }

    private static List<String> concat(Map<String, String> a, Map<String, String> b) {
        List<String> l = new ArrayList<>(a.keySet()); l.addAll(b.keySet()); return l;
    }

    private Challenge parse(String raw, String language, String concept, int difficulty, String scaffolding) throws Exception {
        JsonNode n = Json.M.readTree(raw);
        Challenge ch = new Challenge();
        ch.language = language; ch.concept = concept; ch.difficulty = difficulty; ch.scaffolding = scaffolding;
        ch.title = n.path("title").asText();
        ch.descriptionMd = n.path("descriptionMd").asText();
        ch.expectedMinutes = Math.max(3, Math.min(45, n.path("expectedMinutes").asInt(10)));
        ch.starterFilesJson = Json.write(Json.M.convertValue(n.path("starterFiles"), Map.class));
        ch.testsJson = Json.write(Json.M.convertValue(n.path("tests"), Map.class));
        ch.referenceSolutionJson = Json.write(Json.M.convertValue(n.path("referenceSolution"), Map.class));
        ch.hintsJson = Json.write(Json.M.convertValue(n.path("hints"), List.class));
        if (ch.title.isBlank() || ch.descriptionMd.isBlank()) throw new IllegalStateException("missing title/description");
        return ch;
    }

    private static String system(Lang lang) {
        return "You are an expert " + lang.label + " instructor who writes auto-graded exercises. You reply with a single JSON object and nothing else.";
    }

    /** Language-specific rules: runtime, file layout, how tests are written and which check helpers exist. */
    private static String langRules(Lang lang) {
        return switch (lang) {
            case JAVA -> """
                    - Java 21, default package, no external libraries, no stdin, deterministic output (no randomness, no time).
                    - File names match [A-Za-z0-9_]+.java. One top-level public class per file, named like the file.
                    - tests has a single file TestMain.java with `public class TestMain { public static void main(String[] args) { ... } }`.
                    - Each test is `Check.run("descriptive name", () -> { ... });`. At least 4 tests. A provided class Check offers:
                      Check.eq(Object expected, Object actual), Check.eq(double expected, double actual, double eps),
                      Check.isTrue(boolean cond, String message), Check.throwsEx(SomeException.class, () -> { ... }).
                      These are the ONLY Check methods that exist: do not call any other (no Check.capture, Check.fail, Check.notNull...). To capture System.out, use plain Java (ByteArrayOutputStream + System.setOut).
                      Do NOT define Check. Tests may use reflection to verify design (e.g. a field is private).
                    - The main file is Main.java with `public class Main` and `public static void main(String[] args)`.""";
            case PYTHON -> """
                    - Python 3.12, standard library only, no input(), deterministic output (no randomness, no time). Use type hints.
                    - File names match [a-z0-9_]+.py (snake_case modules), all in one folder; import with `from shapes import Shape`.
                    - tests has a single file test_main.py. It starts with `import check` and imports the learner's modules.
                    - Each test is `check.run("descriptive name", fn)` with a double-quoted name, where fn is a lambda or a small `def` defined just above it.
                      At least 4 tests. The provided module check offers ONLY: check.eq(expected, actual), check.approx(expected, actual, eps=1e-9),
                      check.is_true(cond, message), check.raises(ExceptionType, fn). Do NOT define check.py. To capture print output use io.StringIO with contextlib.redirect_stdout.
                    - The main file is main.py with a `main()` function and `if __name__ == "__main__": main()`.""";
            case JAVASCRIPT -> """
                    - Node.js 18, CommonJS modules, no npm packages, no stdin, deterministic output (no randomness, no time). Use ES classes; # private fields are allowed.
                    - File names match [a-z0-9_]+.js, all in one folder. Each module ends with `module.exports = { ClassName };` and is loaded with `const { ClassName } = require("./file_name");`.
                    - tests has a single file test_main.js. It starts with `const check = require("./check");` and requires the learner's modules.
                    - Each test is `check.run("descriptive name", () => { ... });` and is synchronous (no async, no promises). At least 4 tests.
                      The provided module check offers ONLY: check.eq(expected, actual) (deep equality for arrays and plain objects),
                      check.approx(expected, actual, eps), check.isTrue(cond, message), check.throws(fn, ErrorType). Do NOT define check.js.
                    - The main file is main.js; it requires the classes, creates objects and prints with console.log.""";
            case CPP -> """
                    - C++20, standard library only, no stdin, deterministic output (no randomness, no time).
                    - Put each class in its own header [a-z0-9_]+.hpp with `#pragma once` and the implementation inline in the header. Do not use .cpp files except main.cpp.
                    - tests has a single file test_main.cpp with `#include "check.hpp"`, the learner headers, and `int main() { ... }`.
                    - Each test is `check::run("descriptive name", [] { ... });`. At least 4 tests. The provided header check.hpp offers ONLY:
                      check::eq(expected, actual) (needs operator==), check::approx(expected, actual, eps), check::is_true(cond, message),
                      check::throws<ExceptionType>([] { ... }). Do NOT define check.hpp. Compare std::string with std::string (wrap literals: std::string("x")).
                    - The main file is main.cpp with `int main()`; it includes the headers, creates objects and prints with std::cout.""";
        };
    }

    /**
     * What a good exercise looks like for each track. OOP exercises build a feature; SOLID exercises are design work:
     * fix or finish a design so it follows the principle, with tests that check the design as well as the behaviour.
     */
    static String trackRules(ConceptCatalog.Concept c, Lang lang) {
        if (!"solid".equals(c.track())) return "";
        String design = switch (c.id()) {
            case "single-responsibility" -> "A class that mixes several jobs (e.g. calculating, formatting and saving a report). The learner splits it so each class has one reason to change. Tests check each new class does its one job, and that the original entry point still produces the same result by delegating.";
            case "open-closed" -> "Code that picks behaviour with if/switch on a type code (e.g. discount or shipping rules). The learner introduces an abstraction so new variants are added as new classes without editing the calculator. Tests check every existing variant still works AND that a brand-new variant written only in the test file plugs in without changing learner code.";
            case "liskov-substitution" -> "A subtype that breaks its base type's promises (e.g. a Square that changes both sides, a read-only collection that throws on add, a bird that can't fly). The learner fixes the hierarchy so every subtype can stand in for its base. Tests run the SAME checks against every subtype through the base type and must pass for all of them.";
            case "interface-segregation" -> "One fat interface that forces classes to implement methods they can't support (e.g. a multifunction device interface on a basic printer). The learner splits it into small role interfaces. Tests check each class implements only the roles it really supports and that client code depending on one role works with any class that has it.";
            case "dependency-inversion" -> "A high-level class that creates its low-level dependency itself (e.g. new EmailSender() inside a notifier). The learner makes it depend on an abstraction passed in through the constructor. Tests inject a fake implementation written in the test file and check the high-level class uses it.";
            default -> "A design that breaks the principle; the learner fixes it.";
        };
        String checks = switch (lang) {
            case JAVA -> "Design checks in Java may use plain language features and reflection (e.g. `Notifier.class.getConstructor(MessageSender.class)`, `Shape.class.isInterface()`, `x instanceof Payable`).";
            case PYTHON -> "Design checks in Python may use isinstance/issubclass, abc.ABC, inspect.signature, or a fake class defined in the test file.";
            case JAVASCRIPT -> "Design checks in JavaScript may use instanceof, typeof x.method === 'function', or a fake class defined in the test file.";
            case CPP -> "Design checks in C++ must happen at run time (no static_assert): use dynamic_cast, std::is_base_of_v (include <type_traits>) inside check::is_true, or a fake class defined in the test file that derives from the learner's interface.";
        };
        return """
                This is a SOLID principles exercise about "%s": %s
                Make it a design exercise, not just a feature: %s
                The starter must be realistic, small code with the design problem (HEAVY: the refactor is half done with TODOs;
                PARTIAL: the problem code plus empty new types to fill; BLANK: only the problem code). Name every type the tests rely on
                in descriptionMd (interfaces, classes, constructor parameters). %s
                Add a "## Why" section to descriptionMd (2-3 sentences: what goes wrong with the original design and what the principle buys).
                """.formatted(c.title(), c.summary(), design, checks);
    }

    private static String userPrompt(Lang lang, ConceptCatalog.Concept c, int difficulty, String scaffolding, String avoid) {
        String concept = c.title();
        String scaffoldRule = switch (scaffolding) {
            case "HEAVY" -> "HEAVY scaffolding: the starter is almost complete. Most classes are written; the learner fills 2-3 clearly marked TODO gaps.";
            case "PARTIAL" -> "PARTIAL scaffolding: the starter has class skeletons, signatures and TODO comments; the learner writes the bodies and some members.";
            default -> "BLANK scaffolding: the starter contains only a minimal main file (or nothing else). The description is a spec with exact class and method signatures; the learner writes everything.";
        };
        return """
                Create ONE %1$s exercise teaching: %2$s
                Difficulty: %3$d out of 5. %4$s
                Avoid repeating these existing titles: %5$s
                %9$s

                Return JSON with exactly these keys:
                {
                  "title": "short, specific",
                  "descriptionMd": "Markdown. Story, requirements, and EXACT public class/method signatures the tests rely on. Do not paste test code.",
                  "expectedMinutes": 8,
                  "starterFiles": { "%6$s": "..." },
                  "referenceSolution": { "...every learner file, complete and correct..." },
                  "tests": { "%7$s": "..." },
                  "hints": ["nudge as a question", "conceptual hint", "partial code snippet"]
                }

                Rules:
                %8$s
                - Tests may only depend on the public API stated in descriptionMd, and must pass with referenceSolution.
                - The starter must NOT pass the tests as given, and should run without syntax or compile errors when scaffolding is HEAVY or PARTIAL.
                - Starter and referenceSolution must not include the check helper or %7$s.
                - Include %6$s in starterFiles AND referenceSolution. In starterFiles it must be a working entry point
                  (with a TODO telling the learner what to demonstrate); in referenceSolution it creates a few objects and prints clear lines.
                - descriptionMd must be detailed (at least 150 words) and use these Markdown sections:
                  "## Goal" (the story and what the learner builds), "## Requirements" (a bullet per rule, with exact %1$s signatures),
                  "## %6$s" (exactly what the demo should create and print), "## Example" (a fenced block with the exact output the reference %6$s prints),
                  "## Tips" (1-2 gentle reminders, no solution code).
                - The Example output in descriptionMd must be exactly what referenceSolution's %6$s prints.
                - Directly under the Example block add a short "Format:" bullet list that removes all guesswork about printing:
                  one item per printed line pattern, saying exactly what goes on each line, separators (e.g. ": " with one space),
                  number formatting (e.g. 2 decimal places), capitalisation, and that each item is on its own line.
                """.formatted(lang.label, concept, difficulty, scaffoldRule, avoid.isBlank() ? "none" : avoid, lang.mainFile, lang.testFile, langRules(lang), trackRules(c, lang));
    }
}
