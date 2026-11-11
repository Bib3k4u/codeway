package com.codeway.runner;

import com.codeway.runner.RunResult.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Shared by both runners: lay out the work dir, read test lines, and turn tool output into diagnostics. */
final class RunSupport {
    private RunSupport() {}

    static final int OUT_CAP = 64 * 1024;
    // javac "A.java:3: error: msg", g++ "a.cpp:3:5: error: msg", and our Python/JS syntax helpers (same shape)
    private static final Pattern DIAG = Pattern.compile("^([A-Za-z0-9_./-]+\\.[a-z]+):(\\d+)(?::\\d+)?: (error|warning|fatal error): (.*)$");

    /** A work dir ready to run: {@code dir/src} (code), {@code dir/out} (build output), {@code dir/tools} (helpers). */
    record Prepared(Lang.Plan plan, String nonce, int expectedTests, String badFile) {}

    static Prepared prepare(Path dir, Lang lang, Map<String, String> files, Map<String, String> tests, CodeRunner.Mode mode) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.createDirectories(dir.resolve("out"));
        Files.createDirectories(dir.resolve("tools"));
        List<String> bad = new ArrayList<>();
        write(src, lang, files, bad);
        int expected = 0;
        if (mode == CodeRunner.Mode.TESTS && tests != null) {
            write(src, lang, tests, bad);
            for (String body : tests.values()) expected += count(body, lang.testCall);
        }
        for (var e : lang.support().entrySet()) Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        if (!bad.isEmpty()) return new Prepared(null, null, 0, bad.get(0));
        List<String> sources = new ArrayList<>();
        try (Stream<Path> s = Files.walk(src)) {
            s.filter(Files::isRegularFile).forEach(p -> sources.add(src.relativize(p).toString()));
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return new Prepared(lang.plan(mode, sources, nonce), nonce, expected, null);
    }

    private static void write(Path root, Lang lang, Map<String, String> files, List<String> bad) throws IOException {
        if (files == null) return;
        for (var e : files.entrySet()) {
            String p = e.getKey();
            if (p == null || !lang.fileName.matcher(p).matches()) { bad.add(String.valueOf(p)); continue; }
            Path target = root.resolve(p).normalize();
            if (!target.startsWith(root)) { bad.add(p); continue; }
            Files.writeString(target, e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8);
        }
    }

    static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** Splits program output into test results (nonce-marked lines) and what the learner printed. */
    static RunResult result(Lang lang, Prepared p, CodeRunner.Mode mode, List<Diagnostic> warnings, String stdout, String stderr,
                            boolean timedOut, int exit, int runLimitS, String srcPrefix, long t0) {
        List<TestResult> results = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        String marker = "@@T" + p.nonce() + "\t";
        for (String line : stdout.split("\n", -1)) {
            if (line.startsWith(marker)) {
                String[] f = line.substring(marker.length()).split("\t", 3);
                if (f.length >= 2) results.add(new TestResult(f[0], Boolean.parseBoolean(f[1]), f.length > 2 ? f[2] : ""));
            } else clean.append(line).append('\n');
        }
        if (mode == CodeRunner.Mode.TESTS)
            for (int i = results.size(); i < p.expectedTests(); i++)
                results.add(new TestResult("Test " + (i + 1), false, timedOut ? "Did not finish: time limit exceeded" : "Did not run: the program stopped early"));
        String err = tidyStderr(lang, stderr, srcPrefix);
        if (timedOut) err = (err + "\nTime limit exceeded (" + runLimitS + "s)").trim();
        else if (lang == Lang.CPP && exit == 139) err = (err + "\nThe program crashed (segmentation fault): check for null pointers and out-of-range access.").trim();
        return new RunResult(true, warnings, clean.toString().stripTrailing(), err, results, timedOut, exit, System.currentTimeMillis() - t0);
    }

    /** Shows tracebacks relative to the project and drops Node's internal frames. */
    static String tidyStderr(Lang lang, String s, String srcPrefix) {
        if (s == null || s.isEmpty()) return "";
        String out = srcPrefix == null ? s : s.replace(srcPrefix, "");
        if (lang == Lang.JAVASCRIPT) {
            StringBuilder b = new StringBuilder();
            for (String line : out.split("\n", -1))
                if (!line.contains("node:internal") && !line.startsWith("Node.js v")) b.append(line).append('\n');
            out = b.toString();
        }
        return out.stripTrailing();
    }

    static List<Diagnostic> parseDiagnostics(Lang lang, String text) {
        List<Diagnostic> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher mt = DIAG.matcher(lines[i].strip());
            if (!mt.matches()) continue;
            String file = mt.group(1).replaceFirst("^(\\./|src/)", "");
            String msg = mt.group(4);
            if (lang == Lang.JAVA) {
                // javac prints the useful detail ("symbol: class Sting") on later lines
                for (int j = i + 1; j < lines.length && j <= i + 4 && !DIAG.matcher(lines[j].strip()).matches(); j++) {
                    String t = lines[j].trim();
                    if (t.startsWith("symbol:")) msg += " (" + t.replaceAll("\\s+", " ") + ")";
                }
                msg = friendlyJava(msg);
            }
            out.add(new Diagnostic(file, Integer.parseInt(mt.group(2)), mt.group(3).startsWith("warning") ? "warning" : "error", msg));
            if (out.size() >= 50) break;
        }
        return out;
    }

    /** Rewrite javac messages that are confusing for learners. */
    static String friendlyJava(String m) {
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

    static String read(Path p) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        String s = new String(b, 0, Math.min(b.length, OUT_CAP), StandardCharsets.UTF_8);
        return b.length > OUT_CAP ? s + "\n… output truncated" : s;
    }

    static RunResult fail(List<Diagnostic> d, long t0) {
        return new RunResult(false, d, "", "", List.of(), false, 1, System.currentTimeMillis() - t0);
    }

    static RunResult fail(String message, long t0) { return fail(List.of(new Diagnostic("", 0, "error", message)), t0); }

    static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {}
    }
}
