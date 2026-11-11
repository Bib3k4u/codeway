package com.codeway.runner;

import com.codeway.runner.RunResult.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * DEV-ONLY runner: checks and runs learner code as child processes in a temp dir, using the JDK this app runs on
 * plus python3, node and g++ from PATH. Limits: wall-clock timeout, output cap, stdin closed, secrets stripped from
 * the environment. NOT a security sandbox: production uses {@link DockerRunner}.
 */
@Component
@ConditionalOnProperty(name = "codeway.runner.mode", havingValue = "local", matchIfMissing = true)
public class LocalRunner implements CodeRunner {
    private static final long CHECK_TIMEOUT_S = 30;
    private static final int RUN_TIMEOUT_S = 10;
    private final String javaHome = System.getProperty("java.home");

    @Override
    public RunResult run(String language, Map<String, String> files, Map<String, String> tests, Mode mode) {
        long t0 = System.currentTimeMillis();
        Lang lang = Lang.of(language);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("codeway-run-");
            RunSupport.Prepared p = RunSupport.prepare(dir, lang, files, tests, mode);
            if (p.badFile() != null) return RunSupport.fail(List.of(new Diagnostic(p.badFile(), 1, "error", lang.badNameMessage())), t0);
            Path src = dir.resolve("src");
            String prefix = src.toRealPath() + File.separator;

            List<Diagnostic> diags = List.of();
            if (p.plan().check() != null) {
                Proc c = exec(argv(p.plan().check(), dir), src, p.plan().check().env(), CHECK_TIMEOUT_S);
                String log = (c.stdout + "\n" + c.stderr).replace(prefix, "");
                diags = RunSupport.parseDiagnostics(lang, log);
                if (c.exit != 0 || c.timedOut) {
                    if (diags.isEmpty()) diags = List.of(new Diagnostic("", 0, "error", c.timedOut ? "Compiling took too long." : log.trim()));
                    return new RunResult(false, diags, "", "", List.of(), c.timedOut, c.exit, System.currentTimeMillis() - t0);
                }
            }
            if (!Files.exists(dir.resolve(p.plan().entry())))
                return new RunResult(true, diags, "", p.plan().noEntryMsg(), List.of(), false, 1, System.currentTimeMillis() - t0);

            Proc r = exec(argv(p.plan().run(), dir), src, p.plan().run().env(), RUN_TIMEOUT_S);
            // macOS has two spellings of the temp dir (/private/var/... and /var/...): strip the longer one first
            String stderr = r.stderr.replace(prefix, "").replace(src.toAbsolutePath() + File.separator, "");
            return RunSupport.result(lang, p, mode, diags, r.stdout, stderr, r.timedOut, r.exit, RUN_TIMEOUT_S, prefix, t0);
        } catch (Exception e) {
            return RunSupport.fail("Runner error: " + e.getMessage(), t0);
        } finally {
            if (dir != null) RunSupport.deleteQuietly(dir);
        }
    }

    /** Java tools come from the JDK this app runs on; relative paths (../out/prog) become absolute. */
    private List<String> argv(Lang.Step step, Path dir) {
        List<String> a = new ArrayList<>(step.argv());
        String tool = a.get(0);
        if (tool.equals("javac") || tool.equals("java")) a.set(0, javaHome + File.separator + "bin" + File.separator + tool);
        else if (tool.startsWith("../")) a.set(0, dir.resolve(tool.substring(3)).toString());
        return a;
    }

    record Proc(int exit, String stdout, String stderr, boolean timedOut) {}

    static Proc exec(List<String> cmd, Path cwd, Map<String, String> env, long timeoutSec) throws IOException, InterruptedException {
        Path so = Files.createTempFile("cw-out", ".txt");
        Path se = Files.createTempFile("cw-err", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile())
                    .redirectOutput(so.toFile()).redirectError(se.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.environment().keySet().removeIf(k -> k.contains("KEY") || k.contains("SECRET") || k.contains("PASSWORD") || k.contains("TOKEN")
                    || k.startsWith("DB_") || k.startsWith("AI_") || k.startsWith("PYTHON") || k.startsWith("NODE_"));
            pb.environment().putAll(env);
            Process p = pb.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS); }
            return new Proc(done ? p.exitValue() : -1, RunSupport.read(so), RunSupport.read(se), !done);
        } finally {
            Files.deleteIfExists(so);
            Files.deleteIfExists(se);
        }
    }
}
