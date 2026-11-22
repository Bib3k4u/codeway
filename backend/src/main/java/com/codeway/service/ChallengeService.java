package com.codeway.service;

import com.codeway.ai.AiClient;
import com.codeway.auth.CurrentUser;
import com.codeway.domain.*;
import com.codeway.repo.*;
import com.codeway.runner.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChallengeService {
    private static final int MAX_GENERATION_TRIES = 4;
    /** How long one request waits for a challenge being made before telling the browser to ask again (long-polling). */
    static final long PICK_WAIT_MS = 20_000;
    /** After a generation fails, the same kind is not retried for this long (stops a stampede of retries on a struggling model). */
    private static final long COOLDOWN_MS = 30_000;
    /** At most this many exercises are made at once (each takes AI calls and sandbox runs); the rest wait their turn. */
    private static final int GENERATION_THREADS = 2;
    /** Waiting this long or more in the queue, a learner is served a stored challenge instead of waiting for a fresh one. */
    private static final int BACKLOG_LIMIT = 6;
    /** AI calls one generation can cost (one to write, one to review, per try), used to decide whether to prepare ahead. */
    private static final int PREFETCH_CALLS = 10;

    private final ChallengeRepo challenges;
    private final AttemptRepo attempts;
    private final CodeRunner runner;
    private final AiClient ai;
    private final ConceptCatalog catalog;
    private final LessonService lessons;

    /** Generation runs off the request threads: bounded, de-duplicated by kind of challenge, and it carries on if the browser goes away. */
    private final ThreadPoolExecutor genPool = new ThreadPoolExecutor(GENERATION_THREADS, GENERATION_THREADS, 0L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(60), r -> { Thread t = new Thread(r, "challenge-gen"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<String, CompletableFuture<Challenge>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> failedAt = new ConcurrentHashMap<>();
    private final Set<String> preparing = ConcurrentHashMap.newKeySet();

    /** Thrown while a challenge is still being made: the browser gets "202, ask again" and the same job is joined. */
    public static class Preparing extends RuntimeException {
        public Preparing() { super("A challenge is being prepared", null, false, false); }
    }

    public ChallengeService(ChallengeRepo challenges, AttemptRepo attempts, CodeRunner runner, AiClient ai, ConceptCatalog catalog, LessonService lessons) {
        this.challenges = challenges; this.attempts = attempts; this.runner = runner; this.ai = ai; this.catalog = catalog; this.lessons = lessons;
    }

    /**
     * Challenges the AI made under older rules (before lessons and challenges were kept in step) are not served any more; they stay in the
     * database. Hand-written seeds and guided pattern challenges are not affected.
     */
    static boolean current(Challenge c) {
        boolean generated = "ai".equals(c.origin) || "ai-translation".equals(c.origin);
        return !generated || c.guideJson != null || (c.contentVersion != null && c.contentVersion >= Teaching.CONTENT_VERSION);
    }

    private List<Challenge> bank(String language, String concept) {
        return challenges.findByLanguageAndConceptAndValidatedTrue(language, concept).stream().filter(ChallengeService::current).toList();
    }

    /** Adds the five principle options and our answer key to a guide that only has the question and explanation. */
    public static String normalizeGuide(String guideJson, String concept) { return GuidedPrompts.normalize(guideJson, concept); }

    /** The guide of a guided challenge, or null. */
    public static JsonNode guide(Challenge ch) {
        try { return ch.guideJson == null ? null : Json.M.readTree(ch.guideJson); } catch (Exception e) { return null; }
    }

    /** Several challenges in one query, keyed by id (instead of one lookup per attempt). */
    public static Map<Long, Challenge> byId(ChallengeRepo repo, Collection<Long> ids) {
        Map<Long, Challenge> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Challenge c : repo.findAllById(new LinkedHashSet<>(ids))) out.put(c.id, c);
        return out;
    }

    public boolean hasContent(String language, String concept) {
        if (!catalog.practiceOpen(language, concept)) return false;
        return ai.isConfigured() || !bank(language, concept).isEmpty();
    }

    /** Pipeline: bank (unseen, exact match) -> generate+validate -> nearest unseen -> nearest seen. */
    public Challenge pick(Long userId, String language, String concept, int difficulty, String scaffolding) {
        var bank = bank(language, concept);
        Map<Long, Long> lastSeen = new HashMap<>();
        for (Attempt a : attempts.findByUserId(userId)) {
            long t = a.startedAt.toEpochMilli();
            lastSeen.merge(a.challengeId, t, Math::max);
        }
        List<Challenge> unseen = bank.stream().filter(c -> !lastSeen.containsKey(c.id)).toList();

        boolean guided = catalog.isGuided(concept);
        // Guided (pattern) challenges grow from a hand-written anchor scenario: the learner's first one is always the anchor
        // itself in their language, then come fresh AI variants of it (new domain, new twist), each validated like the anchor and shared.
        if (guided) {
            var root = anchor(concept);
            if (root.isPresent()) {
                Challenge mine = "java".equals(language) ? root.get()
                        : bank.stream().filter(c -> family(c) == root.get().id).findFirst().orElse(null);
                if (mine == null) mine = inLanguage(root.get(), language);       // translated once, then shared
                if (mine != null && !lastSeen.containsKey(mine.id)) return mine;
            }
        }

        var exact = unseen.stream().filter(c -> c.difficulty == difficulty && c.scaffolding.equals(scaffolding)).findFirst();
        if (exact.isPresent()) return exact.get();

        boolean busy = genPool.getQueue().size() >= BACKLOG_LIMIT && !unseen.isEmpty();   // a long queue: don't make learners wait behind it
        if (ai.isConfigured() && !(guided && bank.isEmpty()) && !busy) {
            Challenge g = made(userId, language, concept, difficulty, scaffolding, PICK_WAIT_MS);
            if (g != null) return g;
        }

        Comparator<Challenge> nearest = Comparator.comparingInt((Challenge c) -> Math.abs(c.difficulty - difficulty))
                .thenComparingInt(c -> c.scaffolding.equals(scaffolding) ? 0 : 1);
        var pickUnseen = unseen.stream().min(nearest);
        if (pickUnseen.isPresent()) return pickUnseen.get();
        return bank.stream()
                .min(Comparator.comparingLong((Challenge c) -> lastSeen.getOrDefault(c.id, 0L)).thenComparing(nearest))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "We couldn't prepare a challenge just now. Please try again in a minute, or pick another concept."));
    }

    // ---- making challenges in the background ----
    private static String key(String language, String concept, int difficulty, String scaffolding) {
        return language + "|" + concept + "|" + difficulty + "|" + scaffolding;
    }

    private boolean recentlyFailed(String key) {
        Long t = failedAt.get(key);
        return t != null && System.currentTimeMillis() - t < COOLDOWN_MS;
    }

    /**
     * Runs {@code job} on the generation pool, once per {@code key} however many learners ask for the same kind of challenge at the
     * same time. {@code waitMs} is how long to wait for it (0 = don't). Throws {@link Preparing} if it is still going; null if it
     * failed (or failed just now, see the cooldown).
     */
    Challenge shared(String key, Long userId, Supplier<Challenge> job, long waitMs) {
        CompletableFuture<Challenge> f = inflight.get(key);
        if (f == null) {
            if (recentlyFailed(key)) return null;
            f = inflight.computeIfAbsent(key, k -> {
                CompletableFuture<Challenge> cf = new CompletableFuture<>();
                try {
                    genPool.execute(() -> {
                        Challenge made = null;
                        try { made = CurrentUser.as(userId, job); }
                        catch (Throwable t) { System.err.println("[challenge] job " + k + " crashed: " + t); }
                        if (made == null) failedAt.put(k, System.currentTimeMillis()); else failedAt.remove(k);
                        cf.complete(made);
                        inflight.remove(k, cf);
                    });
                } catch (RejectedExecutionException e) { cf.complete(null); }   // the queue is full: the caller falls back to the bank
                return cf;
            });
            if (f.isDone()) inflight.remove(key, f);
        }
        if (waitMs <= 0) return null;
        try { return f.get(waitMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { throw new Preparing(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (ExecutionException e) { return null; }
    }

    private Challenge made(Long userId, String language, String concept, int difficulty, String scaffolding, long waitMs) {
        return shared(key(language, concept, difficulty, scaffolding), userId,
                () -> generate(language, concept, difficulty, scaffolding, bank(language, concept)), waitMs);
    }

    /**
     * Gets the next challenge ready while the learner works on this one, so "Next" is instant instead of a minute of waiting.
     * Does nothing when one is already stored for them, when the same kind is already being made, when the AI allowance is running
     * low or the queue is long. Never throws: it is an optimisation, not a promise. Design-pattern (guided) concepts are skipped:
     * their next exercise depends on the anchor scenario the learner has not finished yet.
     */
    public void prefetch(Long userId, String language, String concept, int difficulty, String scaffolding) {
        try {
            if (ai == null || attempts == null || !ai.isConfigured() || catalog.isGuided(concept) || !catalog.practiceOpen(language, concept)) return;
            String k = key(language, concept, difficulty, scaffolding);
            if (inflight.containsKey(k) || recentlyFailed(k) || genPool.getQueue().size() >= 3) return;
            if (!ai.hasHeadroom(userId, PREFETCH_CALLS)) return;
            if (!preparing.add(userId + "|" + k)) return;
            try {
                Set<Long> seen = new HashSet<>();
                for (Attempt a : attempts.findByUserId(userId)) seen.add(a.challengeId);
                boolean stored = bank(language, concept).stream().anyMatch(c -> !seen.contains(c.id) && c.difficulty == difficulty && c.scaffolding.equals(scaffolding));
                if (!stored) made(userId, language, concept, difficulty, scaffolding, 0);
            } finally { preparing.remove(userId + "|" + k); }
        } catch (Exception e) {
            System.err.println("[challenge] prefetch skipped: " + e.getMessage());
        }
    }

    /** The hand-written Java scenario a guided concept's variants are modelled on. */
    Optional<Challenge> anchor(String concept) {
        return challenges.findByLanguageAndConceptAndValidatedTrue("java", concept).stream().filter(c -> "seed-pattern".equals(c.origin)).findFirst();
    }

    // ---- generation ----
    Challenge generate(String language, String concept, int difficulty, String scaffolding, List<Challenge> bank) {
        var c = catalog.find(language, concept).orElseThrow();
        String avoid = bank.stream().map(x -> x.title).collect(Collectors.joining("; "));
        Optional<Challenge> anchor = catalog.isGuided(concept) ? anchor(concept) : Optional.empty();
        if (catalog.isGuided(concept) && anchor.isEmpty()) return null;
        // The learner reads this lesson before practising, so the exercise is written from it, not around it.
        String lesson = anchor.isPresent() ? null : lessonFor(language, concept);
        String feedback = "";
        Challenge reviewed = null;       // an exercise that runs and checks out but that the reviewer still had remarks on: kept as a last resort
        for (int attempt = 1; attempt <= MAX_GENERATION_TRIES; attempt++) {
            try {
                Lang lang = Lang.of(language);
                String prompt = anchor.isPresent() ? GuidedPrompts.variant(lang, c, difficulty, scaffolding, avoid, anchor.get(), langRules(lang))
                        : userPrompt(lang, c, difficulty, scaffolding, avoid, teachingRules(c, lesson), catalog.mainProvided(concept));
                String raw = ai.chat(ai.model(), system(lang), prompt + feedback, true);
                Challenge ch = parse(raw, language, concept, difficulty, scaffolding);
                if (anchor.isEmpty() && catalog.mainProvided(concept)) ch.mainLocked = true;
                if (anchor.isPresent()) {
                    if (ch.guideJson == null) throw new IllegalStateException("missing guide");
                    ch.guideJson = GuidedPrompts.normalize(ch.guideJson, concept);   // our quiz options and answer key
                    ch.familyId = null;
                }
                String problem = validate(ch, lesson);
                if (problem == null && anchor.isEmpty()) problem = quality(ch);
                if (problem == null && anchor.isEmpty()) {
                    problem = review(ch, c, lesson);
                    if (problem != null) reviewed = ch;
                }
                if (problem == null) {
                    ch.validated = true;
                    ch.origin = "ai";
                    ch.contentVersion = Teaching.CONTENT_VERSION;
                    return challenges.save(ch);
                }
                System.err.println("[challenge] generation " + attempt + " rejected: " + problem);
                feedback = "\n\nYour previous attempt was rejected: " + (problem.length() > 1500 ? problem.substring(0, 1500) : problem)
                        + "\nFix exactly that and return the complete JSON again.";
            } catch (AiClient.QuotaExceeded q) {
                return null;                                     // today's AI allowance is used up: the caller falls back to the bank
            } catch (Exception e) {
                System.err.println("[challenge] generation " + attempt + " failed: " + e.getMessage());
            }
        }
        if (reviewed != null) {
            // Every try had remarks, but at least one was a working, tested exercise. A learner is better served by it than by an error;
            // the remarks are in the log for tuning the prompt.
            System.err.println("[challenge] kept a working exercise despite reviewer remarks: " + reviewed.title);
            reviewed.validated = true;
            reviewed.origin = "ai";
            reviewed.contentVersion = Teaching.CONTENT_VERSION;
            return challenges.save(reviewed);
        }
        return null;
    }

    private static final Pattern STATEMENT = Pattern.compile("(?i)(print|println|printf|console\\.log|cout|console\\.write|string\\.format|return\\s|throw\\s|raise\\s)");

    /**
     * What learners reported and the model keeps doing: giving away the answer in the brief, and a "guided" starter that is only TODO
     * comments although the brief says the headers are written. Cheap checks on a working exercise; null when it is fine.
     */
    String quality(Challenge ch) {
        Lang lang = Lang.of(ch.language);
        Map<String, String> starter = Json.files(ch.starterFilesJson);
        Map<String, String> ref = Json.files(ch.referenceSolutionJson);

        if ("HEAVY".equals(ch.scaffolding)) {
            for (var e : starter.entrySet()) {
                if (e.getKey().equals(lang.mainFile)) continue;
                String code = stripComments(e.getValue(), lang).strip();
                boolean declares = lang == Lang.PYTHON ? Pattern.compile("(?m)^\\s*(class|def)\\s").matcher(code).find()
                        : Pattern.compile("\\b(class|interface|struct|enum|record)\\b").matcher(code).find();
                if (!declares || !code.contains("("))
                    return "HEAVY scaffolding: " + e.getKey() + " is only comments or an empty shell. Write out its class declaration, fields and every method header, with TODO comments inside the bodies";
            }
        }

        String reads = SOURCE_READ.matcher(String.join("\n", Json.files(ch.testsJson).values())).find() ? "x" : null;
        if (reads != null) return "the tests read source files as text. Check behaviour instead (run the code, use a fake, add a new subclass in the test): a text search fails on comments and on words that merely contain `if` or `switch`";
        String stale = staleTodo(ch, lang, starter);
        if (stale != null) return stale;

        Set<String> given = new HashSet<>();                       // lines the learner is handed anyway
        starter.values().forEach(t -> t.lines().forEach(l -> given.add(squash(l))));
        String brief = squash(ch.descriptionMd);
        for (var e : ref.entrySet()) {
            if (e.getKey().equals(lang.testFile)) continue;
            if (Boolean.TRUE.equals(ch.mainLocked) && e.getKey().equals(lang.mainFile)) continue;   // provided and shown, nothing to give away
            for (String line : e.getValue().split("\\R")) {
                String l = squash(line);
                if (l.length() < 24 || !l.contains("(") || !STATEMENT.matcher(l).find() || given.contains(l)) continue;
                if (brief.contains(l)) return "descriptionMd gives away a line of the solution: `" + l + "`. Describe what it must do and print in words instead; show only signatures and the expected output";
            }
        }
        return null;
    }

    private static final Pattern SOURCE_READ = Pattern.compile("(?i)(Files\\.(readAll|readString|lines|newBufferedReader)|Paths?\\.(get|of)\\(|new File\\(|FileReader|\\breadAllBytes|\\breadString|fs\\.(readFile|existsSync|readdir)|require\\(['\"]fs['\"]\\)|open\\(['\"][^'\"]*\\.(py|js)|ifstream|fopen\\(|File\\.(ReadAll|Open)|StreamReader)");
    private static final Pattern REMOVES = Pattern.compile("(?i)\\b(remove|delete|get rid of|eliminate|replace)\\b.*\\b(switch|if[/ -]?else|if[ -]chain|if statements?|type[ -]?codes?|instanceof|else[ -]if|conditionals?)\\b");
    private static final Pattern BRANCHING = Pattern.compile("\\b(switch|if|else|instanceof)\\b");

    /**
     * A TODO that tells the learner to remove a switch or if-else chain that the starter does not contain (the model writes the
     * story of an old design into comments even when the starter is already the new one). Null when every such TODO has something to remove.
     */
    private static String staleTodo(Challenge ch, Lang lang, Map<String, String> starter) {
        String code = starter.entrySet().stream().filter(e -> !e.getKey().equals(lang.testFile)).map(e -> stripComments(e.getValue(), lang)).collect(Collectors.joining("\n"));
        if (BRANCHING.matcher(code).find()) return null;           // there is branching in the starter, so "remove it" can be meant
        for (var e : starter.entrySet()) {
            for (String line : e.getValue().split("\\R")) {
                String l = line.strip();
                boolean comment = l.startsWith("//") || l.startsWith("#") || l.startsWith("*") || l.startsWith("/*");   // a TODO often runs on to the next comment line
                if (comment && REMOVES.matcher(l).find())
                    return "a comment in " + e.getKey() + " says to remove a switch / if-else / type code, but the starter has none: `" + l + "`. A TODO says only what to write at that spot, never what an older design did";
            }
        }
        return null;
    }

    private static String squash(String s) { return s == null ? "" : s.strip().replaceAll("\\s+", " "); }

    private static String stripComments(String code, Lang lang) {
        String s = code.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll(lang == Lang.PYTHON ? "(?m)#.*$" : "(?m)//.*$", "");
    }

    /** The lesson text the learner reads for this concept (null if it can't be had). */
    private String lessonFor(String language, String concept) {
        try { return lessons.get(language, concept).contentMd; } catch (Exception e) { return null; }
    }

    /**
     * What the learner knows and what the exercise must and must not rely on. Hand-written to stop the failures learners reported:
     * exercises that need ideas taught later or never, ask to create what is already given, or leave requirements to guesswork.
     */
    private String teachingRules(ConceptCatalog.Concept c, String lesson) {
        String before = catalog.requires(c.id()).stream().map(ConceptCatalog.Concept::title).collect(Collectors.joining(", "));
        int here = catalog.position(c.id());
        String later = catalog.list("java").stream().filter(x -> x.track().equals(c.track()) && catalog.position(x.id()) > here)
                .map(ConceptCatalog.Concept::title).collect(Collectors.joining(", "));
        Teaching.Note note = Teaching.note(c.id());
        StringBuilder sb = new StringBuilder("""
                Teaching rules. They matter more than anything else, because the learner cannot leave this page to look things up:
                1. The learner has read ONLY the lesson below%s. Use only what it teaches (plus basic syntax: variables, methods, printing).%s%s
                2. Do not depend on library calls or language features the lesson does not show: reflection, getClass(), getSimpleName(), instanceof,
                   streams, lambdas, generics tricks and the like. If the printed output needs something like a type name, make the learner write it
                   (for example each class returns its own name from a method) instead of discovering it with a library call.
                3. If you really need a small standard-library helper the lesson does not cover (for example String.format, StringBuilder, List.of,
                   Math.round, a for-each loop, throwing an exception), add a section "## New in this challenge" to descriptionMd that names it, says in
                   one sentence what it does, and shows one tiny correct example. Never describe anything you are not certain about. If nothing is new, leave the section out.
                4. descriptionMd starts with "## Before you start": a short line naming the concepts needed (%s), or "basic syntax only".
                5. Say plainly what is provided and what the learner writes. Never tell the learner to "create" a class, method or file that already exists
                   in starterFiles: say it is provided. Give a "## Provided for you" list (every file or class already in the starter, with what it does) and put each
                   thing the learner must write in "## Requirements" as a numbered task that matches a TODO in the starter, in the same order.
                6. Be exact about the demo in the main file: concrete steps such as "create a Dog and a Cat and put them in variables of type Animal", never
                   a vague "create some Animal objects". Do not ask the learner to create objects of a type that is not meant to be created directly.
                7. Everything the tests check is stated in descriptionMd: exact output text, formats, method names, parameters and what is thrown. Nothing is left to guess.
                8. The learner sees the NAME of every test (never its code), so each name says in plain words what is checked, e.g. "A Dog greets with Woof!",
                   never "Test 1". A name must not reveal more than the description already says.
                9. Keep the test file simple and short: each test is a few lines and uses only classes and methods the description names. Put the plain, everyday
                   cases first and the trickier ones (edge cases that follow from the description) last: the learner sees the first half and the rest run on submit.
                10. Never give the answer away. descriptionMd, its Tips and its examples say WHAT each method must do and print, in words, with the exact expected output.
                   They never contain the statement or expression that produces it: no println/print/return line from the reference solution, no ready-made formula or
                   string concatenation. Signatures, field names and types may be given. A Tips item is a reminder of an idea, never a line of code.
                11. descriptionMd is plain Markdown. Name a file in backticks, like `Main.java`. Use "##" only at the start of a line for the sections listed below, never inside a sentence.
                12. A TODO comment in the starter says what to write at that exact spot, in the words of the matching numbered Requirement. It never refers to code that is not
                   in the starter (never "remove the switch / if-else / type code" unless that code is really there), never says "refactor" when the refactor is already done,
                   and is never placed on a line that already does what it asks (no "TODO: store the value" above the line that stores it). Every TODO is a gap the learner fills.
                13. Tests check behaviour by running the learner's code. They never open, read or search the source files (no Files.readString, no contains("if") on source
                   text): such a check fails on comments and on ordinary words ("notify", "specific") and punishes correct code. To show that a design is open to extension,
                   write a new class in the test file and check the original class works with it unchanged, or pass in a fake and check it was used.
                """.formatted(lesson == null ? "" : " (given below)", note != null && note.avoid() != null ? " Do not use these, they come later: " + note.avoid() + "." : "",
                later.isBlank() ? "" : " Not yet taught: " + later + ".", before.isBlank() ? "basic syntax only" : before));
        if (lesson != null) sb.append("\n--- LESSON THE LEARNER HAS JUST READ ---\n").append(lesson.length() > 7000 ? lesson.substring(0, 7000) : lesson).append("\n--- END OF LESSON ---\n");
        return sb.toString();
    }

    /**
     * A second look by the model at a finished, working exercise, answering the questions learners actually raised. Costs one AI call per
     * exercise that is generated (exercises are shared). Null when it passes (or the check could not be made), otherwise what to fix.
     */
    private String review(Challenge ch, ConceptCatalog.Concept c, String lesson) {
        try {
            String prompt = """
                    You check a programming exercise for a beginner before it is shown. Be strict and literal.
                    Concept practised: %s. The learner has read only this lesson (plus basic syntax):
                    --- LESSON ---
                    %s
                    --- END ---
                    EXERCISE DESCRIPTION (what the learner reads):
                    %s

                    STARTER FILES (what the learner starts with): %s
                    TEST NAMES (the learner sees only these): %s
                    REFERENCE SOLUTION (not shown to the learner): %s

                    Answer these. Any "yes" is a problem.
                    1. Does the exercise need an idea, keyword, class or library call that neither the lesson nor the description's "New in this challenge" section explains?
                    2. Does the description tell the learner to create something that already exists in the starter files?
                    3. Is any rule the tests check (names, signatures, output text, format, exceptions) missing or vague in the description?
                    4. Does the description contradict the starter files, the example output or itself?
                    5. Is any statement in the description untrue for the language, or any explanation in "New in this challenge" wrong?
                    6. Does a TODO comment in the starter files refer to code that is not there (for example "remove the switch" when there is none), say "refactor" when the refactor is already done, or sit right above a line that already does what it asks?
                    Return JSON: {"problems": ["one short sentence per problem, saying exactly what to change"]}. Use an empty list if there are none.
                    """.formatted(c.title(), lesson == null ? "(not available)" : (lesson.length() > 5000 ? lesson.substring(0, 5000) : lesson), ch.descriptionMd,
                    cut(Json.files(ch.starterFilesJson).toString(), 5000), Json.strings(ch.testNamesJson), cut(Json.files(ch.referenceSolutionJson).toString(), 5000));
            JsonNode n = Json.M.readTree(ai.chat(ai.model(), "You are a meticulous reviewer of beginner programming exercises. You reply with a single JSON object.", prompt, true));
            List<String> problems = new ArrayList<>();
            n.path("problems").forEach(p -> { if (!p.asText().isBlank()) problems.add(p.asText()); });
            return problems.isEmpty() ? null : "The reviewer found: " + String.join(" | ", problems);
        } catch (Exception e) {
            System.err.println("[challenge] review skipped: " + e.getMessage());
            return null;
        }
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) + "..." : s; }

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
        return shared("tr|" + fam + "|" + language, CurrentUser.get(), () -> translate(src, language, fam), PICK_WAIT_MS);
    }

    private Challenge translate(Challenge src, String language, long fam) {
        Lang lang = Lang.of(language);
        String feedback = "";
        int tries = src.guideJson != null ? 3 : 2;               // guided exercises are shared by everyone, so it is worth a third go
        for (int attempt = 1; attempt <= tries; attempt++) {
            try {
                String raw = ai.chat(ai.model(), system(lang), translatePrompt(src, lang) + feedback, true);
                Challenge ch = parse(raw, language, src.concept, src.difficulty, src.scaffolding);
                ch.expectedMinutes = src.expectedMinutes;
                ch.mainLocked = src.mainLocked;
                ch.guideJson = withSteps(src.guideJson, raw);   // quiz and reveal are language-neutral; the steps are re-worded for this language's names
                String problem = validate(ch);
                if (problem == null) {
                    ch.validated = true; ch.origin = "ai-translation"; ch.familyId = fam; ch.contentVersion = Teaching.CONTENT_VERSION;
                    return challenges.save(ch);
                }
                System.err.println("[challenge] translation " + attempt + " rejected: " + problem);
                if (problem.contains("is not installed on this machine")) return null;   // no toolchain here: asking the AI again cannot help
                feedback = "\n\nYour previous translation was rejected when it was compiled and run: " + (problem.length() > 1200 ? problem.substring(0, 1200) : problem)
                        + "\nFix exactly that and return the complete JSON again.";
            } catch (Exception e) {
                System.err.println("[challenge] translation " + attempt + " failed: " + e.getMessage());
                feedback = "\n\nYour previous answer could not be read as the required JSON (" + String.valueOf(e.getMessage()).split("\n")[0].substring(0, Math.min(160, String.valueOf(e.getMessage()).split("\n")[0].length()))
                        + "). Every files value (starterFiles, referenceSolution, tests) must be an object mapping file name to file text.";
            }
        }
        return null;
    }

    /** The guide with the translator's steps swapped in, when it returned the same number of well-formed ones; else unchanged. */
    static String withSteps(String guideJson, String raw) {
        try {
            JsonNode theirs = Json.M.readTree(raw).path("steps");
            com.fasterxml.jackson.databind.node.ObjectNode g = (com.fasterxml.jackson.databind.node.ObjectNode) Json.M.readTree(guideJson);
            if (!theirs.isArray() || theirs.size() != g.path("steps").size()) return guideJson;
            for (JsonNode st : theirs) if (st.path("title").asText("").isBlank() || st.path("md").asText("").isBlank()) return guideJson;
            g.set("steps", theirs);
            return Json.M.writeValueAsString(g);
        } catch (Exception e) {
            return guideJson;
        }
    }

    private static String translatePrompt(Challenge src, Lang lang) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("title", src.title);
        source.put("descriptionMd", src.descriptionMd);
        source.put("starterFiles", Json.files(src.starterFilesJson));
        source.put("referenceSolution", Json.files(src.referenceSolutionJson));
        source.put("tests", Json.files(src.testsJson));
        source.put("hints", Json.strings(src.hintsJson));
        try { if (src.guideJson != null) source.put("steps", Json.M.readValue(src.guideJson, Map.class).get("steps")); } catch (Exception ignored) { }
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
                """.formatted(Lang.of(src.language).label, lang.label, Json.write(source), langRules(lang), lang.mainFile, lang.testFile)
                + (!Boolean.TRUE.equals(src.mainLocked) ? "" : "\n- The main file " + lang.mainFile + " is provided to the learner and read-only: write it completely in starterFiles, with exactly the same text in referenceSolution, "
                    + "and keep the same stubs (exact signature, dummy body) in starterFiles as the source has.\n")
                + (src.guideJson == null ? "" : """
                - Where the source uses a lambda or anonymous class for a single-method interface, write a small named class (or, in JavaScript, an object)
                  that has that method, in the starter's stubs, the reference and the tests alike, so every call site is written `thing.method(args)` and never
                  calls a bare function. Interfaces become abstract base classes (Python: abc or a plain base class; C++: a class with a virtual method and a
                  virtual destructor; hold implementations by std::shared_ptr or std::unique_ptr; JavaScript: a base class that throws "not implemented"; C#: an `interface` named
                  with a leading I, for example IShippingRule).
                - Also return the key "steps": the same steps, same count and order, with every class, method and file name, signature and example
                  rewritten for the target language exactly as your starterFiles and tests use them (so a learner can follow the step text literally).
                - Keep EVERY test name exactly as in the source, including its "Guard:" or "S1:", "S2:"... prefix and order. The starter is
                  WORKING code: it must pass all "Guard:" tests, and keep the new empty types the step tests refer to (as stubs with TODOs).
                """);
    }

    /** Returns null when the challenge is sound, otherwise the reason it was rejected. */
    public String validate(Challenge ch) { return validate(ch, null); }

    /** As {@link #validate(Challenge)}; with the lesson the learner reads, it also rejects an exercise that needs something the lesson does not teach. */
    public String validate(Challenge ch, String lessonMd) {
        Map<String, String> tests = Json.files(ch.testsJson);
        Map<String, String> starter = Json.files(ch.starterFilesJson);
        Map<String, String> ref = Json.files(ch.referenceSolutionJson);
        Lang lang = Lang.of(ch.language);
        if (!tests.containsKey(lang.testFile)) return "tests must include " + lang.testFile;
        if (!ref.containsKey(lang.mainFile)) return "referenceSolution must include " + lang.mainFile;
        if (Boolean.TRUE.equals(ch.mainLocked)) {
            String sm = starter.get(lang.mainFile);
            if (sm == null || sm.isBlank()) return "the provided " + lang.mainFile + " must be written out in starterFiles";
            if (!sm.strip().equals(ref.get(lang.mainFile).strip()))
                return lang.mainFile + " must be identical in starterFiles and referenceSolution: it is provided to the learner and read-only";
        }
        for (String f : concat(starter, ref)) {
            if (!lang.fileName.matcher(f).matches()) return "bad file name " + f;
            if (f.equals(lang.testFile) || f.toLowerCase(Locale.ROOT).startsWith("check.")) return "learner files must not include " + f;
        }
        RunResult good = runner.run(lang.id, ref, tests, CodeRunner.Mode.TESTS);
        if (!good.compiled()) return "reference does not compile: " + good.diagnostics();
        if (good.total() < 3) return "needs at least 3 tests, got " + good.total() + " | stderr: " + good.stderr() + " | stdout: " + good.stdout();
        if (!good.allPassed()) {
            // the program's own error first (a crash at start-up explains every failed test), then which tests failed
            String err = good.stderr() == null ? "" : good.stderr().strip();
            return (err.isEmpty() ? "" : "the program printed this error: " + cut(err, 700) + " | ")
                    + "reference fails: " + good.tests().stream().filter(t -> !t.passed()).map(t -> t.name() + " (" + t.message() + ")").limit(6).toList();
        }
        // learners see every test by name, so a name has to say what is checked
        var vague = good.tests().stream().map(RunResult.TestResult::name).filter(n -> n.trim().matches("(?i)(unit )?test[ _-]*#?\\d*|case[ _-]*\\d+|check[ _-]*\\d*")).toList();
        if (!vague.isEmpty()) return "test names must say in plain words what is checked, not " + vague;
        ch.testNamesJson = Json.write(good.tests().stream().map(RunResult.TestResult::name).toList());
        RunResult start = runner.run(lang.id, starterWithMain(lang.id, starter), tests, CodeRunner.Mode.TESTS);
        if (start.allPassed()) return "starter already passes all tests";
        if (ch.guideJson != null) {
            String bad = GuidedPrompts.checkGuide(ch.guideJson, good.tests().stream().map(RunResult.TestResult::name).toList());
            if (bad != null) return bad;
            if (!start.compiled()) return "starter does not compile: " + start.diagnostics();
            // the starter is working code: every Guard test must already pass, and step 1 must still be to do
            var brokenGuards = start.tests().stream().filter(t -> t.name().startsWith("Guard:") && !t.passed()).map(RunResult.TestResult::name).toList();
            if (!brokenGuards.isEmpty()) return "starter breaks guard tests: " + brokenGuards;
            if (start.tests().stream().noneMatch(t -> t.name().startsWith("S1:") && !t.passed())) return "step 1 tests already pass on the starter";
        }
        return Teaching.untaught(ch, catalog, lessonMd);
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
            case CSHARP -> """
                    using System;

                    class Program
                    {
                        static void Main(string[] args)
                        {
                            // TODO: create objects here and print results to show your code working
                        }
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
            case CSHARP -> main.contains("static void Main") || main.contains("static int Main");
            default -> !main.isBlank();
        };
        if (!ok) m.put(lang.mainFile, mainBoilerplate(lang));
        return m;
    }

    private static final Pattern TEST_NAME = Pattern.compile("(?:Check\\.run|Check\\.Run|check\\.run|check::run)\\(\\s*([\"'`])((?:(?!\\1)[^\\\\]|\\\\.)*)\\1");

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
     * How many tests the learner sees (and gets from "Run tests"). The rest are held back and run only on Submit, so passing
     * the visible ones isn't the whole job. Challenges with three tests or fewer, and guided ones (their steps are the tests), show all.
     */
    public static int visibleCount(Challenge ch) {
        int n = testNames(ch).size();
        if (n <= 3 || ChallengeService.guide(ch) != null) return n;
        return Math.max(3, (n + 1) / 2);
    }

    public static List<String> visibleNames(Challenge ch) {
        List<String> all = testNames(ch);
        return all.subList(0, Math.min(visibleCount(ch), all.size()));
    }

    /** A run of the tests as the learner sees it from "Run tests": only the visible ones. */
    public static RunResult onlyVisible(Challenge ch, RunResult r) {
        int k = visibleCount(ch);
        if (!r.compiled() || r.tests().size() <= k) return r;
        return new RunResult(r.compiled(), r.diagnostics(), r.stdout(), r.stderr(), new ArrayList<>(r.tests().subList(0, k)), r.timedOut(), r.exitCode(), r.millis());
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
        if (r.diagnostics().isEmpty() || own.size() == r.diagnostics().size()) return r;
        if (own.isEmpty()) {
            // Everything that failed is in the hidden test file: the learner's code is missing something the tests use. A line number in a
            // file they cannot open means nothing, so say what is missing, without a location.
            var seen = new LinkedHashSet<String>();
            for (var d : r.diagnostics()) {
                if (!"error".equalsIgnoreCase(d.severity())) continue;
                seen.add(d.message().strip());
                if (seen.size() == 4) break;
            }
            var friendly = seen.stream().map(m -> new RunResult.Diagnostic("", 0, "error",
                    "The tests can't build against your code yet: " + m + ". Check the class, method and field names and signatures against the Requirements in the brief.")).toList();
            if (friendly.isEmpty()) return r;
            return new RunResult(r.compiled(), friendly, r.stdout(), r.stderr(), r.tests(), r.timedOut(), r.exitCode(), r.millis());
        }
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
        // "as shown below in ## Main.java": a heading marker in the middle of a sentence would show as raw text
        ch.descriptionMd = n.path("descriptionMd").asText().replaceAll("(?m)(?<=\\S[ \\t])#{2,6}[ \\t]+([A-Za-z0-9_]+\\.[A-Za-z]+)", "`$1`");
        ch.expectedMinutes = Math.max(3, Math.min(45, n.path("expectedMinutes").asInt(10)));
        ch.starterFilesJson = Json.write(Json.M.convertValue(n.path("starterFiles"), Map.class));
        // the single test file is sometimes returned as bare text instead of {"file": "text"}
        JsonNode tests = n.path("tests");
        ch.testsJson = tests.isTextual() ? Json.write(Map.of(Lang.of(language).testFile, tests.asText())) : Json.write(Json.M.convertValue(tests, Map.class));
        ch.referenceSolutionJson = Json.write(Json.M.convertValue(n.path("referenceSolution"), Map.class));
        ch.hintsJson = Json.write(Json.M.convertValue(n.path("hints"), List.class));
        if (n.path("guide").isObject()) ch.guideJson = Json.write(Json.M.convertValue(n.path("guide"), Map.class));
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
                    - The main file is main.py with a `main()` function and `if __name__ == "__main__": main()`.
                    - Modules must never import each other in a circle (it crashes at import time): when classes refer to each other (for example states that
                      return the next state), put them in ONE module, or import inside the function that needs the other class.""";
            case JAVASCRIPT -> """
                    - Node.js 18, CommonJS modules, no npm packages, no stdin, deterministic output (no randomness, no time). Use ES classes; # private fields are allowed.
                    - File names match [a-z0-9_]+.js, all in one folder. Each module ends with `module.exports = { ClassName };` and is loaded with `const { ClassName } = require("./file_name");`.
                    - tests has a single file test_main.js. It starts with `const check = require("./check");` and requires the learner's modules.
                    - Each test is `check.run("descriptive name", () => { ... });` and is synchronous (no async, no promises). At least 4 tests.
                      The provided module check offers ONLY: check.eq(expected, actual) (deep equality for arrays and plain objects),
                      check.approx(expected, actual, eps), check.isTrue(cond, message), check.throws(fn, ErrorType). Do NOT define check.js.
                    - The main file is main.js; it requires the classes, creates objects and prints with console.log.
                    - Modules must never require each other in a circle (the export is still empty): when classes refer to each other, put them in ONE module,
                      or call require inside the function that needs the other class.""";
            case CPP -> """
                    - C++20, standard library only, no stdin, deterministic output (no randomness, no time).
                    - Put each class in its own header [a-z0-9_]+.hpp with `#pragma once` and the implementation inline in the header. Do not use .cpp files except main.cpp.
                    - tests has a single file test_main.cpp with `#include "check.hpp"`, the learner headers, and `int main() { ... }`.
                    - Each test is `check::run("descriptive name", [] { ... });`. At least 4 tests. The provided header check.hpp offers ONLY:
                      check::eq(expected, actual) (needs operator==), check::approx(expected, actual, eps), check::is_true(cond, message),
                      check::throws<ExceptionType>([] { ... }). Do NOT define check.hpp. Compare std::string with std::string (wrap literals: std::string("x")).
                    - The main file is main.cpp with `int main()`; it includes the headers, creates objects and prints with std::cout.
                    - Headers must never include each other in a circle: when classes refer to each other (for example states that return the next state),
                      put them in ONE header, declaring the later classes first (`class B;`) and defining the member functions after all the classes.""";
            case CSHARP -> """
                    - C# 12 on .NET 8, standard library only, no NuGet packages, no stdin, deterministic output (no randomness, no time).
                    - File names match [A-Za-z0-9_]+.cs. One top-level public class per file, named like the file, in the global namespace (no `namespace` line).
                      Start every file with the `using` lines it needs (`using System;`, `using System.Collections.Generic;`, `using System.Linq;`): implicit usings are off.
                    - Nullable reference types are off. Use PascalCase for classes, methods and properties, camelCase for locals; use properties (`public string Name { get; }`) where Java would use getters.
                    - tests has a single file TestMain.cs with `using System;` and `public class TestMain { public static void Main(string[] args) { ... } }`.
                    - Each test is `Check.Run("descriptive name", () => { ... });`. At least 4 tests. A provided static class Check offers ONLY:
                      Check.Eq(object expected, object actual), Check.Eq(double expected, double actual, double eps),
                      Check.IsTrue(bool cond, string message), Check.Throws<SomeException>(() => { ... }). Do NOT define Check. To capture Console output use Console.SetOut with a StringWriter.
                    - The main file is Program.cs with `class Program` and `static void Main(string[] args)`; it creates objects and prints with Console.WriteLine.""";
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
            case CSHARP -> "Design checks in C# may use `is`, typeof(...).IsInterface, typeof(...).GetConstructor(...), or a fake class defined in the test file that implements the learner's interface.";
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

    private static String userPrompt(Lang lang, ConceptCatalog.Concept c, int difficulty, String scaffolding, String avoid, String teaching, boolean mainProvided) {
        String concept = c.title();
        String scaffoldRule = switch (scaffolding) {
            case "HEAVY" -> "HEAVY scaffolding: the starter is almost complete. Every file the learner edits already has its class declaration, its fields and every method header it needs, written out; the learner fills 2-3 clearly marked TODO gaps inside them. Never leave a learner file as only comments.";
            case "PARTIAL" -> "PARTIAL scaffolding: the starter has class skeletons, signatures and TODO comments; the learner writes the bodies and some members. Less is written than for HEAVY: the learner also adds some fields and methods themselves.";
            default -> mainProvided
                    ? "BLANK scaffolding: the starter has only the problem code and an empty stub for each new type. The description is a spec with exact class and method signatures; the learner writes the bodies."
                    : "BLANK scaffolding: the starter contains only a minimal main file (or nothing else). The description is a spec with exact class and method signatures; the learner writes everything.";
        };
        String mainRule = mainProvided
                ? "- %6$s is PROVIDED and read-only: write it completely in starterFiles, a working demo that uses the classes and prints clear lines, and put exactly the same text in\n"
                + "  referenceSolution. The learner never edits it and the tests never use it. Prefer a stub in starterFiles (exact signature, dummy body such as return 0, null or an empty string)\n"
                + "  for every class it uses, so the project compiles before the learner starts. Where the exercise is to CREATE a class or interface, %6$s may use it before it exists, but then\n"
                + "  \"## Requirements\" must give its exact name, constructor and method signatures. All TODOs are in the other files.\n"
                + "  In descriptionMd, say that %6$s is provided and must not be edited. Tests use several different values and, where the principle is about abstractions, a small fake class written in the test file."
                : "- Include %6$s in starterFiles AND referenceSolution. In starterFiles it must be a working entry point\n"
                + "  (with a TODO telling the learner what to demonstrate); in referenceSolution it creates a few objects and prints clear lines.";
        return """
                Create ONE %1$s exercise teaching: %2$s
                Difficulty: %3$d out of 5. %4$s
                Avoid repeating these existing titles: %5$s
                %9$s

                %10$s

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
                %11$s
                - descriptionMd must be detailed (at least 150 words) and use these Markdown sections, in this order:
                  "## Before you start", "## Goal" (the story and what the learner builds), "## Provided for you" (what the starter already contains),
                  "## Requirements" (numbered tasks, one per TODO, with exact %1$s signatures), "## New in this challenge" (only if rule 3 applies),
                  "## %6$s" (exactly what the demo creates and prints, and whether the starter already does it or the learner writes it),
                  "## Example" (a fenced block with the exact output the reference %6$s prints), "## Tips" (1-2 gentle reminders, no solution code).
                - Make each test name a plain-words statement of what is checked (see the teaching rules).
                - The Example output in descriptionMd must be exactly what referenceSolution's %6$s prints.
                - Directly under the Example block add a short "Format:" bullet list that removes all guesswork about printing:
                  one item per printed line pattern, saying exactly what goes on each line, separators (e.g. ": " with one space),
                  number formatting (e.g. 2 decimal places), capitalisation, and that each item is on its own line.
                """.formatted(lang.label, concept, difficulty, scaffoldRule, avoid.isBlank() ? "none" : avoid, lang.mainFile, lang.testFile, langRules(lang), trackRules(c, lang), teaching, mainRule.formatted(lang.mainFile, lang.mainFile, lang.mainFile, lang.mainFile, lang.mainFile, lang.mainFile));
    }
}
