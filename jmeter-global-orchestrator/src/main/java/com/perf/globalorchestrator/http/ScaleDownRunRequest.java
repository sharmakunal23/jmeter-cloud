package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Body of {@code POST /api/v1/runs/{runId}/scaleDown} — drains workers
 * from a RUNNING run. MID-TEST-SCALING Phase B.
 *
 * <p>Two ways to specify which workers to drain (mutually exclusive — supply
 * exactly one):
 * <ul>
 *   <li>{@code workerIds} — explicit list of {@code podId}s to drain.</li>
 *   <li>{@code allocations} — per-region count; the service drains the
 *       N most-recently-joined workers in that region (youngest-first).</li>
 * </ul>
 *
 * <p>Drain is graceful: each target's local-orchestrator sends "Shutdown"
 * to JMeter's TCP port, in-flight samplers complete, the worker lands in
 * {@code DRAINED}. If the drain budget elapses without exit, the worker
 * lands {@code ABORTED} with reason {@code drainTimeoutExpired}.
 *
 * <p>Unknown fields are ignored so the wire schema can grow without
 * breaking older clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScaleDownRunRequest(
        List<String> workerIds,
        List<FleetAllocationEntry> allocations) {

    public ScaleDownRunRequest {
        workerIds   = workerIds   == null ? List.of() : List.copyOf(workerIds);
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    /** True if the caller provided exactly one of the two paths. */
    public boolean isExclusive() {
        return workerIds.isEmpty() ^ allocations.isEmpty();
    }
}
