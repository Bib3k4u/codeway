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
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_]+\\.java");

    private final ChallengeRepo challenges;
    private final AttemptRepo attempts;
    private final CodeRunner runner;
    private final AiClient ai;
    private final ConceptCatalog catalog;

    public ChallengeService(ChallengeRepo challenges, AttemptRepo attempts, CodeRunner runner, AiClient ai, ConceptCatalog catalog) {
        this.challenges = challenges; this.attempts = attempts; this.runner = runner; this.ai = ai; this.catalog = catalog;
    }

    public boolean hasContent(String language, String concept) {
        if (!ConceptCatalog.PRACTICE.contains(language)) return false;
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
                String raw = ai.chat(ai.model(), SYSTEM, userPrompt(c.title(), difficulty, scaffolding, avoid), true);
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

    /** Returns null when the challenge is sound, otherwise the reason it was rejected. */
    public String validate(Challenge ch) {
        Map<String, String> tests = Json.files(ch.testsJson);
        Map<String, String> starter = Json.files(ch.starterFilesJson);
        Map<String, String> ref = Json.files(ch.referenceSolutionJson);
        if (!tests.containsKey("TestMain.java")) return "tests must include TestMain.java";
        for (String f : concat(starter, ref)) if (!FILE.matcher(f).matches()) return "bad file name " + f;
        RunResult good = runner.run(ref, tests, CodeRunner.Mode.TESTS);
        if (!good.compiled()) return "reference does not compile: " + good.diagnostics();
        if (good.total() < 3) return "needs at least 3 tests, got " + good.total() + " | stderr: " + good.stderr() + " | stdout: " + good.stdout();
        if (!good.allPassed()) return "reference fails: " + good.tests().stream().filter(t -> !t.passed()).toList() + " " + good.stderr();
        RunResult start = runner.run(starter, tests, CodeRunner.Mode.TESTS);
        if (start.allPassed()) return "starter already passes all tests";
        return null;
    }

    public static final String MAIN_BOILERPLATE = """
            public class Main {
                public static void main(String[] args) {
                    // TODO: create objects here and print results to show your code working
                }
            }
            """;

    /** Starter files as shown to the learner: always includes a Main.java with a main method. */
    public static Map<String, String> starterWithMain(Map<String, String> starter) {
        Map<String, String> m = new LinkedHashMap<>(starter);
        String main = m.get("Main.java");
        if (main == null || !main.contains("static void main")) m.put("Main.java", MAIN_BOILERPLATE);
        return m;
    }

    private static final Pattern TEST_NAME = Pattern.compile("Check\\.run\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static List<String> testNames(Challenge ch) {
        List<String> names = new ArrayList<>();
        for (String body : Json.files(ch.testsJson).values()) {
            var m = TEST_NAME.matcher(body);
            while (m.find()) names.add(m.group(1));
        }
        return names;
    }

    /** Computes (once) what the reference solution's Main prints, shown to learners as the example output. */
    public String sampleOutput(Challenge ch) {
        if (ch.sampleOutput == null) {
            var ref = Json.files(ch.referenceSolutionJson);
            RunResult r = runner.run(ref, Map.of(), CodeRunner.Mode.MAIN);
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

    private static final String SYSTEM = """
            You are an expert Java instructor who writes auto-graded exercises. You reply with a single JSON object and nothing else.""";

    private static String userPrompt(String concept, int difficulty, String scaffolding, String avoid) {
        String scaffoldRule = switch (scaffolding) {
            case "HEAVY" -> "HEAVY scaffolding: the starter is almost complete. Most classes are written; the learner fills 2-3 clearly marked // TODO gaps.";
            case "PARTIAL" -> "PARTIAL scaffolding: the starter has class skeletons, signatures and // TODO comments; the learner writes the bodies and some members.";
            default -> "BLANK scaffolding: the starter contains only a minimal Main.java (or nothing else). The description is a spec with exact class and method signatures; the learner writes everything.";
        };
        return """
                Create ONE Java exercise teaching: %s
                Difficulty: %d out of 5. %s
                Avoid repeating these existing titles: %s

                Return JSON with exactly these keys:
                {
                  "title": "short, specific",
                  "descriptionMd": "Markdown. Story, requirements, and EXACT public class/method signatures the tests rely on. Do not paste test code.",
                  "expectedMinutes": 8,
                  "starterFiles": { "Main.java": "..." },
                  "referenceSolution": { "...every learner file, complete and correct..." },
                  "tests": { "TestMain.java": "..." },
                  "hints": ["nudge as a question", "conceptual hint", "partial code snippet"]
                }

                Rules:
                - Java 21, default package, no external libraries, no stdin, deterministic output (no randomness, no time).
                - File names match [A-Za-z0-9_]+.java. One top-level public class per file, named like the file.
                - tests has a single file TestMain.java with `public class TestMain { public static void main(String[] args) { ... } }`.
                - Each test is `Check.run("descriptive name", () -> { ... });`. At least 4 tests. A provided class Check offers:
                  Check.eq(Object expected, Object actual), Check.eq(double expected, double actual, double eps),
                  Check.isTrue(boolean cond, String message), Check.throwsEx(SomeException.class, () -> { ... }).
                  These are the ONLY Check methods that exist: do not call any other (no Check.capture, Check.fail, Check.notNull...). To capture System.out, use plain Java (ByteArrayOutputStream + System.setOut).
                  Do NOT define Check. Tests may use reflection to verify design (e.g. a field is private).
                - Tests may only depend on the public API stated in descriptionMd, and must pass with referenceSolution.
                - The starter must NOT pass the tests as given, and should compile when scaffolding is HEAVY or PARTIAL.
                - Starter and referenceSolution must not include Check.java or TestMain.java.
                - Include a Main.java in starterFiles AND referenceSolution. In starterFiles it must contain a working `public static void main(String[] args)` skeleton
                  (with a // TODO telling the learner what to demonstrate); in referenceSolution it creates a few objects and prints clear lines.
                - descriptionMd must be detailed (at least 150 words) and use these Markdown sections:
                  "## Goal" (the story and what the learner builds), "## Requirements" (a bullet per rule, with exact signatures such as `public Book(String title, int pages)`),
                  "## Main.java" (exactly what the demo should create and print), "## Example" (a fenced block with the exact output the reference Main prints),
                  "## Tips" (1-2 gentle reminders, no solution code).
                - The Example output in descriptionMd must be exactly what referenceSolution's Main prints.
                """.formatted(concept, difficulty, scaffoldRule, avoid.isBlank() ? "none" : avoid);
    }
}
