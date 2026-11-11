package com.codeway.runner;

import java.util.List;

public record RunResult(
        boolean compiled,
        List<Diagnostic> diagnostics,
        String stdout,
        String stderr,
        List<TestResult> tests,
        boolean timedOut,
        int exitCode,
        long millis) {

    public record Diagnostic(String file, int line, String severity, String message) {}
    public record TestResult(String name, boolean passed, String message) {}

    public int passed() { return (int) tests.stream().filter(TestResult::passed).count(); }
    public int total() { return tests.size(); }
    public boolean allPassed() { return compiled && !tests.isEmpty() && tests.stream().allMatch(TestResult::passed); }
    public boolean runtimeError() { return compiled && !timedOut && exitCode != 0 && tests.isEmpty(); }
}
