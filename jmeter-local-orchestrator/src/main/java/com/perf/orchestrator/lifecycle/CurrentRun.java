package com.perf.orchestrator.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-instance state of the current (or last-completed) test run.
 *
 * <h2>Thread safety</h2>
 * Every public mutator and {@link #snapshot()} is synchronised on the
 * instance. Mutators write to disk inside the lock so the on-disk JSON
 * always reflects the in-memory state — at the cost of one fsync per
 * transition. State transitions are infrequent (≪ 1/s) so the cost is
 * irrelevant; correctness is not.
 *
 * <h2>Persistence</h2>
 * Every transition writes {@code ${RUN_STATE_FILE}} via
 * {@code tmp + atomic-rename}. On a crash mid-write the previous file
 * survives intact.
 *
 * <h2>Restart recovery</h2>
 * {@link #load(Path, Clock)} reads any pre-existing snapshot. If the
 * snapshot reports a non-terminal state, the orchestrator must mark it
 * {@link TestState#FAILED} (with reason {@code "orchestrator_restart"})
 * — the JMeter child is gone, so the run cannot be resumed. That bookkeeping
 * lives in {@link TestRunManager} so this class stays a pure state holder.
 */
public final class CurrentRun {

    private static final Logger LOG = LoggerFactory.getLogger(CurrentRun.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path stateFile;
    private final Clock clock;

    // -- Mutable state, all guarded by the monitor of `this` -----------------

    private TestState state = TestState.IDLE;
    private String runId;
    private String region;
    private Instant startedAt;
    private Instant completedAt;
    private Long jmeterPid;
    private Integer exitCode;
    private String failureReason;
    private long rowsIngested;
    private long windowsPublished;
    private long kafkaSendErrors;
    private Long lastKafkaAckMs;
    private String uploadState = "SKIPPED";
    private String uploadTarget;
    private String uploadFailureReason;

    private CurrentRun(Path stateFile, Clock clock) {
        this.stateFile = stateFile;
        this.clock = clock;
    }

    public static CurrentRun load(Path stateFile, Clock clock) {
        Objects.requireNonNull(stateFile, "stateFile");
        Objects.requireNonNull(clock, "clock");
        CurrentRun run = new CurrentRun(stateFile, clock);
        if (Files.exists(stateFile)) {
            try {
                JsonNode n = MAPPER.readTree(stateFile.toFile());
                run.state            = TestState.valueOf(n.path("state").asText("IDLE"));
                run.runId            = nullableText(n, "runId");
                run.region           = nullableText(n, "region");
                run.startedAt        = parseInstantOrNull(n, "startedAt");
                run.completedAt      = parseInstantOrNull(n, "completedAt");
                run.jmeterPid        = n.has("jmeterPid")    && !n.get("jmeterPid").isNull()    ? n.get("jmeterPid").asLong()    : null;
                run.exitCode         = n.has("exitCode")     && !n.get("exitCode").isNull()     ? n.get("exitCode").asInt()      : null;
                run.failureReason    = nullableText(n, "failureReason");
                run.rowsIngested     = n.path("rowsIngested").asLong(0L);
                run.windowsPublished = n.path("windowsPublished").asLong(0L);
                run.kafkaSendErrors  = n.path("kafkaSendErrors").asLong(0L);
                run.lastKafkaAckMs   = n.has("lastKafkaAckMs") && !n.get("lastKafkaAckMs").isNull() ? n.get("lastKafkaAckMs").asLong() : null;
                run.uploadState         = n.path("uploadState").asText("SKIPPED");
                run.uploadTarget        = nullableText(n, "uploadTarget");
                run.uploadFailureReason = nullableText(n, "uploadFailureReason");
            } catch (IOException | IllegalArgumentException e) {
                // Corrupt snapshot — log loudly and start clean rather than
                // refuse to boot. The previous run is unrecoverable in any
                // case (orchestrator crashed; JMeter is gone).
                LOG.warn("Could not parse {} — discarding previous run state: {}",
                        stateFile, e.toString());
            }
        }
        return run;
    }

    // -----------------------------------------------------------------------
    // Mutators — every successful return implies the snapshot has been persisted
    // -----------------------------------------------------------------------

    /**
     * Begins a new run. Resets every counter and timestamp; transitions to
     * {@link TestState#PREPARING}. Caller must already have ensured no
     * other run is active.
     */
    public synchronized void beginRun(String runId, String region) {
        this.state            = TestState.PREPARING;
        this.runId            = runId;
        this.region           = region;
        this.startedAt        = Instant.now(clock);
        this.completedAt      = null;
        this.jmeterPid        = null;
        this.exitCode         = null;
        this.failureReason    = null;
        this.rowsIngested     = 0;
        this.windowsPublished = 0;
        this.kafkaSendErrors  = 0;
        this.lastKafkaAckMs   = null;
        this.uploadState         = "SKIPPED";
        this.uploadTarget        = null;
        this.uploadFailureReason = null;
        persist();
    }

    // -- Upload-state transitions (driven by ResultUploader, step 9) ---------

    public synchronized void uploadPending() {
        this.uploadState = "PENDING";
        this.uploadTarget = null;
        this.uploadFailureReason = null;
        persist();
    }

    public synchronized void uploadInProgress() {
        this.uploadState = "UPLOADING";
        persist();
    }

    public synchronized void uploadSucceeded(String target) {
        this.uploadState = "UPLOADED";
        this.uploadTarget = target;
        this.uploadFailureReason = null;
        persist();
    }

    public synchronized void uploadFailed(String reason) {
        this.uploadState = "FAILED";
        this.uploadFailureReason = reason;
        persist();
    }

    public synchronized String uploadState() {
        return uploadState;
    }

    public synchronized void transitionTo(TestState newState) {
        this.state = newState;
        if (newState.isTerminal()) {
            this.completedAt = Instant.now(clock);
        }
        persist();
    }

    public synchronized void recordJmeterPid(long pid) {
        this.jmeterPid = pid;
        persist();
    }

    public synchronized void recordExit(int exitCode) {
        this.exitCode = exitCode;
        persist();
    }

    public synchronized void recordFailure(String reason) {
        this.state = TestState.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now(clock);
        persist();
    }

    public synchronized void recordAborted(String reason) {
        this.state = TestState.ABORTED;
        this.failureReason = reason;
        this.completedAt = Instant.now(clock);
        persist();
    }

    /**
     * MID-TEST-SCALING Phase B — graceful drain reached its terminal state.
     * Called when JMeter exited cleanly after the operator triggered drain
     * via {@code POST /api/v1/test/drain}. Distinct from {@link #recordAborted}
     * (force-stop) and the {@link TestState#COMPLETED} natural exit.
     */
    public synchronized void recordDrained() {
        this.state = TestState.DRAINED;
        this.completedAt = Instant.now(clock);
        persist();
    }

    public synchronized void updateMetrics(long rowsIngested, long windowsPublished,
                                           long kafkaSendErrors, Long lastKafkaAckMs) {
        this.rowsIngested     = rowsIngested;
        this.windowsPublished = windowsPublished;
        this.kafkaSendErrors  = kafkaSendErrors;
        this.lastKafkaAckMs   = lastKafkaAckMs;
        // No persist — counters update during RUNNING and persisting on every
        // change would dominate disk I/O. The next state transition or an
        // explicit flush picks them up.
    }

    /** Persists current counters explicitly — used between RUNNING samples. */
    public synchronized void flushMetrics() {
        persist();
    }

    // -----------------------------------------------------------------------
    // Read-only views
    // -----------------------------------------------------------------------

    public synchronized boolean isActive()   { return state.isActive(); }
    public synchronized boolean isTerminal() { return state.isTerminal(); }
    public synchronized TestState state()    { return state; }

    /** Returns an immutable snapshot suitable for serialising to {@code GET /api/v1/test}. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                state, runId, region, startedAt, completedAt,
                jmeterPid, exitCode, failureReason,
                rowsIngested, windowsPublished, kafkaSendErrors, lastKafkaAckMs,
                uploadState, uploadTarget, uploadFailureReason);
    }

    /**
     * Returns {@link Optional#empty()} when no run has ever been initialised
     * (state IDLE and runId is null). The {@code GET /api/v1/test} controller
     * uses this to decide between 200 and 404.
     */
    public synchronized Optional<Snapshot> snapshotIfPresent() {
        if (state == TestState.IDLE && runId == null) return Optional.empty();
        return Optional.of(snapshot());
    }

    public record Snapshot(
            TestState state,
            String runId,
            String region,
            Instant startedAt,
            Instant completedAt,
            Long jmeterPid,
            Integer exitCode,
            String failureReason,
            long rowsIngested,
            long windowsPublished,
            long kafkaSendErrors,
            Long lastKafkaAckMs,
            String uploadState,
            String uploadTarget,
            String uploadFailureReason) {
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private void persist() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put   ("state",            state.name());
        if (runId != null)       node.put("runId", runId);
        if (region != null)      node.put("region", region);
        if (startedAt != null)   node.put("startedAt", startedAt.toString());
        if (completedAt != null) node.put("completedAt", completedAt.toString());
        if (jmeterPid != null)   node.put("jmeterPid", jmeterPid);
        if (exitCode != null)    node.put("exitCode", exitCode);
        if (failureReason != null) node.put("failureReason", failureReason);
        node.put("rowsIngested",     rowsIngested);
        node.put("windowsPublished", windowsPublished);
        node.put("kafkaSendErrors",  kafkaSendErrors);
        if (lastKafkaAckMs != null) node.put("lastKafkaAckMs", lastKafkaAckMs);
        node.put("uploadState", uploadState);
        if (uploadTarget != null) node.put("uploadTarget", uploadTarget);
        if (uploadFailureReason != null) node.put("uploadFailureReason", uploadFailureReason);

        try {
            byte[] payload = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
            Path parent = stateFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.write(tmp, payload,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, stateFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ame) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException io) {
            // Best-effort persistence — a write failure must not crash the
            // run. The next transition will retry. Surfaced loudly.
            LOG.warn("Failed to persist run state to {}: {}", stateFile, io.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String nullableText(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
    }

    private static Instant parseInstantOrNull(JsonNode n, String field) {
        if (!n.has(field) || n.get(field).isNull()) return null;
        try { return Instant.parse(n.get(field).asText()); }
        catch (Exception e) { return null; }
    }
}
