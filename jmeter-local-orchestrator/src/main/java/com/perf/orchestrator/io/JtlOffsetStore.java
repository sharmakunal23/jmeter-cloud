package com.perf.orchestrator.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Persists the JTL byte-offset to disk so the orchestrator can resume from the
 * correct position after a pod restart or container crash.
 *
 * <h2>Atomicity guarantee</h2>
 * Writes use a write-then-rename pattern:
 * <ol>
 *   <li>Write the new offset to {@code <stateFile>.tmp}</li>
 *   <li>Atomically rename {@code .tmp} over {@code stateFile}</li>
 * </ol>
 * On Linux, {@code rename(2)} is atomic when source and destination share the same
 * filesystem, which is guaranteed here since both files live in the same
 * {@code emptyDir} volume. A reader never observes a partial write.
 *
 * <h2>Crash recovery semantics</h2>
 * At-least-once delivery: if the orchestrator crashes between persisting the offset
 * and Kafka acknowledging the messages, rows since the last persisted offset
 * are re-processed and re-published. Kafka's idempotent producer (Section 5)
 * deduplicates these at the broker level.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Intended for use by the single poll-loop thread only.
 */
public final class JtlOffsetStore {

    private static final Logger LOG = Logger.getLogger(JtlOffsetStore.class.getName());

    private final Path stateFile;
    private final Path tmpFile;
    private final LongAdder saveFailures;

    /**
     * Constructs a store backed by the given file path with an internal
     * failure counter. Suitable for tests and any caller that does not need
     * to surface save-failure counts via Prometheus.
     *
     * @param stateFile path for the persisted offset file
     */
    public JtlOffsetStore(Path stateFile) {
        this(stateFile, new LongAdder());
    }

    /**
     * Constructs a store backed by the given file path. Save failures
     * (filesystem ENOSPC, EACCES, missing parent dir, etc.) increment the
     * supplied {@link LongAdder} so callers can expose the count via
     * {@code OrchestratorCounters#offsetSaveFailuresTotal} — silent
     * at-least-once amplification is otherwise invisible.
     *
     * <p>The parent directory must already exist.
     *
     * @param stateFile     path for the persisted offset file
     * @param saveFailures  process-level counter incremented once per
     *                      {@link #saveOffset} that swallows an IOException
     */
    public JtlOffsetStore(Path stateFile, LongAdder saveFailures) {
        Objects.requireNonNull(stateFile,    "stateFile cannot be null");
        Objects.requireNonNull(saveFailures, "saveFailures cannot be null");
        this.stateFile    = stateFile;
        this.tmpFile      = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        this.saveFailures = saveFailures;
    }

    // -----------------------------------------------------------------------
    // Core API
    // -----------------------------------------------------------------------

    /**
     * Loads the previously persisted byte offset.
     *
     * @return the saved offset, or {@code 0} if no state file exists (fresh start)
     *         or if the state file is unreadable/corrupt (safe fallback: reprocess
     *         from the header rather than crash)
     */
    public long loadOffset() {
        if (!Files.exists(stateFile)) {
            return 0L;
        }
        try {
            String content = Files.readString(stateFile).trim();
            if (content.isEmpty()) {
                LOG.warning(() -> "State file is empty, resetting to 0. File: " + stateFile);
                return 0L;
            }
            long offset = Long.parseLong(content);
            if (offset < 0) {
                LOG.warning(() -> "State file contains negative offset (" + offset +
                        "), resetting to 0. File: " + stateFile);
                return 0L;
            }
            return offset;
        } catch (IOException e) {
            LOG.warning(() -> "Cannot read state file (" + e.getMessage() +
                    "), starting from 0. File: " + stateFile);
            return 0L;
        } catch (NumberFormatException e) {
            LOG.warning(() -> "State file contains non-numeric content, resetting to 0. File: " + stateFile);
            return 0L;
        }
    }

    /**
     * Persists the current byte offset to disk using an atomic write.
     *
     * <p>Failures are logged at WARNING level but do not propagate — a failed
     * persist means at-most-one-flush-interval of rows will be re-processed
     * on the next restart, which is acceptable given Kafka's idempotent producer.
     *
     * @param offset the byte position in the JTL file after the last processed byte
     */
    public void saveOffset(long offset) {
        try {
            Files.writeString(tmpFile, String.valueOf(offset),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            Files.move(tmpFile, stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            saveFailures.increment();
            LOG.warning(() -> "Failed to persist byte offset " + offset +
                    " (" + e.getMessage() + "). Crash recovery may reprocess up to " +
                    "one flush interval of rows.");
        }
    }

    /**
     * Returns the cumulative count of save failures observed by this store
     * instance via the supplied {@link LongAdder}. When the no-arg
     * constructor was used, the counter is local to this instance.
     */
    public long getSaveFailureCount() {
        return saveFailures.sum();
    }

    /**
     * Removes both the state file and any leftover tmp file.
     * Called at the start of a fresh test run to ensure no stale offset is loaded.
     */
    public void clear() {
        silentDelete(stateFile);
        silentDelete(tmpFile);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void silentDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.fine(() -> "Could not delete " + path + ": " + e.getMessage());
        }
    }
}
