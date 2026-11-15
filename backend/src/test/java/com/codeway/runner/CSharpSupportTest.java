package com.codeway.runner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C# support that can be checked without the .NET SDK: the run plan, the harness, the compile script (against a fake SDK folder),
 * compiler messages and the sandbox script. A real compile-and-run test runs only where `dotnet` is installed.
 */
class CSharpSupportTest {
    @Test void languageIsKnownAndFilesAreChecked() {
        assertSame(Lang.CSHARP, Lang.of("csharp"));
        assertEquals("C#", Lang.CSHARP.label);
        assertTrue(Lang.CSHARP.fileName.matcher("Shape.cs").matches());
        assertFalse(Lang.CSHARP.fileName.matcher("Shape.java").matches());
        assertFalse(Lang.CSHARP.fileName.matcher("../Shape.cs").matches());
        assertTrue(Lang.CSHARP.testCall.equals("Check.Run("));
    }

    @Test void planCompilesEverythingAndPicksTheEntryClass() {
        var tests = Lang.CSHARP.plan(CodeRunner.Mode.TESTS, List.of("Shape.cs", "Program.cs", "TestMain.cs", "Check.cs"), "n0nce");
        assertEquals(List.of("sh", "../tools/csc.sh", "TestMain", "Check.cs", "Program.cs", "Shape.cs", "TestMain.cs"), tests.check().argv());
        assertEquals(List.of("dotnet", "../out/prog.dll"), tests.run().argv());
        assertEquals("n0nce", tests.run().env().get("CHECK_NONCE"));
        assertEquals("out/prog.dll", tests.entry());
        var main = Lang.CSHARP.plan(CodeRunner.Mode.MAIN, List.of("Shape.cs", "Program.cs", "TestMain.cs"), "x");
        assertEquals("Program", main.check().argv().get(2));
    }

    @Test void theSandboxScriptRunsTheCompileScriptThroughSh() {
        var plan = Lang.CSHARP.plan(CodeRunner.Mode.MAIN, List.of("Program.cs"), "x");
        String s = DockerRunner.script(plan);
        assertTrue(s.contains("'sh' '../tools/csc.sh' 'Program' 'Program.cs'"), s);
        assertTrue(s.contains("'dotnet' '../out/prog.dll'"), s);
    }

    @Test void harnessAndScriptAreInTheSupportFiles() {
        var support = Lang.CSHARP.support();
        assertTrue(support.get("src/Check.cs").contains("public static void Run(string name, Action body)"));
        assertTrue(support.get("src/Check.cs").contains("Throws<T>"));
        assertTrue(support.get("src/Check.cs").contains("CHECK_NONCE"));
        assertTrue(support.get("tools/csc.sh").contains("-main:\"$MAIN\""));
    }

    @Test void harnessEscapesSurviveTheJavaTextBlock() {
        String cs = Lang.CSHARP.support().get("src/Check.cs");
        // a doubled backslash in the Java source is what keeps '\n' a C# escape instead of a real line break inside the character literal
        assertTrue(cs.contains("'\\n'") && cs.contains("'\\t'") && cs.contains("\"\\t\""), cs);
        assertFalse(cs.contains("'\n'") || cs.contains("'\t'"), "raw control characters inside a char literal");
        assertTrue(cs.contains("\"\\\"\" + s + \"\\\"\""), "quotes around a shown string");
    }

    @Test void compilerMessagesBecomeDiagnostics() {
        String log = "Shape.cs(12,5): error CS1002: ; expected\n"
                + "/work/src/Circle.cs(3,18): error CS0246: The type or namespace name 'Shpe' could not be found (are you missing a using directive or an assembly reference?) [/work/x.csproj]\n"
                + "Program.cs(1,1): warning CS8019: Unnecessary using directive.\n"
                + "garbage line\n";
        var d = RunSupport.parseDiagnostics(Lang.CSHARP, log);
        assertEquals(3, d.size());
        assertEquals("Shape.cs", d.get(0).file());
        assertEquals(12, d.get(0).line());
        assertEquals("error", d.get(0).severity());
        assertEquals("CS1002: ; expected", d.get(0).message());
        assertEquals("Circle.cs", d.get(1).file());
        assertTrue(d.get(1).message().startsWith("CS0246: The type or namespace name 'Shpe'"));
        assertFalse(d.get(1).message().contains("csproj"));
        assertEquals("warning", d.get(2).severity());
    }

    @Test void theCompileScriptFindsTheSdkAndWritesARuntimeConfig() throws Exception {
        Assumptions.assumeTrue(new File("/bin/sh").exists());
        Path root = Files.createTempDirectory("fake-dotnet");
        Path work = Files.createTempDirectory("cs-work");
        try {
            // a fake SDK layout and a fake `dotnet` that records how it was called
            Files.createDirectories(root.resolve("sdk/8.0.100/Roslyn/bincore"));
            Files.createDirectories(root.resolve("sdk/8.0.404/Roslyn/bincore"));
            Files.createDirectories(root.resolve("packs/Microsoft.NETCore.App.Ref/8.0.11/ref/net8.0"));
            Files.createDirectories(root.resolve("shared/Microsoft.NETCore.App/8.0.11"));
            Files.writeString(root.resolve("packs/Microsoft.NETCore.App.Ref/8.0.11/ref/net8.0/System.Runtime.dll"), "");
            Files.writeString(root.resolve("packs/Microsoft.NETCore.App.Ref/8.0.11/ref/net8.0/System.Collections.dll"), "");
            Path calls = work.resolve("calls.txt");
            Path dotnet = root.resolve("dotnet");
            Files.writeString(dotnet, "#!/bin/sh\nfor a in \"$@\"; do echo \"$a\"; done > \"" + calls + "\"\n");
            assertTrue(dotnet.toFile().setExecutable(true));
            Files.createDirectories(work.resolve("src"));
            Files.createDirectories(work.resolve("out"));
            Files.createDirectories(work.resolve("tools"));
            Files.writeString(work.resolve("tools/csc.sh"), Harness.CSC_SCRIPT);

            var pb = new ProcessBuilder("sh", "../tools/csc.sh", "TestMain", "A.cs", "TestMain.cs").directory(work.resolve("src").toFile()).redirectErrorStream(true);
            pb.environment().put("PATH", root + File.pathSeparator + "/usr/bin" + File.pathSeparator + "/bin");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            assertTrue(p.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, p.exitValue(), out);

            String recorded = Files.readString(calls);
            assertTrue(recorded.contains(root.toRealPath() + "/sdk/8.0.404/Roslyn/bincore/csc.dll") || recorded.contains(root + "/sdk/8.0.404/Roslyn/bincore/csc.dll"), "newest SDK is used:\n" + recorded);
            assertTrue(recorded.contains("-main:TestMain") && recorded.contains("-out:../out/prog.dll"), recorded);
            assertTrue(recorded.contains("System.Runtime.dll") && recorded.contains("System.Collections.dll"), recorded);
            assertTrue(recorded.lines().anyMatch(l -> l.equals("A.cs")) && recorded.lines().anyMatch(l -> l.equals("TestMain.cs")), recorded);
            String cfg = Files.readString(work.resolve("out/prog.runtimeconfig.json"));
            assertTrue(cfg.contains("\"tfm\":\"net8.0\"") && cfg.contains("\"version\":\"8.0.11\""), cfg);
        } finally {
            RunSupport.deleteQuietly(root);
            RunSupport.deleteQuietly(work);
        }
    }

    @Test void theCompileScriptSaysSoWhenThereIsNoSdk() throws Exception {
        Path work = Files.createTempDirectory("cs-nosdk");
        try {
            Files.createDirectories(work.resolve("src"));
            Files.createDirectories(work.resolve("out"));
            Files.writeString(work.resolve("csc.sh"), Harness.CSC_SCRIPT);
            var pb = new ProcessBuilder("/bin/sh", "../csc.sh", "Program", "Program.cs").directory(work.resolve("src").toFile()).redirectErrorStream(true);
            pb.environment().put("PATH", "/usr/bin:/bin");
            Assumptions.assumeTrue(!new File("/usr/bin/dotnet").exists() && !new File("/bin/dotnet").exists());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, p.exitValue());
            assertTrue(out.contains("the .NET SDK is not installed"), out);
        } finally {
            RunSupport.deleteQuietly(work);
        }
    }

    @Test void aRealRunWhereDotnetIsInstalled() {
        boolean haveDotnet = List.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)).stream().anyMatch(d -> new File(d, "dotnet").canExecute());
        Assumptions.assumeTrue(haveDotnet, "no .NET SDK here: skipped");
        var runner = new LocalRunner();
        var files = Map.of("Calc.cs", "public class Calc { public int Add(int a, int b) { return a + b; } }");
        var tests = Map.of("TestMain.cs", "using System; public class TestMain { public static void Main(string[] a) {"
                + " Check.Run(\"adds\", () => Check.Eq(5, new Calc().Add(2, 3)));"
                + " Check.Run(\"fails\", () => Check.Eq(6, new Calc().Add(2, 3))); } }");
        var r = runner.run("csharp", files, tests, CodeRunner.Mode.TESTS);
        assertTrue(r.compiled(), r.diagnostics().toString());
        assertEquals(2, r.tests().size());
        assertTrue(r.tests().get(0).passed());
        assertFalse(r.tests().get(1).passed());
    }
}
