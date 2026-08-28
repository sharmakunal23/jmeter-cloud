package com.perf.globalorchestrator.region;

/** A regional orchestrator's {@code GET /api/v1/capabilities} answer. */
public record RegionCapabilities(
        String region,
        String namespace,
        String headlessService,
        String image,
        int localOrchestratorPort,
        String version) {
}
