package com.codeway.runner;

import java.util.Map;

/** Checks and runs a learner project in one of the practice languages (see {@link Lang}). */
public interface CodeRunner {
    enum Mode { MAIN, TESTS }

    /**
     * @param language java, python, javascript or cpp
     * @param files    learner files (path -> content)
     * @param tests    hidden test files (path -> content); only used in TESTS mode
     * @param mode     MAIN runs the learner's entry file; TESTS runs the hidden test entry file
     */
    RunResult run(String language, Map<String, String> files, Map<String, String> tests, Mode mode);

    default RunResult run(Map<String, String> files, Map<String, String> tests, Mode mode) {
        return run(Lang.JAVA.id, files, tests, mode);
    }
}
