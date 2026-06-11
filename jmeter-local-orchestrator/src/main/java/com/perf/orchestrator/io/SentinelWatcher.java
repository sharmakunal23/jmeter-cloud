package com.perf.orchestrator.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Watches for the sentinel file written by the JMeter wrapper script when the
 * test run completes.
 *
 * <h2>Why a sentinel file</h2>
 * The JTL file alone cannot signal test completion — silence in the file could
 * mean JMeter is between thread group iterations, not that the test is over.
 * The wrapper script writes a {@code .done} file containing the JMeter process
 * exit code immediately after JMeter exits, giving the orchestrator an authoritative
 * completion signal.
 *
 * <h2>Wrapper script contract</h2>
 * <pre>
 *   #!/bin/bash
 *   jmeter -n -t /test/plan.jmx -l /results/results.jtl
 *   echo $? > /results/.done
 * </pre>
 *
 * <h2>Caching behaviour</h2>
 * Once the sentinel is seen, {@link #isDone()} short-circuits to {@code true}
 * without further filesystem calls. At 100 ms polling this saves ~360,000
 * {@code Files.exists()} calls per hour after the test ends.
 *
 * <h2>Thread safety</h2>
 * {@code done} is {@code volatile} for future multi-threaded use. In the
 * current single-threaded poll loop no synchronisation is required.
 */
public final class SentinelWatcher {

    private static final Logger LOG = Logger.getLogger(SentinelWatcher.class.getName());

    private final Path sentinelPath;

    /** Cached once the sentinel is first observed — avoids repeated filesystem calls. */
    private volatile boolean done;

    public SentinelWatcher(Path sentinelPath) {
        this.sentinelPath = Objects.requireNonNull(sentinelPath, "sentinelPath cannot be null");
    }

    // -----------------------------------------------------------------------
    // Core API
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the sentinel file exists.
     *
     * <p>Result is cached after the first {@code true}: subsequent calls return
     * immediately without a filesystem stat call.
     */
    public boolean isDone() {
        if (done) return true;
        done = Files.exists(sentinelPath);
        if (done) {
            LOG.info(() -> "Sentinel file detected at " + sentinelPath +
                    " — transitioning to DRAINING");
        }
        return done;
    }

    /**
     * Reads the JMeter process exit code from the sentinel file content.
     *
     * <p>A non-zero exit code means JMeter reported test failures or an internal
     * error — useful for the run-completion event published to Kafka in Section 5.
     *
     * @return the exit code if the sentinel exists and contains a parseable integer;
     *         {@link Optional#empty()} if the file is absent, empty, or non-numeric
     */
    public Optional<Integer> readExitCode() {
        if (!Files.exists(sentinelPath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(sentinelPath).trim();
            if (content.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Integer.parseInt(content));
        } catch (IOException e) {
            LOG.warning(() -> "Could not read sentinel file: " + e.getMessage());
            return Optional.empty();
        } catch (NumberFormatException e) {
            LOG.warning(() -> "Sentinel file content is not a valid exit code: " + sentinelPath);
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if the exit code was read and is non-zero,
     * indicating JMeter reported failures.
     */
    public boolean testFailed() {
        return readExitCode().map(code -> code != 0).orElse(false);
    }
}
