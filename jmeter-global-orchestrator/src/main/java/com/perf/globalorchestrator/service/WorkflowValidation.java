package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * What the builder needs to show beside a canvas: what is wrong, what is
 * risky, and how the graph's peak worker demand compares with the group's
 * reservation in each cluster.
 *
 * <p>{@code errors} block a save; {@code warnings} do not — capacity can change
 * after a workflow is drafted, so an over-subscribed graph is saveable and
 * refused at launch instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowValidation(boolean valid, List<String> errors, List<String> warnings,
                                 List<RegionDemand> capacity) {

    /**
     * One cluster's picture.
     *
     * @param peakWorkers the most this graph can want at once — the
     *                    maximum-weight antichain, not a per-task maximum
     * @param tasks       the task names that make up that peak, so a surprising
     *                    number explains itself
     * @param reserved    the group's reservation in this cluster; 0 when it has none
     */
    public record RegionDemand(String region, int peakWorkers, List<String> tasks, int reserved, boolean fits) {}

    public static WorkflowValidation ok(List<String> warnings, List<RegionDemand> capacity) {
        return new WorkflowValidation(true, List.of(), warnings, capacity);
    }

    public static WorkflowValidation invalid(List<String> errors) {
        return new WorkflowValidation(false, errors, List.of(), List.of());
    }

    /** Clusters the graph asks for more than the group reserved. */
    public List<RegionDemand> overSubscribed() {
        return capacity == null ? List.of() : capacity.stream().filter(d -> !d.fits()).toList();
    }

    /** Peak workers keyed by cluster — the shape the UI charts. */
    public Map<String, Integer> peakByRegion() {
        if (capacity == null) return Map.of();
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (RegionDemand d : capacity) out.put(d.region(), d.peakWorkers());
        return out;
    }
}
