package com.perf.regionalorchestrator.provision;

/**
 * The one region this deployment serves, from {@code REGION}. Every Pod it
 * creates is labelled with it, and a {@code POST /api/v1/pods} naming another
 * region is refused — a mislabelled worker would be claimed by the wrong
 * capacity row.
 */
public record RegionalProperties(String region) {
    public RegionalProperties {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "REGION is required: the regional orchestrator serves exactly one region");
        }
    }
}
