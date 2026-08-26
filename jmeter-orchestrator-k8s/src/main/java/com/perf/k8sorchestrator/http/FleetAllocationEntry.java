package com.perf.k8sorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One {@code (region, count)} entry inside
 * {@link StartRunRequest#fleetAllocation()}. An array (not a map) so JSON
 * ordering is stable and the shape can grow with optional fields like
 * future per-region priority or pod-class constraints.
 *
 * <p><b>Track G (Step 31)</b> added {@code perNodeProperties}: an
 * optional list of per-pod JMeter {@code -J} property maps. Index
 * {@code i} of the list applies to the i-th pod claimed in this region;
 * missing or null entries mean "no extra props for that pod." Length
 * is allowed to be ≤ {@code count}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetAllocationEntry(
        String region,
        int count,
        List<Map<String, String>> perNodeProperties) {

    public FleetAllocationEntry {
        perNodeProperties = perNodeProperties == null
                ? List.of()
                : List.copyOf(perNodeProperties);
    }

    /** Pre-Step-31 callers (just region + count). */
    public FleetAllocationEntry(String region, int count) {
        this(region, count, List.of());
    }

    /**
     * Properties for the i-th claimed pod in this region. Returns an
     * empty map if the index is out of range or the entry is null —
     * keeps the fan-out path branch-free.
     */
    public Map<String, String> propertiesFor(int podIndex) {
        if (podIndex < 0 || podIndex >= perNodeProperties.size()) return Map.of();
        Map<String, String> m = perNodeProperties.get(podIndex);
        return m == null ? Map.of() : m;
    }
}
