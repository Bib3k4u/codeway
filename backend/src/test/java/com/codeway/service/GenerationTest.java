package com.codeway.service;

import com.codeway.domain.Challenge;
import com.codeway.runner.LocalRunner;
import com.codeway.runner.RunResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Making challenges in the background (one job per kind, a wait that ends in "ask again"), and the checks learners' feedback led to. */
class GenerationTest {
    private static ChallengeService service() { return new ChallengeService(null, null, new LocalRunner(), null, new ConceptCatalog(), null); }

    private static Challenge challenge(String scaffolding, Map<String, String> starter, Map<String, String> ref, String description) {
        Challenge c = new Challenge();
        c.language = "java"; c.scaffolding = scaffolding; c.descriptionMd = description;
        c.starterFilesJson = Json.write(starter);
        c.referenceSolutionJson = Json.write(ref);
        return c;
    }

    // ---- background jobs ----
    @Test void askersForTheSameKindShareOneJob() throws Exception {
        var svc = service();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        Challenge made = new Challenge(); made.title = "ready";
        java.util.function.Supplier<Challenge> job = () -> {
            runs.incrementAndGet();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return made;
        };
        var results = new java.util.concurrent.CopyOnWriteArrayList<Challenge>();
        Thread a = new Thread(() -> results.add(svc.shared("k", 1L, job, 4000)));
        Thread b = new Thread(() -> results.add(svc.shared("k", 2L, job, 4000)));
        a.start(); Thread.sleep(150); b.start(); Thread.sleep(150);
        release.countDown();
        a.join(5000); b.join(5000);
        assertEquals(1, runs.get(), "two learners asking for the same kind must not start two generations");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r == made));
    }

    @Test void aSlowJobEndsTheWaitWithPreparingAndKeepsGoing() throws Exception {
        var svc = service();
        CountDownLatch release = new CountDownLatch(1);
        Challenge made = new Challenge();
        java.util.function.Supplier<Challenge> job = () -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} return made; };
        assertThrows(ChallengeService.Preparing.class, () -> svc.shared("slow", 1L, job, 100));
        assertThrows(ChallengeService.Preparing.class, () -> svc.shared("slow", 1L, job, 100), "asking again joins the same job");
        release.countDown();
        assertSame(made, svc.shared("slow", 1L, job, 3000), "the job finished while the browser was away");
    }

    @Test void aFailedKindIsNotRetriedStraightAway() {
        var svc = service();
        AtomicInteger runs = new AtomicInteger();
        assertNull(svc.shared("bad", 1L, () -> { runs.incrementAndGet(); return null; }, 3000));
        assertNull(svc.shared("bad", 1L, () -> { runs.incrementAndGet(); return null; }, 3000));
        assertEquals(1, runs.get(), "the cooldown keeps a struggling model from being hammered");
    }

    @Test void aCrashingJobIsAFailureNotAnException() {
        assertNull(service().shared("boom", 1L, () -> { throw new IllegalStateException("x"); }, 3000));
    }

    // ---- what the next challenge will be ----
    @Test void oneDecentPassStepsTheScaffoldingDown() {
        assertEquals("HEAVY", MasteryService.scaffolding(MasteryService.INITIAL_SCORE));
        double afterClean = MasteryService.scoreAfterCleanPass(MasteryService.INITIAL_SCORE, 1);
        assertEquals("PARTIAL", MasteryService.scaffolding(afterClean), "a clean first pass must make the next challenge less hand-held");
        // one light hint (performance 0.92) still moves on, leaning on the whole solution (0.2) does not
        double expected = 1.0 / (1.0 + Math.exp(-8.0 * (MasteryService.INITIAL_SCORE - 0.1)));
        assertEquals("PARTIAL", MasteryService.scaffolding(MasteryService.nextScoreForTest(MasteryService.INITIAL_SCORE, 0.92, expected, true)));
        assertEquals("HEAVY", MasteryService.scaffolding(MasteryService.nextScoreForTest(MasteryService.INITIAL_SCORE, 0.20, expected, true)));
    }

    // ---- checks on a generated exercise ----
    private static final String SHELL = "public class Car {\n    // TODO: add fields\n    // TODO: add methods\n}\n";
    private static final String FULL = "public class Car {\n    private String name;\n    public Car(String name) { this.name = name; }\n    public String describe() {\n        // TODO\n        return null;\n    }\n}\n";
    private static final String REF = "public class Car {\n    private String name;\n    public Car(String name) { this.name = name; }\n    public String describe() {\n        return \"Car: \" + name.toUpperCase() + \"!\";\n    }\n}\n";

    @Test void aGuidedStarterThatIsOnlyCommentsIsRejected() {
        var c = challenge("HEAVY", Map.of("Car.java", SHELL), Map.of("Car.java", REF), "Write a Car.");
        String problem = service().quality(c);
        assertNotNull(problem);
        assertTrue(problem.contains("Car.java"));
        assertNull(service().quality(challenge("HEAVY", Map.of("Car.java", FULL), Map.of("Car.java", REF), "Write describe().")));
        assertNull(service().quality(challenge("BLANK", Map.of("Car.java", SHELL), Map.of("Car.java", REF), "Write a Car.")), "only guided starters must come written out");
    }

    @Test void aBriefThatPastesTheSolutionLineIsRejected() {
        String leak = "## Tips\n\nUse `return \"Car: \" + name.toUpperCase() + \"!\";` inside describe.";
        String problem = service().quality(challenge("PARTIAL", Map.of("Car.java", FULL), Map.of("Car.java", REF), leak));
        assertNotNull(problem);
        assertTrue(problem.contains("gives away"));
        // describing it in words, and showing a signature or the expected output, is fine
        assertNull(service().quality(challenge("PARTIAL", Map.of("Car.java", FULL), Map.of("Car.java", REF),
                "describe() returns the name in capitals between `Car: ` and `!`.\n\n```\npublic String describe()\n```\nExample output: Car: AUDI!")));
    }

    @Test void aLineTheStarterAlreadyHasIsNotALeak() {
        String starter = "public class Car {\n    public String describe() {\n        return \"Car: \" + name.toUpperCase() + \"!\";\n    }\n}\n";
        assertNull(service().quality(challenge("PARTIAL", Map.of("Car.java", starter), Map.of("Car.java", REF), "It already has `return \"Car: \" + name.toUpperCase() + \"!\";`")));
    }

    // ---- compiler errors in the hidden tests ----
    @Test void errorsOnlyInTheHiddenTestFileBecomeAMessageWithoutALocation() {
        var c = new Challenge();
        c.testsJson = Json.write(Map.of("TestMain.java", "class TestMain {}"));
        var d = new RunResult.Diagnostic("TestMain.java", 6, "error", "cannot find symbol: method drive()");
        var r = new RunResult(false, List.of(d, d), "", "", List.of(), false, 1, 5);
        var shown = ChallengeService.hideTestDiagnostics(c, r).diagnostics();
        assertEquals(1, shown.size(), "the same message twice is shown once");
        assertEquals("", shown.get(0).file());
        assertTrue(shown.get(0).message().contains("cannot find symbol: method drive()"));
        assertTrue(shown.get(0).message().contains("Requirements"));
    }

    @Test void errorsInTheLearnersOwnFileStayAsTheyAre() {
        var c = new Challenge();
        c.testsJson = Json.write(Map.of("TestMain.java", "class TestMain {}"));
        var own = new RunResult.Diagnostic("Car.java", 3, "error", "';' expected");
        var knock = new RunResult.Diagnostic("TestMain.java", 6, "error", "cannot find symbol");
        var shown = ChallengeService.hideTestDiagnostics(c, new RunResult(false, List.of(own, knock), "", "", List.of(), false, 1, 5)).diagnostics();
        assertEquals(List.of(own), shown);
    }
}
