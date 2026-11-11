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
import java.util.stream.Stream;

/**
 * Production runner: compiles and runs learner code inside a throwaway Docker container per run.
 * The container has no network, a read-only root filesystem, no Linux capabilities, a non-root user,
 * and memory/CPU/process limits. Only the run's temp directory is mounted. Enable with RUNNER_MODE=docker.
 */
@Component
@ConditionalOnProperty(name = "codeway.runner.mode", havingValue = "docker")
public class DockerJavaRunner implements CodeRunner {
    private static final int RUN_TIMEOUT_S = 10;      // the learner's program, enforced inside the container
    private static final int CONTAINER_TIMEOUT_S = 60; // whole container (startup + javac + run), enforced from outside

    @Value("${codeway.runner.image:eclipse-temurin:21-jdk}") private String image;
    @Value("${codeway.runner.max-concurrent:2}") private int maxConcurrent;
    @Value("${codeway.runner.memory:512m}") private String memory;
    @Value("${codeway.runner.cpus:1.0}") private String cpus;
    @Value("${codeway.runner.docker:docker}") private String docker;

    private Semaphore slots;

    @PostConstruct
    void init() { slots = new Semaphore(Math.max(1, maxConcurrent), true); }

    @Override
    public RunResult run(Map<String, String> files, Map<String, String> tests, Mode mode) {
        long t0 = System.currentTimeMillis();
        boolean acquired = false;
        Path dir = null;
        try {
            acquired = slots.tryAcquire(45, TimeUnit.SECONDS);
            if (!acquired) return LocalJavaRunner.fail(List.of(new Diagnostic("", 0, "error", "The code runner is busy right now. Please try again in a moment.")), t0);

            dir = Files.createTempDirectory("codeway-run-");
            Path src = Files.createDirectories(dir.resolve("src"));
            Files.createDirectories(dir.resolve("out"));

            List<String> bad = new ArrayList<>();
            LocalJavaRunner.write(src, files, bad);
            LocalJavaRunner.write(src, Map.of(CheckHarness.PATH, CheckHarness.SOURCE), bad);
            int expected = 0;
            if (mode == Mode.TESTS && tests != null) {
                LocalJavaRunner.write(src, tests, bad);
                for (String body : tests.values()) expected += LocalJavaRunner.count(body, "Check.run(");
            }
            if (!bad.isEmpty())
                return LocalJavaRunner.fail(List.of(new Diagnostic(bad.get(0), 1, "error", "Invalid file name. Use letters, digits and underscores, ending in .java")), t0);

            List<String> sources = new ArrayList<>();
            final Path work = dir;
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(work.relativize(p).toString()));
            }
            Files.writeString(dir.resolve("sources.txt"), String.join("\n", sources) + "\n");

            String nonce = UUID.randomUUID().toString().replace("-", "");
            String mainClass = mode == Mode.TESTS ? "TestMain" : "Main";
            Files.writeString(dir.resolve("run.sh"), """
                    cd /work
                    javac -J-Xmx256m -J-XX:-UsePerfData --release 21 -Xlint:none -encoding UTF-8 -d out @sources.txt > compile.log 2>&1
                    echo $? > compile.code
                    if [ "$(cat compile.code)" = "0" ] && [ -f out/%1$s.class ]; then
                      timeout -s KILL %2$d java -Xmx256m -Xss2m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData \\
                        -Dcheck.nonce=%3$s -Dfile.encoding=UTF-8 -cp out %1$s > run.out 2> run.err < /dev/null
                      echo $? > run.code
                    fi
                    """.formatted(mainClass, RUN_TIMEOUT_S, nonce), StandardCharsets.UTF_8);
            openUp(dir); // the container runs as nobody (65534) and must be able to write into /work

            String name = "cw-" + UUID.randomUUID().toString().substring(0, 12);
            List<String> cmd = List.of(docker, "run", "--rm", "--name", name,
                    "--network", "none", "--read-only", "--tmpfs", "/tmp:rw,exec,size=64m",
                    "--memory", memory, "--memory-swap", memory, "--cpus", cpus, "--pids-limit", "128",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "65534:65534",
                    "-v", dir.toAbsolutePath() + ":/work", "-w", "/work", image, "sh", "/work/run.sh");
            LocalJavaRunner.Proc p = LocalJavaRunner.exec(cmd, dir, CONTAINER_TIMEOUT_S);
            if (p.timedOut()) LocalJavaRunner.exec(List.of(docker, "kill", name), dir, 10);

            Path compileCode = dir.resolve("compile.code");
            if (!Files.exists(compileCode)) {
                String why = p.timedOut() ? "The run took too long and was stopped." : ("The sandbox could not start: " + (p.stderr() + p.stdout()).trim());
                return LocalJavaRunner.fail(List.of(new Diagnostic("", 0, "error", why)), t0);
            }
            String compileLog = LocalJavaRunner.read(dir.resolve("compile.log")).replace("src/", "");
            if (!"0".equals(Files.readString(compileCode).trim())) {
                List<Diagnostic> diags = LocalJavaRunner.parseDiagnostics(compileLog);
                if (diags.isEmpty()) diags = List.of(new Diagnostic("", 0, "error", compileLog.trim()));
                return new RunResult(false, diags, "", "", List.of(), false, 1, System.currentTimeMillis() - t0);
            }
            List<Diagnostic> warnings = LocalJavaRunner.parseDiagnostics(compileLog);
            Path runCode = dir.resolve("run.code");
            if (!Files.exists(runCode)) {
                String msg = mode == Mode.TESTS ? "Test entry point is missing." : "No class named Main with a main method was found. Create Main.java to run your program.";
                return new RunResult(true, warnings, "", msg, List.of(), false, 1, System.currentTimeMillis() - t0);
            }
            int exit = Integer.parseInt(Files.readString(runCode).trim());
            boolean timedOut = exit == 137 || p.timedOut(); // SIGKILL from `timeout`
            String stdout = LocalJavaRunner.read(dir.resolve("run.out"));
            String stderr = LocalJavaRunner.read(dir.resolve("run.err"));

            List<TestResult> results = new ArrayList<>();
            StringBuilder clean = new StringBuilder();
            String marker = "@@T" + nonce + "\t";
            for (String line : stdout.split("\n", -1)) {
                if (line.startsWith(marker)) {
                    String[] f = line.substring(marker.length()).split("\t", 3);
                    if (f.length >= 2) results.add(new TestResult(f[0], Boolean.parseBoolean(f[1]), f.length > 2 ? f[2] : ""));
                } else clean.append(line).append('\n');
            }
            if (mode == Mode.TESTS)
                for (int i = results.size(); i < expected; i++)
                    results.add(new TestResult("Test " + (i + 1), false, timedOut ? "Did not finish: time limit exceeded" : "Did not run: the program stopped early"));
            if (timedOut) stderr = (stderr + "\nTime limit exceeded (" + RUN_TIMEOUT_S + "s)").trim();
            return new RunResult(true, warnings, clean.toString().stripTrailing(), stderr, results, timedOut, exit, System.currentTimeMillis() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LocalJavaRunner.fail(List.of(new Diagnostic("", 0, "error", "Runner interrupted")), t0);
        } catch (Exception e) {
            return LocalJavaRunner.fail(List.of(new Diagnostic("", 0, "error", "Runner error: " + e.getMessage())), t0);
        } finally {
            if (dir != null) LocalJavaRunner.deleteQuietly(dir);
            if (acquired) slots.release();
        }
    }

    private static void openUp(Path dir) throws java.io.IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator)
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwxrwxrwx" : "rw-rw-rw-"));
        }
    }
}
