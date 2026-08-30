package com.perf.globalorchestrator.region;

/** A regional orchestrator's {@code GET /api/v1/capabilities} answer. */
public record RegionCapabilities(
        String region,
        String namespace,
        String headlessService,
        String image,
        int localOrchestratorPort,
        String version,
        /** Workers the region's namespace quota still admits (Track 8); null = unbounded / not reported. */
        Integer workersFree) {

    public RegionCapabilities(String region, String namespace, String headlessService, String image,
                              int localOrchestratorPort, String version) {
        this(region, namespace, headlessService, image, localOrchestratorPort, version, null);
    }
}
