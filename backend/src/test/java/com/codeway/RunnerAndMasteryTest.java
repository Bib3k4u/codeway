package com.codeway;

import com.codeway.runner.*;
import com.codeway.service.MasteryService;
import com.codeway.service.MasteryService.Signals;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RunnerAndMasteryTest {
    private final LocalRunner runner = new LocalRunner();

    private static final String TESTS = """
            public class TestMain { public static void main(String[] a) {
                Check.run("adds", () -> Check.eq(4, Calc.add(2, 2)));
                Check.run("negative", () -> Check.eq(0, Calc.add(-1, 1)));
            } }
            """;

    @Test void passingSolution() {
        var r = runner.run(Map.of("Calc.java", "public class Calc { static int add(int a, int b){ return a+b; } }"),
                Map.of("TestMain.java", TESTS), CodeRunner.Mode.TESTS);
        assertTrue(r.compiled());
        assertTrue(r.allPassed(), r.toString());
        assertEquals(2, r.total());
    }

    @Test void failingSolutionReportsMessages() {
        var r = runner.run(Map.of("Calc.java", "public class Calc { static int add(int a, int b){ return a*b; } }"),
                Map.of("TestMain.java", TESTS), CodeRunner.Mode.TESTS);
        assertTrue(r.compiled());
        assertEquals(1, r.passed());
        assertTrue(r.tests().get(1).message().contains("expected <0> but got <-1>"));
    }

    @Test void compileErrorsBecomeMarkers() {
        var r = runner.run(Map.of("Calc.java", "public class Calc { static int add(int a, int b){ return a+ } }"),
                Map.of("TestMain.java", TESTS), CodeRunner.Mode.TESTS);
        assertFalse(r.compiled());
        assertEquals("Calc.java", r.diagnostics().get(0).file());
        assertEquals(1, r.diagnostics().get(0).line());
    }

    @Test void infiniteLoopTimesOutAndSystemExitCountsAsFailure() {
        var loop = runner.run(Map.of("Main.java", "public class Main { public static void main(String[] a){ while(true){} } }"), Map.of(), CodeRunner.Mode.MAIN);
        assertTrue(loop.timedOut());
        var exit = runner.run(Map.of("Calc.java", "public class Calc { static int add(int a,int b){ System.exit(0); return 0; } }"),
                Map.of("TestMain.java", TESTS), CodeRunner.Mode.TESTS);
        assertFalse(exit.allPassed());
        assertEquals(2, exit.total());
    }

    @Test void mainModePrintsStdout() {
        var r = runner.run(Map.of("Main.java", "public class Main { public static void main(String[] a){ System.out.println(\"hi\"); } }"), Map.of(), CodeRunner.Mode.MAIN);
        assertEquals("hi", r.stdout());
    }

    @Test void pathTraversalRejected() {
        var r = runner.run(Map.of("../Evil.java", "class Evil {}"), Map.of(), CodeRunner.Mode.MAIN);
        assertFalse(r.compiled());
    }

    @Test void performanceRules() {
        double clean = MasteryService.performance(new Signals(5, 5, 2, 0, 0, 4, 10, 0, 3, 0));
        double hinted = MasteryService.performance(new Signals(5, 5, 2, 0, 2, 4, 10, 0, 3, 0));
        double solution = MasteryService.performance(new Signals(5, 5, 2, 0, 4, 4, 10, 0, 3, 0));
        double failed = MasteryService.performance(new Signals(2, 5, 2, 0, 0, 4, 10, 0, 3, 0));
        assertEquals(1.0, clean, 1e-9);
        assertTrue(hinted < clean && solution <= 0.2 && failed < 0.3);
        double resubmitted = MasteryService.performance(new Signals(5, 5, 2, 0, 0, 4, 10, 0, 3, 2));
        assertEquals(0.8, resubmitted, 1e-9);
        assertEquals("overconfident", MasteryService.calibration(5, 1, 5));
        assertEquals(1, MasteryService.targetDifficulty(0.15));
        assertEquals(5, MasteryService.targetDifficulty(0.95));
        assertEquals("HEAVY", MasteryService.scaffolding(0.2));
        assertEquals("BLANK", MasteryService.scaffolding(0.8));
    }

    @Test void learnerMistakesGetFriendlyMessages() {
        String book = "public class Book {\n    private Sting title;\n}\npublic static void main(String[] args){\n}\n";
        var r = runner.run(Map.of("Book.java", book), Map.of(), CodeRunner.Mode.MAIN);
        assertFalse(r.compiled());
        String all = r.diagnostics().toString();
        assertTrue(all.contains("outside of a class"), all);
        assertFalse(all.contains("-source 21"), all);
        var typo = runner.run(Map.of("Book.java", "public class Book { private Sting title; }"), Map.of(), CodeRunner.Mode.MAIN);
        assertTrue(typo.diagnostics().get(0).message().contains("symbol: class Sting"), typo.diagnostics().toString());
    }
}
