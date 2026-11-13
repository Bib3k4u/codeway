package com.codeway.seed;

import com.codeway.domain.Challenge;
import com.codeway.repo.ChallengeRepo;
import com.codeway.runner.Lang;
import com.codeway.service.ChallengeService;
import com.codeway.service.ConceptCatalog;
import com.codeway.service.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * The hand-written anchor scenarios for guided design-pattern practice, read from resources/patterns:
 *
 * <pre>
 * patterns/&lt;id&gt;/scenario.json      concept, title, difficulty, scaffolding, expectedMinutes, hints[], guide{quiz{question,explanation}, steps[], reveal{}}
 * patterns/&lt;id&gt;/description.md     the brief (never names the pattern)
 * patterns/&lt;id&gt;/&lt;lang&gt;/starter/*    working code with the design problem, plus stubs for the new types
 * patterns/&lt;id&gt;/&lt;lang&gt;/reference/*  the finished design
 * patterns/&lt;id&gt;/&lt;lang&gt;/tests/*      the test entry file ("Guard:" and "S1:".. tests)
 * </pre>
 *
 * Each one is validated through the runner like AI output is; an edited scenario is re-validated and updated in place.
 * Fresh variants for learners are generated from these anchors (see ChallengeService / GuidedPrompts).
 */
@Component
public class SeedPatterns implements ApplicationRunner {
    public static final String ORIGIN = "seed-pattern";
    private final ChallengeRepo repo;
    private final ChallengeService service;
    @Value("${codeway.seed.patterns:true}") private boolean enabled;

    public SeedPatterns(ChallengeRepo repo, ChallengeService service) { this.repo = repo; this.service = service; }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        Thread t = new Thread(this::seed, "seed-patterns");
        t.setDaemon(true);
        t.start();
    }

    private void seed() {
        List<Challenge> all;
        try { all = load(null); } catch (Exception e) { System.err.println("[seed-pattern] could not read scenarios: " + e.getMessage()); return; }
        for (Challenge c : all) {
            try {
                var existing = repo.findByTitleAndLanguageAndOrigin(c.title, c.language, ORIGIN);
                if (existing.isPresent() && c.contentHash.equals(existing.get().contentHash) && existing.get().validated) continue;
                existing.ifPresent(e -> c.id = e.id);                       // an edited scenario is updated in place
                String problem = service.validate(c);
                c.validated = problem == null;
                repo.save(c);
                System.out.println("[seed-pattern] " + c.title + " (" + c.language + ")" + (problem == null ? " validated" : " REJECTED: " + problem));
            } catch (Exception e) {
                System.err.println("[seed-pattern] " + c.title + " failed: " + e.getMessage());
            }
        }
    }

    /** Reads every scenario (or just {@code onlyId}) for every language that has files. Not validated. */
    public static List<Challenge> load(String onlyId) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        List<Challenge> out = new ArrayList<>();
        for (Resource sc : resolver.getResources("classpath*:patterns/*/scenario.json")) {
            JsonNode s = Json.M.readTree(sc.getInputStream());
            String id = s.path("id").asText();
            if (onlyId != null && !onlyId.equals(id)) continue;
            String description = text(resolver.getResource("classpath:patterns/" + id + "/description.md"));
            for (String lang : ConceptCatalog.LANGUAGES) {
                String base = "classpath:patterns/" + id + "/" + lang + "/";
                Map<String, String> starter = dir(resolver, base + "starter/*"), reference = dir(resolver, base + "reference/*"), tests = dir(resolver, base + "tests/*");
                if (starter.isEmpty()) continue;
                Challenge c = new Challenge();
                c.language = lang; c.concept = s.path("concept").asText(); c.title = s.path("title").asText();
                c.difficulty = s.path("difficulty").asInt(2); c.scaffolding = s.path("scaffolding").asText("HEAVY");
                c.expectedMinutes = s.path("expectedMinutes").asInt(25); c.descriptionMd = description;
                c.starterFilesJson = Json.write(starter); c.referenceSolutionJson = Json.write(reference); c.testsJson = Json.write(tests);
                c.hintsJson = Json.write(Json.M.convertValue(s.path("hints"), List.class));
                c.guideJson = normalizedGuide(s.path("guide"), c.concept);
                c.origin = ORIGIN;
                c.contentHash = hash(c);
                out.add(c);
            }
        }
        return out;
    }

    private static String normalizedGuide(JsonNode guide, String concept) throws IOException {
        // the scenario file only has the question and explanation; the five principle options and the answer key are ours
        return ChallengeService.normalizeGuide(Json.M.writeValueAsString(guide), concept);
    }

    private static Map<String, String> dir(PathMatchingResourcePatternResolver resolver, String pattern) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try {
            for (Resource r : resolver.getResources(pattern)) {
                String name = r.getFilename();
                if (name != null && name.contains(".") && r.isReadable()) files.put(name, text(r));
            }
        } catch (java.io.FileNotFoundException none) {
            // a language this scenario has no files for (they are translated on demand)
        }
        return files;
    }

    private static String text(Resource r) throws IOException {
        try (var in = r.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String hash(Challenge c) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{c.title, c.descriptionMd, c.starterFilesJson, c.referenceSolutionJson, c.testsJson, c.hintsJson, c.guideJson,
                    String.valueOf(c.difficulty), c.scaffolding, String.valueOf(c.expectedMinutes)})
                md.update((part + "\u0000").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
