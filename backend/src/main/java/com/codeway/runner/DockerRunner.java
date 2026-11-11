package com.codeway.runner;

import com.codeway.runner.RunResult.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Production runner: checks and runs learner code inside a throwaway Docker container per run, for every practice
 * language (the sandbox image has the JDK, Python, Node and g++). The container has no network, a read-only root
 * filesystem, no Linux capabilities, a non-root user, and memory/CPU/process limits. Only the run's temp directory
 * is mounted. Enable with RUNNER_MODE=docker.
 */
@Component
@ConditionalOnProperty(name = "codeway.runner.mode", havingValue = "docker")
public class DockerRunner implements CodeRunner {
    private static final int RUN_TIMEOUT_S = 10;       // the learner's program, enforced inside the container
    private static final int CHECK_TIMEOUT_S = 40;     // compile / syntax check, enforced inside the container
    private static final int CONTAINER_TIMEOUT_S = 75; // whole container, enforced from outside

    @Value("${codeway.runner.image:codeway-sandbox:1}") private String image;
    @Value("${codeway.runner.max-concurrent:2}") private int maxConcurrent;
    @Value("${codeway.runner.memory:512m}") private String memory;
    @Value("${codeway.runner.cpus:1.0}") private String cpus;
    @Value("${codeway.runner.docker:docker}") private String docker;

    private Semaphore slots;

    @PostConstruct
    void init() { slots = new Semaphore(Math.max(1, maxConcurrent), true); }

    @Override
    public RunResult run(String language, Map<String, String> files, Map<String, String> tests, Mode mode) {
        long t0 = System.currentTimeMillis();
        Lang lang = Lang.of(language);
        boolean acquired = false;
        Path dir = null;
        try {
            acquired = slots.tryAcquire(45, TimeUnit.SECONDS);
            if (!acquired) return RunSupport.fail("The code runner is busy right now. Please try again in a moment.", t0);

            dir = Files.createTempDirectory("codeway-run-");
            RunSupport.Prepared p = RunSupport.prepare(dir, lang, files, tests, mode);
            if (p.badFile() != null) return RunSupport.fail(List.of(new Diagnostic(p.badFile(), 1, "error", lang.badNameMessage())), t0);
            Lang.Plan plan = p.plan();
            Files.writeString(dir.resolve("run.sh"), script(plan), StandardCharsets.UTF_8);
            openUp(dir); // the container runs as nobody (65534) and must be able to write into /work

            String name = "cw-" + UUID.randomUUID().toString().substring(0, 12);
            List<String> cmd = List.of(docker, "run", "--rm", "--name", name,
                    "--network", "none", "--read-only", "--tmpfs", "/tmp:rw,exec,size=64m",
                    "--memory", memory, "--memory-swap", memory, "--cpus", cpus, "--pids-limit", "128",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "65534:65534",
                    "-e", "HOME=/tmp", "-v", dir.toAbsolutePath() + ":/work", "-w", "/work/src", image, "sh", "/work/run.sh");
            LocalRunner.Proc proc = LocalRunner.exec(cmd, dir, Map.of(), CONTAINER_TIMEOUT_S);
            if (proc.timedOut()) LocalRunner.exec(List.of(docker, "kill", name), dir, Map.of(), 10);

            Path checkCode = dir.resolve("check.code");
            if (!Files.exists(checkCode)) {
                String why = proc.timedOut() ? "The run took too long and was stopped." : ("The sandbox could not start: " + (proc.stderr() + proc.stdout()).trim());
                return RunSupport.fail(why, t0);
            }
            String log = RunSupport.read(dir.resolve("check.log")).replace("/work/src/", "");
            int check = Integer.parseInt(Files.readString(checkCode).trim());
            List<Diagnostic> diags = RunSupport.parseDiagnostics(lang, log);
            if (check != 0) {
                if (diags.isEmpty()) diags = List.of(new Diagnostic("", 0, "error", check == 137 ? "Compiling took too long." : log.trim()));
                return new RunResult(false, diags, "", "", List.of(), check == 137, check, System.currentTimeMillis() - t0);
            }
            Path runCode = dir.resolve("run.code");
            if (!Files.exists(runCode))
                return new RunResult(true, diags, "", plan.noEntryMsg(), List.of(), false, 1, System.currentTimeMillis() - t0);
            int exit = Integer.parseInt(Files.readString(runCode).trim());
            boolean timedOut = exit == 137 || proc.timedOut(); // SIGKILL from `timeout`
            return RunSupport.result(lang, p, mode, diags, RunSupport.read(dir.resolve("run.out")), RunSupport.read(dir.resolve("run.err")),
                    timedOut, exit, RUN_TIMEOUT_S, "/work/src/", t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RunSupport.fail("Runner interrupted", t0);
        } catch (Exception e) {
            return RunSupport.fail("Runner error: " + e.getMessage(), t0);
        } finally {
            if (dir != null) RunSupport.deleteQuietly(dir);
            if (acquired) slots.release();
        }
    }

    /** The container's script: check (compile / syntax), then run the entry point if it exists. */
    static String script(Lang.Plan plan) {
        StringBuilder s = new StringBuilder("cd /work/src\n");
        if (plan.check() != null)
            s.append("timeout -s KILL ").append(CHECK_TIMEOUT_S).append(' ').append(cmd(plan.check())).append(" > ../check.log 2>&1\n");
        else s.append("true\n");
        s.append("echo $? > ../check.code\n");
        s.append("if [ \"$(cat ../check.code)\" = \"0\" ] && [ -e ../").append(plan.entry()).append(" ]; then\n");
        s.append("  ").append(env(plan.run())).append("timeout -s KILL ").append(RUN_TIMEOUT_S).append(' ').append(cmd(plan.run()))
                .append(" > ../run.out 2> ../run.err < /dev/null\n");
        s.append("  echo $? > ../run.code\nfi\n");
        return s.toString();
    }

    private static String cmd(Lang.Step step) { return step.argv().stream().map(DockerRunner::quote).collect(Collectors.joining(" ")); }

    private static String env(Lang.Step step) {
        return step.env().entrySet().stream().map(e -> e.getKey() + "=" + quote(e.getValue()) + " ").collect(Collectors.joining());
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static void openUp(Path dir) throws java.io.IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator)
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwxrwxrwx" : "rw-rw-rw-"));
        }
    }
}
