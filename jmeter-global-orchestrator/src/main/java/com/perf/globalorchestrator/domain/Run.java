package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One row from {@code globalOrchestrator.run} plus its fleet-member children.
 *
 * <p>UI-D3 added {@code application} — the application this run was
 * launched against. Supplied by the launcher form (gated behind the
 * application picker) and persisted on the row so the
 * {@code /applications/:appName} surface can filter without a join
 * through the testPlan blob's tags. {@code null} for legacy rows that
 * predate the V4 migration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Run(
        String runId,
        String originRegion,
        String testPlanBlobId,
        String dataFilesBlobId,
        String application,
        String initiatedBy,
        RunState state,
        String stateReason,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        /** Save Results: true → each worker uploads its JTL to the Document Service on COMPLETE. */
        boolean saveResults,
        List<RunFleetMember> fleetMembers) {
}
