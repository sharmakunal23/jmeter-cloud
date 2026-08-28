package com.perf.regionalorchestrator.provision;

import java.time.Instant;

/**
 * Returned by {@link PodProvisioner#createAndStart(PodSpec)} — what the global
 * records on the pod row: the in-cluster {@link #baseUrl()}, the
 * {@link #imageDigest()} (the configured image reference, the value space the
 * recycler diffs against {@link PodProvisioner#currentImageDigest()}), and
 * {@link #createdAt()} from the API server's creation timestamp.
 */
public record ProvisionResult(
        String baseUrl,
        String imageDigest,
        Instant createdAt) {
}
