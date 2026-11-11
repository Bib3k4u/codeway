package com.codeway.runner;

import com.codeway.runner.RunResult.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * DEV-ONLY runner: compiles and runs learner code in a child JVM inside a temp dir.
 * Limits: wall-clock timeout, heap cap, output cap, stdin closed. NOT a security sandbox:
 * replace with Judge0/Piston in Docker before exposing this to untrusted users.
 */
@Component
@ConditionalOnProperty(name = "codeway.runner.mode", havingValue = "local", matchIfMissing = true)
public class LocalJavaRunner implements CodeRunner {
    private static final Pattern PATH_OK = Pattern.compile("[A-Za-z0-9_]+(/[A-Za-z0-9_]+)*\\.java");
    private static final Pattern DIAG = Pattern.compile("^(.+\\.java):(\\d+): (error|warning): (.*)$");
    private static final int OUT_CAP = 64 * 1024;
    private static final long COMPILE_TIMEOUT_S = 30;
    private static final long RUN_TIMEOUT_S = 10;

    private final String javaHome = System.getProperty("java.home");

    @Value("${codeway.runner.mode:local}")
    private String mode;

    @Override
    public RunResult run(Map<String, String> files, Map<String, String> tests, Mode m) {
        long t0 = System.currentTimeMillis();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("codeway-run-");
            Path src = Files.createDirectories(dir.resolve("src"));
            Path out = Files.createDirectories(dir.resolve("out"));

            List<String> bad = new ArrayList<>();
            write(src, files, bad);
            write(src, Map.of(CheckHarness.PATH, CheckHarness.SOURCE), bad);
            int expected = 0;
            if (m == Mode.TESTS && tests != null) {
                write(src, tests, bad);
                for (String body : tests.values()) expected += count(body, "Check.run(");
            }
            if (!bad.isEmpty()) {
                return fail(List.of(new Diagnostic(bad.get(0), 1, "error", "Invalid file name. Use letters, digits and underscores, ending in .java")), t0);
            }

            List<String> javaFiles = new ArrayList<>();
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(src.relativize(p).toString()));
            }
            List<String> cmd = new ArrayList<>(List.of(bin("javac"), "--release", "21", "-Xlint:none", "-encoding", "UTF-8", "-d", out.toString()));
            cmd.addAll(javaFiles);
            Proc c = exec(cmd, src, COMPILE_TIMEOUT_S);
            List<Diagnostic> diags = parseDiagnostics(c.stderr + "\n" + c.stdout);
            if (c.exit != 0 || c.timedOut) {
                if (diags.isEmpty()) diags = List.of(new Diagnostic("", 0, "error", (c.stderr + c.stdout).trim()));
                return new RunResult(false, diags, "", "", List.of(), c.timedOut, c.exit, System.currentTimeMillis() - t0);
            }

            String nonce = UUID.randomUUID().toString().replace("-", "");
            String mainClass = m == Mode.TESTS ? "TestMain" : "Main";
            if (!Files.exists(out.resolve(mainClass + ".class"))) {
                String msg = m == Mode.TESTS ? "Test entry point is missing." : "No class named Main with a main method was found. Create Main.java to run your program.";
                return new RunResult(true, diags, "", msg, List.of(), false, 1, System.currentTimeMillis() - t0);
            }
            Proc r = exec(List.of(bin("java"), "-Xmx256m", "-Xss2m", "-XX:+UseSerialGC", "-Dcheck.nonce=" + nonce,
                    "-Dfile.encoding=UTF-8", "-cp", out.toString(), mainClass), out, RUN_TIMEOUT_S);

            List<TestResult> results = new ArrayList<>();
            StringBuilder clean = new StringBuilder();
            String marker = "@@T" + nonce + "\t";
            for (String line : r.stdout.split("\n", -1)) {
                if (line.startsWith(marker)) {
                    String[] f = line.substring(marker.length()).split("\t", 3);
                    if (f.length >= 2) results.add(new TestResult(f[0], Boolean.parseBoolean(f[1]), f.length > 2 ? f[2] : ""));
                } else {
                    clean.append(line).append('\n');
                }
            }
            if (m == Mode.TESTS) {
                for (int i = results.size(); i < expected; i++)
                    results.add(new TestResult("Test " + (i + 1), false, r.timedOut ? "Did not finish: time limit exceeded" : "Did not run: the program stopped early"));
            }
            String stderr = r.timedOut ? (r.stderr + "\nTime limit exceeded (" + RUN_TIMEOUT_S + "s)").trim() : r.stderr;
            return new RunResult(true, diags, clean.toString().stripTrailing(), stderr, results, r.timedOut, r.exit, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return fail(List.of(new Diagnostic("", 0, "error", "Runner error: " + e.getMessage())), t0);
        } finally {
            if (dir != null) deleteQuietly(dir);
        }
    }

    // ---- helpers ----
    private String bin(String name) { return javaHome + File.separator + "bin" + File.separator + name; }

    static void write(Path root, Map<String, String> files, List<String> bad) throws IOException {
        if (files == null) return;
        for (var e : files.entrySet()) {
            String p = e.getKey();
            if (!PATH_OK.matcher(p).matches()) { bad.add(p); continue; }
            Path target = root.resolve(p).normalize();
            if (!target.startsWith(root)) { bad.add(p); continue; }
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8);
        }
    }

    static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    static List<Diagnostic> parseDiagnostics(String text) {
        List<Diagnostic> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher mt = DIAG.matcher(lines[i]);
            if (!mt.matches()) continue;
            String msg = mt.group(4);
            // javac prints the useful detail ("symbol: class Sting") on later lines
            for (int j = i + 1; j < lines.length && j <= i + 4 && !DIAG.matcher(lines[j]).matches(); j++) {
                String t = lines[j].trim();
                if (t.startsWith("symbol:")) msg += " (" + t.replaceAll("\\s+", " ") + ")";
            }
            out.add(new Diagnostic(mt.group(1), Integer.parseInt(mt.group(2)), mt.group(3), friendly(msg)));
        }
        return out;
    }

    /** Rewrite javac messages that are confusing for learners. */
    static String friendly(String m) {
        if (m.contains("implicitly declared classes") || m.contains("class, interface, enum, or record expected")
                || m.contains("unnamed class"))
            return "This code is outside of a class. In Java every field and method, including main, must be inside a class. "
                    + "Put your main method in Main.java inside `public class Main { ... }`, and make sure the braces of your other class are balanced.";
        if (m.startsWith("cannot find symbol"))
            return m + ". Check the spelling and capitalisation (for example String, not Sting) and that the class exists.";
        if (m.contains("non-static") && m.contains("static context"))
            return m + ". Create an object with `new` first, then call the method on it.";
        if (m.contains("has private access"))
            return m + ". Private members can only be used inside their own class: use a public getter instead.";
        if (m.startsWith("class ") && m.contains("is public, should be declared in a file named"))
            return m + ". Rename the file or the class so they match.";
        return m;
    }

    record Proc(int exit, String stdout, String stderr, boolean timedOut) {}

    static Proc exec(List<String> cmd, Path cwd, long timeoutSec) throws IOException, InterruptedException {
        Path so = Files.createTempFile("cw-out", ".txt");
        Path se = Files.createTempFile("cw-err", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile())
                    .redirectOutput(so.toFile()).redirectError(se.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.environment().keySet().removeIf(k -> k.contains("KEY") || k.contains("SECRET") || k.contains("PASSWORD") || k.contains("TOKEN"));
            Process p = pb.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS); }
            return new Proc(done ? p.exitValue() : -1, read(so), read(se), !done);
        } finally {
            Files.deleteIfExists(so);
            Files.deleteIfExists(se);
        }
    }

    static String read(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        String s = new String(b, 0, Math.min(b.length, OUT_CAP), StandardCharsets.UTF_8);
        return b.length > OUT_CAP ? s + "\n… output truncated" : s;
    }

    static RunResult fail(List<Diagnostic> d, long t0) {
        return new RunResult(false, d, "", "", List.of(), false, 1, System.currentTimeMillis() - t0);
    }

    static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {}
    }
}
