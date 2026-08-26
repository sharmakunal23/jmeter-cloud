package com.perf.k8sorchestrator.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.k8sorchestrator.http.FleetAllocationEntry;

import java.util.List;
import java.util.Map;

/**
 * Server-side mirror of the UI's launcher-form snapshot
 * ({@code jmeter-cloud-ui/src/api/templates.ts#TemplateBody}), fetched from
 * document-service at fire time by {@link DocumentServiceClient}.
 *
 * <p>This is the same JSON a human saves with "Save as template"; the
 * scheduler maps it to a {@code StartRunRequest} so a scheduled run is
 * byte-for-byte the run a click would have produced. Unknown fields are
 * ignored so the template schema can grow without breaking the scheduler.
 *
 * <p>{@code labelFilter} is the comma-separated form the UI stores; the
 * scheduler splits it into {@code StartRunRequest.labelFilter}. {@code v} is
 * the template schema version (forward-compat only — not used yet).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateBody(
        int v,
        String application,
        String testPlanBlobId,
        String dataFilesBlobId,
        List<FleetAllocationEntry> fleetAllocation,
        Map<String, String> globalProperties,
        String labelFilter,
        Boolean saveResults,
        String initiatedBy) {

    public TemplateBody {
        fleetAllocation = fleetAllocation == null ? List.of() : List.copyOf(fleetAllocation);
    }

    /** Null-safe — a template with no explicit flag does not save results. */
    public boolean isSaveResults() {
        return Boolean.TRUE.equals(saveResults);
    }
}
