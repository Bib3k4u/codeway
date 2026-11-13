package com.codeway.runner;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Runs real python3, node and g++ (available locally and on GitHub's ubuntu runners). */
class MultiLanguageRunnerTest {
    private final LocalRunner runner = new LocalRunner();

    // ---- Python ----
    static final String PY_TESTS = """
            import check
            from calc import Calc

            check.run("adds", lambda: check.eq(4, Calc().add(2, 2)))
            def negative():
                check.eq(0, Calc().add(-1, 1))
            check.run("negative", negative)
            check.run("raises on None", lambda: check.raises(TypeError, lambda: Calc().add(None, 1)))
            """;

    @Test void pythonPassesAndFails() {
        var ok = runner.run("python", Map.of("calc.py", "class Calc:\n    def add(self, a, b):\n        return a + b\n"), Map.of("test_main.py", PY_TESTS), CodeRunner.Mode.TESTS);
        assertTrue(ok.allPassed(), ok.toString());
        assertEquals(3, ok.total());
        var bad = runner.run("python", Map.of("calc.py", "class Calc:\n    def add(self, a, b):\n        return a * b\n"), Map.of("test_main.py", PY_TESTS), CodeRunner.Mode.TESTS);
        assertEquals(2, bad.passed(), bad.toString());   // None * 1 also raises TypeError
        assertTrue(bad.tests().get(1).message().contains("expected <0> but got <-1>"), bad.tests().get(1).message());
    }

    @Test void pythonSyntaxErrorAndMain() {
        var r = runner.run("python", Map.of("calc.py", "class Calc:\n    def add(self, a, b)\n        return a + b\n"), Map.of("test_main.py", PY_TESTS), CodeRunner.Mode.TESTS);
        assertFalse(r.compiled());
        assertEquals("calc.py", r.diagnostics().get(0).file());
        assertEquals(2, r.diagnostics().get(0).line());
        var m = runner.run("python", Map.of("main.py", "print('hello from python')\n"), Map.of(), CodeRunner.Mode.MAIN);
        assertEquals("hello from python", m.stdout());
        var crash = runner.run("python", Map.of("main.py", "import helper\nhelper.boom()\n", "helper.py", "def boom():\n    raise ValueError('bad')\n"), Map.of(), CodeRunner.Mode.MAIN);
        assertNotEquals(0, crash.exitCode());
        assertTrue(crash.stderr().contains("File \"helper.py\""), crash.stderr());
        assertTrue(crash.stderr().contains("ValueError: bad"));
    }

    // ---- JavaScript ----
    static final String JS_TESTS = """
            const check = require("./check");
            const { Calc } = require("./calc");
            check.run("adds", () => check.eq(4, new Calc().add(2, 2)));
            check.run("negative", () => { check.eq(0, new Calc().add(-1, 1)); });
            check.run("deep equal", () => check.eq([1, { a: 2 }], new Calc().pair()));
            """;

    @Test void javascriptPassesAndFails() {
        var ok = runner.run("javascript", Map.of("calc.js", "class Calc { add(a, b) { return a + b; } pair() { return [1, { a: 2 }]; } }\nmodule.exports = { Calc };\n"),
                Map.of("test_main.js", JS_TESTS), CodeRunner.Mode.TESTS);
        assertTrue(ok.allPassed(), ok.toString());
        var bad = runner.run("javascript", Map.of("calc.js", "class Calc { add(a, b) { return a * b; } pair() { return [1, { a: 3 }]; } }\nmodule.exports = { Calc };\n"),
                Map.of("test_main.js", JS_TESTS), CodeRunner.Mode.TESTS);
        assertEquals(1, bad.passed(), bad.toString());
        assertTrue(bad.tests().get(2).message().contains("expected <[1,{\"a\":2}]>"), bad.tests().get(2).message());
    }

    @Test void javascriptSyntaxErrorAndMain() {
        var r = runner.run("javascript", Map.of("calc.js", "class Calc {\n  add(a, b) { return a + ; }\n}\nmodule.exports = { Calc };\n"), Map.of("test_main.js", JS_TESTS), CodeRunner.Mode.TESTS);
        assertFalse(r.compiled(), r.toString());
        assertEquals("calc.js", r.diagnostics().get(0).file());
        assertEquals(2, r.diagnostics().get(0).line());
        var m = runner.run("javascript", Map.of("main.js", "console.log('hello from node');\n"), Map.of(), CodeRunner.Mode.MAIN);
        assertEquals("hello from node", m.stdout());
        var crash = runner.run("javascript", Map.of("main.js", "require('./helper').boom();\n", "helper.js", "module.exports = { boom() { throw new TypeError('bad'); } };\n"), Map.of(), CodeRunner.Mode.MAIN);
        assertNotEquals(0, crash.exitCode());
        assertTrue(crash.stderr().contains("helper.js"), crash.stderr());
        assertFalse(crash.stderr().contains("node:internal"));
    }

    // ---- C++ ----
    static final String CPP_TESTS = """
            #include "check.hpp"
            #include "calc.hpp"
            #include <stdexcept>
            int main() {
                check::run("adds", [] { check::eq(4, Calc().add(2, 2)); });
                check::run("negative", [] { check::eq(0, Calc().add(-1, 1)); });
                check::run("name", [] { check::eq(std::string("calc"), Calc().name()); });
                check::run("throws", [] { check::throws<std::invalid_argument>([] { Calc().divide(1, 0); }); });
            }
            """;

    static final String CALC_HPP = """
            #pragma once
            #include <stdexcept>
            #include <string>
            class Calc {
            public:
                int add(int a, int b) const { return a %s b; }
                std::string name() const { return "calc"; }
                int divide(int a, int b) const { if (b == 0) throw std::invalid_argument("zero"); return a / b; }
            };
            """;

    @Test void cppPassesAndFailsAndIgnoresLearnerMain() {
        String main = "#include <iostream>\n#include \"calc.hpp\"\nint main() { std::cout << Calc().add(1, 2) << std::endl; }\n";
        var ok = runner.run("cpp", Map.of("calc.hpp", CALC_HPP.formatted("+"), "main.cpp", main), Map.of("test_main.cpp", CPP_TESTS), CodeRunner.Mode.TESTS);
        assertTrue(ok.allPassed(), ok.toString());
        assertEquals(4, ok.total());
        var bad = runner.run("cpp", Map.of("calc.hpp", CALC_HPP.formatted("*"), "main.cpp", main), Map.of("test_main.cpp", CPP_TESTS), CodeRunner.Mode.TESTS);
        assertEquals(3, bad.passed(), bad.toString());
        assertTrue(bad.tests().get(1).message().contains("expected <0> but got <-1>"), bad.tests().get(1).message());
        var m = runner.run("cpp", Map.of("calc.hpp", CALC_HPP.formatted("+"), "main.cpp", main), Map.of(), CodeRunner.Mode.MAIN);
        assertEquals("3", m.stdout(), m.toString());
    }

    @Test void cppCompileErrorPointsAtLine() {
        var r = runner.run("cpp", Map.of("calc.hpp", "#pragma once\nclass Calc {\npublic:\n    int add(int a, int b) const { return a + ; }\n};\n"), Map.of("test_main.cpp", CPP_TESTS), CodeRunner.Mode.TESTS);
        assertFalse(r.compiled());
        assertEquals("calc.hpp", r.diagnostics().get(0).file(), r.diagnostics().toString());
        assertEquals(4, r.diagnostics().get(0).line());
    }

    @Test void stoppedEarlyCountsMissingTests() {
        var r = runner.run("python", Map.of("calc.py", "import sys\nclass Calc:\n    def add(self, a, b):\n        sys.exit(3)\n"), Map.of("test_main.py", PY_TESTS), CodeRunner.Mode.TESTS);
        assertEquals(3, r.total());
        assertEquals(0, r.passed());
    }

    @Test void badFileNamesRejectedPerLanguage() {
        var r = runner.run("python", Map.of("../evil.py", "x = 1"), Map.of(), CodeRunner.Mode.MAIN);
        assertFalse(r.compiled());
        assertTrue(r.diagnostics().get(0).message().contains(".py"));
        var j = runner.run("javascript", Map.of("Main.java", "class Main {}"), Map.of(), CodeRunner.Mode.MAIN);
        assertFalse(j.compiled());
    }

    @Test void dockerScriptQuotesAndChecksEntry() {
        var plan = Lang.PYTHON.plan(CodeRunner.Mode.TESTS, java.util.List.of("check.py", "calc.py", "test_main.py"), "abc");
        String s = DockerRunner.script(plan);
        assertTrue(s.contains("'python3' '-B' '-E' '-s' '../tools/syntax.py' 'calc.py' 'check.py' 'test_main.py'"), s);
        assertTrue(s.contains("[ -e ../src/test_main.py ]"), s);
        assertTrue(s.contains("CHECK_NONCE='abc' timeout -s KILL 10 'python3'"), s);
        var cpp = Lang.CPP.plan(CodeRunner.Mode.TESTS, java.util.List.of("calc.hpp", "main.cpp", "test_main.cpp", "check.hpp"), "n");
        assertFalse(cpp.check().argv().contains("main.cpp"), "learner main() must not be linked with the test main()");
        assertTrue(cpp.check().argv().contains("test_main.cpp"));
    }

    @Test void sandboxCommandIsLockedDown() {
        var dir = java.nio.file.Path.of("/opt/codeway/tmp/run-1");
        var plain = DockerRunner.dockerCommand("docker", "", "cw-x", "512m", "1.0", dir, "img");
        for (String flag : java.util.List.of("--network", "none", "--read-only", "--cap-drop", "ALL", "no-new-privileges", "65534:65534",
                "fsize=33554432:33554432", "core=0", "--pids-limit", "--memory-swap"))
            assertTrue(plain.contains(flag), flag + " missing in " + plain);
        assertFalse(plain.contains("--runtime"), "default runtime unless configured");
        assertFalse(plain.contains("--privileged"));
        var gv = DockerRunner.dockerCommand("docker", " runsc ", "cw-x", "512m", "1.0", dir, "img");
        assertEquals("runsc", gv.get(gv.indexOf("--runtime") + 1));
        assertEquals("/opt/codeway/tmp/run-1:/work", gv.get(gv.indexOf("-v") + 1));
        assertTrue(gv.indexOf("--runtime") < gv.indexOf("img"), "runtime option must come before the image");
    }
}
