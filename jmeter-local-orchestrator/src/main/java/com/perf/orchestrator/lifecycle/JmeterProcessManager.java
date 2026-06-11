package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.logs.LogSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production {@link JmeterLauncher}. Spawns the JMeter binary via
 * {@link ProcessBuilder} and returns a {@link JmeterProcess} wrapping the
 * underlying {@link Process}.
 *
 * <h2>Stdout / stderr drainer</h2>
 * Each stream is consumed by a dedicated daemon thread that mirrors lines
 * to the orchestrator log AND appends them to {@code stdoutLog} on disk.
 * Without an active reader the child can block on a full pipe buffer
 * (~64 KB), which would freeze JMeter mid-test.
 *
 * <h2>Error handling</h2>
 * {@link #launch(LaunchSpec)} throws {@link IOException} only on
 * spawn failure (binary not found, permission denied). Once spawned the
 * lifecycle is owned by the returned {@link JmeterProcess} — drain
 * threads keep running until they hit EOF.
 */
public final class JmeterProcessManager implements JmeterLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(JmeterProcessManager.class);

    /** Suffix appended to drainer thread names for grep-friendly traces. */
    private static final AtomicInteger SPAWN_COUNTER = new AtomicInteger();

    private final LogSink logSink;

    public JmeterProcessManager() {
        this(LogSink.NULL);
    }

    public JmeterProcessManager(LogSink logSink) {
        this.logSink = logSink == null ? LogSink.NULL : logSink;
    }

    @Override
    public JmeterProcess launch(LaunchSpec spec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.command())
                .directory(spec.workingDir().toFile())
                .redirectErrorStream(false);
        // Replace, not merge: the per-run env should be deterministic and
        // not pick up stray secrets from the orchestrator's own environment
        // unless explicitly forwarded.
        pb.environment().clear();
        pb.environment().putAll(spec.env());

        LOG.info("Spawning JMeter: {}", String.join(" ", spec.command()));
        Process process = pb.start();
        long pid = process.pid();
        int n = SPAWN_COUNTER.incrementAndGet();

        // Ensure parent dir exists for the stdout log we'll append to.
        Path log = spec.stdoutLog();
        Path logParent = log.getParent();
        if (logParent != null) Files.createDirectories(logParent);

        startDrainer("jmeter-stdout-" + n, process.getInputStream(), log, false, logSink);
        startDrainer("jmeter-stderr-" + n, process.getErrorStream(), log, true,  logSink);

        LOG.info("JMeter spawned with PID {}", pid);
        return new RealJmeterProcess(process, pid);
    }

    /**
     * Spawns one daemon thread to consume a single stream line-by-line.
     * Each line is mirrored to SLF4J (INFO for stdout, WARN for stderr),
     * appended to the on-disk JMeter log, and pushed into the orchestrator
     * {@link LogSink} (the in-memory ring buffer for {@code GET /api/v1/logs}).
     *
     * <p><b>Lifecycle invariant:</b> the drainer terminates when the child's
     * stdout/stderr pipe-end closes (normal exit, SIGTERM, or SIGKILL — the
     * kernel closes the write-end either way). {@code readLine()} returns
     * {@code null}, the loop exits, the try-with-resources closes the
     * reader, and the thread becomes eligible for GC. Sequential runs do
     * not accumulate threads; at most two drainers are live during an
     * active test.
     */
    private static void startDrainer(String name, InputStream src, Path logFile, boolean isStderr, LogSink sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(src, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isStderr) {
                        LOG.warn("[jmeter] {}", line);
                    } else {
                        LOG.info("[jmeter] {}", line);
                    }
                    appendLine(logFile, line);
                    try {
                        sink.append(line);
                    } catch (RuntimeException re) {
                        // Sinks should not throw, but a misbehaving one must
                        // not stop the drainer — that would deadlock JMeter
                        // on a full pipe.
                        LOG.debug("LogSink.append rejected line: {}", re.toString());
                    }
                }
            } catch (IOException io) {
                // Stream closing is normal at process exit.
                LOG.debug("{} drainer ended: {}", name, io.toString());
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private static void appendLine(Path logFile, String line) {
        try {
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException io) {
            // Don't let log write failures stop the drainer — the line is
            // already in SLF4J. One warning per failure is enough; spam is
            // worse than silence here.
            LOG.warn("Failed to append to JMeter log {}: {}", logFile, io.toString());
        }
    }

    /**
     * {@link JmeterProcess} backed by a real {@link Process}.
     *
     * <p>{@link #sigterm()} maps to {@link Process#destroy()} (SIGTERM on Unix);
     * {@link #sigkill()} maps to {@link Process#destroyForcibly()}. Both are
     * idempotent — issuing them against an already-exited process is a no-op.
     */
    static final class RealJmeterProcess implements JmeterProcess {
        private final Process process;
        private final long pid;

        RealJmeterProcess(Process process, long pid) {
            this.process = process;
            this.pid = pid;
        }

        @Override public long pid()        { return pid; }
        @Override public boolean isAlive() { return process.isAlive(); }

        @Override
        public void sigterm() {
            if (process.isAlive()) process.destroy();
        }

        @Override
        public void sigkill() {
            if (process.isAlive()) process.destroyForcibly();
        }

        @Override
        public Optional<Integer> awaitExit(Duration timeout) throws InterruptedException {
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return exited ? Optional.of(process.exitValue()) : Optional.empty();
        }
    }
}
