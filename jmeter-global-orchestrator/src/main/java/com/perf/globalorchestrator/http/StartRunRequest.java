package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Body of {@code POST /api/v1/runs} — matches the {@code StartRunRequest}
 * schema in {@code api/openapi.yaml}.
 *
 * <p>Unknown fields are ignored so the wire schema can grow without
 * breaking older clients.
 *
 * <p>{@link #fleetAllocation()} is the Track F multi-region shape. If
 * present, it wins over the legacy {@link #fleetSize()} + single-element
 * {@link #regions()}. When {@code fleetAllocation} is absent and
 * {@code regions} has exactly one entry, the legacy fields are folded
 * into a single allocation entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartRunRequest(
        String testPlanBlobId,
        String dataFilesBlobId,
        /**
         * UI-D3 — application this run is launched against. The launcher's
         * application gate ensures it's always set when submitted from the
         * UI; backend / API callers may omit it (NULL persisted, run still
         * runs but won't appear under any application filter).
         */
        String application,
        int fleetSize,
        List<String> regions,
        List<FleetAllocationEntry> fleetAllocation,
        String initiatedBy,
        /**
         * When true, a shortfall in the claim
         * phase triggers an on-the-fly spin to fill the gap rather than
         * a 503 INSUFFICIENT_CAPACITY. Subject to the per-region
         * {@code groupCapacity.maxAvailable} ceiling: if the
         * spin would exceed it, a 409 CAPACITY_EXCEEDED is returned
         * instead. Default false (today's behavior). The UI's
         * NewRunPage sets this to true only after the operator confirms
         * the shortfall dialog.
         */
        Boolean spinShortfall,
        /**
         * When true, each worker zips + uploads its JTL to the Document
         * Service on a clean COMPLETE (tagged with this run's application +
         * runId + workerId), so the operator can download all results for the
         * run in one zip. Threads to the fan-out body as
         * {@code autoUploadResults=true}; persisted on the run row so the UI
         * shows a "Download results" button. Default false.
         */
        Boolean saveResults,
        /**
         * UX-DYNAMICS T3 — global-library plugin ids to stage onto every
         * worker of this run. Resolved against {@code ORCH_PLUGIN} at launch
         * (unknown id → 400) and snapshotted onto the run row, so later
         * registry deletes never affect this run or its scale-up joiners.
         */
        List<String> pluginIds) {

    public StartRunRequest {
        regions         = regions         == null ? List.of() : List.copyOf(regions);
        fleetAllocation = fleetAllocation == null ? List.of() : List.copyOf(fleetAllocation);
        pluginIds       = pluginIds       == null ? List.of() : List.copyOf(pluginIds);
    }

    /** Null-safe accessor — null in the wire body is treated as false. */
    public boolean isSpinShortfall() {
        return Boolean.TRUE.equals(spinShortfall);
    }

    /** Null-safe accessor — null in the wire body is treated as false. */
    public boolean isSaveResults() {
        return Boolean.TRUE.equals(saveResults);
    }
}
