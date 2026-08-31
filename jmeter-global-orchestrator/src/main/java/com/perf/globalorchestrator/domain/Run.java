package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One row from {@code ORCH_RUN} plus its fleet-member children.
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
        List<RunFleetMember> fleetMembers,
        /**
         * The application's metrics group when the run launched. The run's rows
         * live in that group's fact table for good, so the readers resolve
         * through this — not through the application's current group, which an
         * operator may change later. Null on legacy rows (then the app's group).
         */
        String metricsGroupId,
        /**
         * UX-DYNAMICS T3 — the launch-time plugin snapshot ({@code
         * ORCH_RUN.PLUGINS}). Scale-up joiners fan out from this list, so a
         * registry delete never changes what a running fleet stages.
         */
        List<PluginRef> plugins,
        /**
         * The workflow execution and task this run was launched for, or null for
         * an ordinary launch. The task id is unique across {@code ORCH_RUN}: one
         * workflow task owns at most one run.
         */
        String workflowExecutionId,
        String workflowTaskId) {

    public Run {
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** Not launched by a workflow — the ordinary case. */
    public Run(String runId, String originRegion, String testPlanBlobId, String dataFilesBlobId, String application,
               String initiatedBy, RunState state, String stateReason, Instant createdAt, Instant startedAt,
               Instant completedAt, boolean saveResults, List<RunFleetMember> fleetMembers, String metricsGroupId,
               List<PluginRef> plugins) {
        this(runId, originRegion, testPlanBlobId, dataFilesBlobId, application, initiatedBy, state, stateReason,
                createdAt, startedAt, completedAt, saveResults, fleetMembers, metricsGroupId, plugins, null, null);
    }

    /** Pre-T3 callers (tests, legacy paths): no plugin snapshot. */
    public Run(String runId, String originRegion, String testPlanBlobId, String dataFilesBlobId, String application,
               String initiatedBy, RunState state, String stateReason, Instant createdAt, Instant startedAt,
               Instant completedAt, boolean saveResults, List<RunFleetMember> fleetMembers, String metricsGroupId) {
        this(runId, originRegion, testPlanBlobId, dataFilesBlobId, application, initiatedBy, state, stateReason,
                createdAt, startedAt, completedAt, saveResults, fleetMembers, metricsGroupId, List.of(), null, null);
    }

    /** Without a recorded group (legacy rows, tests): the readers fall back to the application's group. */
    public Run(String runId, String originRegion, String testPlanBlobId, String dataFilesBlobId, String application,
               String initiatedBy, RunState state, String stateReason, Instant createdAt, Instant startedAt,
               Instant completedAt, boolean saveResults, List<RunFleetMember> fleetMembers) {
        this(runId, originRegion, testPlanBlobId, dataFilesBlobId, application, initiatedBy, state, stateReason,
                createdAt, startedAt, completedAt, saveResults, fleetMembers, null, List.of(), null, null);
    }
}
