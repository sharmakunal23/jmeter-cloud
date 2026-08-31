package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Launches a run from a saved template and waits for it to finish.
 *
 * <p><b>The node pins the fleet, the template supplies the plan.</b>
 * {@link #fleetAllocation()} is required and authoritative — capacity has to be
 * answerable without fetching every template, and the operator has to see the
 * number the workflow will actually reserve. A template's own allocation is
 * used verbatim only when the counts match and {@link #properties()} is empty;
 * otherwise the engine rebuilds the per-worker property snapshots from the
 * template's {@code globalProperties} overlaid with {@link #properties()},
 * because those snapshots are where a run's {@code -J} values live.
 *
 * @param maxDurationMinutes watchdog, 1..1440; the run is aborted and the task fails when it overruns
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoadTestNode(
        String id,
        String name,
        NodePosition position,
        JoinPolicy joinPolicy,
        String application,
        String templateBlobId,
        List<RegionCount> fleetAllocation,
        Map<String, String> properties,
        Boolean saveResults,
        LoadTestSuccess successWhen,
        int maxDurationMinutes) implements WorkflowNode {

    public LoadTestNode {
        joinPolicy         = joinPolicy      == null ? JoinPolicy.ALL : joinPolicy;
        fleetAllocation    = fleetAllocation == null ? List.of() : List.copyOf(fleetAllocation);
        properties         = properties      == null ? Map.of() : Map.copyOf(properties);
        successWhen        = successWhen     == null ? LoadTestSuccess.COMPLETED_ONLY : successWhen;
        maxDurationMinutes = maxDurationMinutes == 0 ? 120 : maxDurationMinutes;
    }

    @Override public NodeType type() { return NodeType.LOAD_TEST; }

    @Override @JsonIgnore public String applicationName() { return application; }

    /** Workers this node wants in {@code region}; 0 when it does not place any there. */
    public int workersIn(String region) {
        int n = 0;
        for (RegionCount rc : fleetAllocation) {
            if (rc.region().equals(region)) n += rc.count();
        }
        return n;
    }

    /** True when the template's own allocation can be used verbatim, per-worker overrides intact. */
    public boolean matchesTemplateFleet(Map<String, Integer> templateCounts) {
        if (!properties.isEmpty()) return false;
        Map<String, Integer> mine = new java.util.LinkedHashMap<>();
        for (RegionCount rc : fleetAllocation) mine.merge(rc.region(), rc.count(), Integer::sum);
        return mine.equals(templateCounts);
    }
}
