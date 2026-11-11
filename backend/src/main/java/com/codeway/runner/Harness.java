package com.codeway.runner;

/**
 * The tiny test harness ("check") placed next to learner code, one per language, plus syntax-check helpers.
 * Every harness prints one line per test: {@code @@T<nonce>\t<name>\t<true|false>\t<message>}.
 * The nonce comes from -Dcheck.nonce (Java) or the CHECK_NONCE environment variable (others).
 */
final class Harness {
    private Harness() {}

    static final String JAVA = """
            public final class Check {
                public interface Body { void run() throws Throwable; }
                private static final String NONCE = System.getProperty("check.nonce", "");

                public static void run(String name, Body body) {
                    try { body.run(); emit(name, true, ""); }
                    catch (Throwable t) {
                        String m = t.getMessage() != null ? t.getMessage() : t.toString();
                        emit(name, false, m);
                    }
                }
                public static void eq(Object expected, Object actual) {
                    if (expected == null ? actual != null : !expected.equals(actual))
                        throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
                }
                public static void eq(double expected, double actual, double eps) {
                    if (Math.abs(expected - actual) > eps)
                        throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
                }
                public static void isTrue(boolean cond, String message) {
                    if (!cond) throw new AssertionError(message);
                }
                public static <T extends Throwable> void throwsEx(Class<T> type, Body body) {
                    try { body.run(); }
                    catch (Throwable t) {
                        if (type.isInstance(t)) return;
                        throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
                    }
                    throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
                }
                private static void emit(String name, boolean ok, String msg) {
                    String clean = msg.replace('\\t', ' ').replace('\\n', ' ').replace('\\r', ' ');
                    if (clean.length() > 300) clean = clean.substring(0, 300);
                    System.out.println("@@T" + NONCE + "\\t" + name.replace('\\t', ' ') + "\\t" + ok + "\\t" + clean);
                }
            }
            """;

    static final String PYTHON = """
            \"\"\"Test helpers for hidden tests: check.run(name, fn), eq, approx, is_true, raises.\"\"\"
            import os

            _NONCE = os.environ.get("CHECK_NONCE", "")


            def _clean(s):
                return str(s).replace("\\t", " ").replace("\\n", " ").replace("\\r", " ")[:300]


            def _emit(name, ok, msg):
                print(f"@@T{_NONCE}\\t{_clean(name)}\\t{'true' if ok else 'false'}\\t{_clean(msg)}", flush=True)


            def run(name, fn):
                try:
                    fn()
                    _emit(name, True, "")
                except AssertionError as e:
                    _emit(name, False, str(e) or "assertion failed")
                except Exception as e:
                    _emit(name, False, f"{type(e).__name__}: {e}")


            def eq(expected, actual):
                if expected != actual:
                    raise AssertionError(f"expected <{expected!r}> but got <{actual!r}>")


            def approx(expected, actual, eps=1e-9):
                if abs(expected - actual) > eps:
                    raise AssertionError(f"expected <{expected!r}> but got <{actual!r}>")


            def is_true(cond, message):
                if not cond:
                    raise AssertionError(message)


            def raises(exc_type, fn):
                try:
                    fn()
                except exc_type:
                    return
                except Exception as e:
                    raise AssertionError(f"expected {exc_type.__name__} but got {type(e).__name__}: {e}")
                raise AssertionError(f"expected {exc_type.__name__} but nothing was raised")
            """;

    /** Prints "file:line: error: message" for each syntax error, like a compiler, without writing bytecode. */
    static final String PYTHON_SYNTAX = """
            import ast, sys
            bad = 0
            for f in sys.argv[1:]:
                try:
                    with open(f, encoding="utf-8") as fh:
                        ast.parse(fh.read(), f)
                except SyntaxError as e:
                    bad = 1
                    print(f"{f}:{e.lineno or 1}: error: {type(e).__name__}: {e.msg}")
            sys.exit(bad)
            """;

    static final String JAVASCRIPT = """
            "use strict";
            // Test helpers for hidden tests: run(name, fn), eq (deep), approx, isTrue, throws.
            const NONCE = process.env.CHECK_NONCE || "";
            const clean = (s) => String(s).replace(/[\\t\\n\\r]/g, " ").slice(0, 300);
            const show = (v) => { try { return typeof v === "object" && v !== null || typeof v === "string" ? JSON.stringify(v) : String(v); } catch { return String(v); } };

            function same(a, b) {
              if (Object.is(a, b)) return true;
              if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
              if (Array.isArray(a) !== Array.isArray(b)) return false;
              const ka = Object.keys(a), kb = Object.keys(b);
              return ka.length === kb.length && ka.every((k) => same(a[k], b[k]));
            }
            function emit(name, ok, msg) { process.stdout.write(`@@T${NONCE}\\t${clean(name)}\\t${ok}\\t${clean(msg)}\\n`); }

            function run(name, fn) {
              try { fn(); emit(name, true, ""); }
              catch (e) { emit(name, false, e && e.message ? (e.name && e.name !== "Error" ? `${e.name}: ${e.message}` : e.message) : String(e)); }
            }
            function eq(expected, actual) {
              if (!same(expected, actual)) throw new Error(`expected <${show(expected)}> but got <${show(actual)}>`);
            }
            function approx(expected, actual, eps = 1e-9) {
              if (Math.abs(expected - actual) > eps) throw new Error(`expected <${expected}> but got <${actual}>`);
            }
            function isTrue(cond, message) { if (!cond) throw new Error(message); }
            function throws(fn, ErrorType) {
              try { fn(); }
              catch (e) {
                if (!ErrorType || e instanceof ErrorType) return;
                throw new Error(`expected ${ErrorType.name} but got ${e && e.name}: ${e && e.message}`);
              }
              throw new Error(`expected ${ErrorType ? ErrorType.name : "an error"} but nothing was thrown`);
            }
            module.exports = { run, eq, approx, isTrue, throws };
            """;

    /** Parses each file as a CommonJS module body (same wrapper as Node) and prints compiler-style errors. */
    static final String JS_SYNTAX = """
            const fs = require("fs"), vm = require("vm");
            let bad = 0;
            for (const f of process.argv.slice(2)) {
              try {
                new vm.Script("(function (exports, require, module, __filename, __dirname) {" + fs.readFileSync(f, "utf8") + "\\n})", { filename: f });
              } catch (e) {
                if (!(e instanceof SyntaxError)) continue;
                bad = 1;
                const m = /:(\\d+)\\s*$/.exec(String(e.stack).split("\\n")[0]);
                console.log(`${f}:${m ? m[1] : 1}: error: SyntaxError: ${e.message}`);
              }
            }
            process.exit(bad);
            """;

    static final String CPP = """
            #pragma once
            // Test helpers for hidden tests: check::run(name, fn), eq, approx, is_true, throws<E>.
            #include <cmath>
            #include <cstdlib>
            #include <exception>
            #include <functional>
            #include <iostream>
            #include <sstream>
            #include <stdexcept>
            #include <string>

            namespace check {
            struct Failure : std::runtime_error { using std::runtime_error::runtime_error; };

            namespace detail {
            template <class T> std::string show(const T& v) {
                if constexpr (requires(std::ostream& o, const T& x) { o << x; }) { std::ostringstream s; s << v; return s.str(); }
                else return "<value>";
            }
            inline std::string clean(std::string s) {
                for (char& c : s) if (c == '\\t' || c == '\\n' || c == '\\r') c = ' ';
                if (s.size() > 300) s.resize(300);
                return s;
            }
            inline void emit(const std::string& name, bool ok, const std::string& msg) {
                const char* n = std::getenv("CHECK_NONCE");
                std::cout << "@@T" << (n ? n : "") << '\\t' << clean(name) << '\\t' << (ok ? "true" : "false") << '\\t' << clean(msg) << std::endl;
            }
            }  // namespace detail

            inline void run(const std::string& name, const std::function<void()>& body) {
                try { body(); detail::emit(name, true, ""); }
                catch (const std::exception& e) { detail::emit(name, false, e.what()); }
                catch (...) { detail::emit(name, false, "unknown exception"); }
            }
            template <class E, class A> void eq(const E& expected, const A& actual) {
                if (!(expected == actual)) throw Failure("expected <" + detail::show(expected) + "> but got <" + detail::show(actual) + ">");
            }
            inline void approx(double expected, double actual, double eps = 1e-9) {
                if (std::fabs(expected - actual) > eps) throw Failure("expected <" + detail::show(expected) + "> but got <" + detail::show(actual) + ">");
            }
            inline void is_true(bool cond, const std::string& message) { if (!cond) throw Failure(message); }
            template <class Ex, class F> void throws(F&& fn) {
                try { fn(); }
                catch (const Ex&) { return; }
                catch (const std::exception& e) { throw Failure(std::string("expected a different exception but got: ") + e.what()); }
                throw Failure("expected an exception but nothing was thrown");
            }
            }  // namespace check
            """;
}
