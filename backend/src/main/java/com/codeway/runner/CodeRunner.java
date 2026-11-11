package com.codeway.runner;

import java.util.Map;

/** Compile and execute a Java project. Implementations: local process (dev) or Judge0/Piston (prod). */
public interface CodeRunner {
    enum Mode { MAIN, TESTS }

    /**
     * @param files   learner files (path -> content)
     * @param tests   hidden test files (path -> content); only used in TESTS mode
     * @param mode    MAIN runs class Main; TESTS runs class TestMain
     */
    RunResult run(Map<String, String> files, Map<String, String> tests, Mode mode);
}
