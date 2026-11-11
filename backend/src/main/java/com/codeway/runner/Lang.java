package com.codeway.runner;

import java.util.*;
import java.util.regex.Pattern;

/**
 * What it takes to check and run a project in each practice language. Both runners (local process and Docker) use
 * the same plan: files live in {@code src/}, build output in {@code out/}, helper scripts in {@code tools/}, and
 * every command runs with {@code src/} as its working directory, so the plan is the same on a laptop and in the
 * sandbox. Hidden tests print nonce-marked lines through a small per-language harness ("check").
 */
public enum Lang {
    JAVA("java", "Java", "[A-Za-z0-9_]+\\.java", ".java", "Main.java", "TestMain.java", "Check.run("),
    PYTHON("python", "Python", "[A-Za-z0-9_]+\\.py", ".py", "main.py", "test_main.py", "check.run("),
    JAVASCRIPT("javascript", "JavaScript", "[A-Za-z0-9_]+\\.js", ".js", "main.js", "test_main.js", "check.run("),
    CPP("cpp", "C++", "[A-Za-z0-9_]+\\.(cpp|hpp|h)", ".cpp, .hpp or .h", "main.cpp", "test_main.cpp", "check::run(");

    public final String id, label, extensions, mainFile, testFile, testCall;
    public final Pattern fileName;

    Lang(String id, String label, String fileName, String extensions, String mainFile, String testFile, String testCall) {
        this.id = id; this.label = label; this.fileName = Pattern.compile(fileName); this.extensions = extensions;
        this.mainFile = mainFile; this.testFile = testFile; this.testCall = testCall;
    }

    public static Lang of(String id) {
        for (Lang l : values()) if (l.id.equals(id)) return l;
        return JAVA;
    }

    public String badNameMessage() { return "Invalid file name. Use letters, digits and underscores, ending in " + extensions; }

    /** One command. {@code argv[0]} is a tool name (javac, java, python3, node, g++) or a path relative to src/. */
    public record Step(List<String> argv, Map<String, String> env) {}

    /**
     * @param check      compile (Java, C++) or syntax check (Python, JavaScript); may produce diagnostics
     * @param run        the program or the test entry point
     * @param entry      path (relative to the work dir) that must exist before {@code run} makes sense
     * @param noEntryMsg shown when {@code entry} is missing
     */
    public record Plan(Step check, Step run, String entry, String noEntryMsg) {}

    /** @param sources learner + harness + test files, relative to src/ */
    public Plan plan(CodeRunner.Mode mode, List<String> sources, String nonce) {
        boolean tests = mode == CodeRunner.Mode.TESTS;
        Map<String, String> env = Map.of("CHECK_NONCE", nonce);
        String entryFile = tests ? testFile : mainFile;
        String missing = tests ? "Test entry point is missing."
                : "No " + mainFile + " was found. Create " + mainFile + " to run your program.";
        return switch (this) {
            case JAVA -> {
                List<String> javac = new ArrayList<>(List.of("javac", "-J-Xmx256m", "-J-XX:-UsePerfData", "--release", "21",
                        "-Xlint:none", "-encoding", "UTF-8", "-d", "../out"));
                javac.addAll(sorted(sources, ".java"));
                String cls = tests ? "TestMain" : "Main";
                yield new Plan(new Step(javac, Map.of()),
                        new Step(List.of("java", "-Xmx256m", "-Xss2m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData",
                                "-Dcheck.nonce=" + nonce, "-Dfile.encoding=UTF-8", "-cp", "../out", cls), env),
                        "out/" + cls + ".class",
                        tests ? missing : "No class named Main with a main method was found. Create Main.java to run your program.");
            }
            case PYTHON -> {
                List<String> chk = new ArrayList<>(List.of("python3", "-B", "-E", "-s", "../tools/syntax.py"));
                chk.addAll(sorted(sources, ".py"));
                yield new Plan(new Step(chk, Map.of()), new Step(List.of("python3", "-B", "-E", "-s", entryFile), env), "src/" + entryFile, missing);
            }
            case JAVASCRIPT -> {
                List<String> chk = new ArrayList<>(List.of("node", "../tools/syntax.js"));
                chk.addAll(sorted(sources, ".js"));
                yield new Plan(new Step(chk, Map.of()), new Step(List.of("node", "--max-old-space-size=256", "--stack-size=2048", entryFile), env), "src/" + entryFile, missing);
            }
            case CPP -> {
                // Learner main.cpp and the test entry each define main(): compile exactly one of them.
                String other = tests ? mainFile : testFile;
                List<String> units = sorted(sources, ".cpp").stream().filter(f -> !f.equals(other)).toList();
                List<String> gpp = new ArrayList<>(List.of("g++", "-std=c++20", "-O0", "-pipe", "-o", "../out/prog"));
                gpp.addAll(units);
                yield new Plan(units.contains(entryFile) ? new Step(gpp, Map.of()) : null,
                        new Step(List.of("../out/prog"), env), "out/prog", missing);
            }
        };
    }

    /** Harness and helper files: path relative to the work dir -> content. */
    public Map<String, String> support() {
        return switch (this) {
            case JAVA -> Map.of("src/Check.java", Harness.JAVA);
            case PYTHON -> Map.of("src/check.py", Harness.PYTHON, "tools/syntax.py", Harness.PYTHON_SYNTAX);
            case JAVASCRIPT -> Map.of("src/check.js", Harness.JAVASCRIPT, "tools/syntax.js", Harness.JS_SYNTAX);
            case CPP -> Map.of("src/check.hpp", Harness.CPP);
        };
    }

    private static List<String> sorted(List<String> files, String ext) {
        return files.stream().filter(f -> f.endsWith(ext)).sorted().toList();
    }
}
