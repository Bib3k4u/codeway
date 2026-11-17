package com.codeway.service;

import com.codeway.domain.Challenge;
import com.codeway.runner.CodeRunner;
import com.codeway.runner.LocalRunner;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The provided main file (SOLID and later) is read-only, and a Run can be fed custom input. */
class LockedMainTest {
    private static Challenge locked(boolean on) {
        Challenge c = new Challenge();
        c.language = "java";
        c.starterFilesJson = Json.write(Map.of("Main.java", "public class Main { public static void main(String[] a) { System.out.println(\"original\"); } }", "Calc.java", "class Calc {}"));
        c.mainLocked = on ? true : null;
        return c;
    }

    @Test void theProvidedMainIsPutBackWhateverTheLearnerSent() {
        Challenge ch = locked(true);
        assertEquals(List.of("Main.java"), AttemptService.lockedFiles(ch));
        var sent = Map.of("Main.java", "// tampered", "Calc.java", "class Calc { int x; }");
        var used = AttemptService.withLockedMain(ch, sent);
        assertTrue(used.get("Main.java").contains("original"));
        assertEquals("class Calc { int x; }", used.get("Calc.java"));
        // deleted from the project: it comes back too
        assertTrue(AttemptService.withLockedMain(ch, Map.of("Calc.java", "class Calc {}")).containsKey("Main.java"));
    }

    @Test void whenTheLearnerWritesMainItIsLeftAlone() {
        Challenge ch = locked(false);
        assertTrue(AttemptService.lockedFiles(ch).isEmpty());
        var sent = Map.of("Main.java", "// mine");
        assertEquals("// mine", AttemptService.withLockedMain(ch, sent).get("Main.java"));
    }

    @Test void customInputReachesTheProgram() {
        var r = new LocalRunner().run("java", Map.of("Main.java", """
                import java.util.Scanner;
                public class Main { public static void main(String[] a) {
                    Scanner in = new Scanner(System.in);
                    int n = in.nextInt(); int m = in.nextInt();
                    System.out.println(n + m);
                } }
                """), Map.of(), CodeRunner.Mode.MAIN, "20 22\n");
        assertTrue(r.compiled(), r.toString());
        assertEquals("42", r.stdout().trim());
    }

    @Test void withoutInputTheProgramSeesAnEmptyStream() {
        var r = new LocalRunner().run("java", Map.of("Main.java", "public class Main { public static void main(String[] a) throws Exception { System.out.println(System.in.read()); } }"),
                Map.of(), CodeRunner.Mode.MAIN, null);
        assertEquals("-1", r.stdout().trim());
    }
}
