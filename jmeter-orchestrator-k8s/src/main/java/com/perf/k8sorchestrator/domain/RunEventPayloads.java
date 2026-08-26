package com.perf.k8sorchestrator.domain;

import java.util.List;

/**
 * The per-event-type {@code payload} contracts (decision #4:
 * type-safe records, not freeform maps). The service builds one of these,
 * Jackson serialises it to the JSONB {@code runEvent.payload} column. Shapes
 * are mirrored in the UI's TypeScript types.
 *
 * <p>No PII (decision #6): allocations carry region + count only — never the
 * per-node {@code -J} property maps, which can hold sensitive values. Blob IDs
 * and application names are non-sensitive identifiers and are fine.
 */
public final class RunEventPayloads {

    private RunEventPayloads() {}

    /** {@code RUN_START} — the launch allocation + how much of it was granted. */
    public record RunStart(
            String application,
            List<RegionCount> fleetAllocation,
            int requested,
            int granted) {}

    /** {@code SCALE_UP} — mid-test add-workers request + grant ledger. */
    public record ScaleUp(
            List<RegionCount> allocations,
            boolean bestEffort,
            int requested,
            int granted,
            boolean partial) {}

    /** {@code SCALE_DOWN} — mid-test drain request + what actually drained / was skipped. */
    public record ScaleDown(
            List<String> workerIds,
            List<RegionCount> allocations,
            List<String> drained,
            List<Skipped> skipped) {}

    /** {@code DRAIN_WORKER} — a single targeted worker drain (one explicit workerId). */
    public record DrainWorker(
            String workerId,
            List<String> drained,
            List<Skipped> skipped) {}

    /** {@code RUN_COMPLETED} / {@code RUN_FAILED} / {@code RUN_ABORTED} — how the run ended. */
    public record RunEnd(String finalState, String reason) {}

    /**
     * {@code ABORT} — an operator force-terminated a run. {@code aborted} lists
     * the workers that acknowledged the abort RPC; {@code skipped} lists those
     * that couldn't be reached (already gone / unreachable) — the run is rolled
     * to ABORTED regardless. {@code reason} is the operator's optional note.
     */
    public record Abort(String reason, List<String> aborted, List<Skipped> skipped) {}

    /** {@code DELETE} — an operator hid (soft-deleted) a run. {@code reason} is the optional note. */
    public record Delete(String reason) {}

    /** {@code WORKERS_RECYCLED} — workers of this run recycled by the hygiene policy. */
    public record WorkersRecycled(int count, List<String> pods, String reason) {}

    /** {@code RESULTS_SAVED} — one worker finished uploading its results to the Document Service. */
    public record ResultsSaved(String workerId, String target) {}

    /** A {@code (region, count)} pair — the PII-free projection of a fleet allocation entry. */
    public record RegionCount(String region, int count) {}

    /** A skipped drain target + the reason it was skipped (mirrors ScaleDownRunResponse.SkippedTarget). */
    public record Skipped(String target, String reason) {}
}
