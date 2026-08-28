package com.perf.globalorchestrator.provision;

import java.time.Instant;

/**
 * Returned by {@link PodProvisioner#createAndStart(PodSpec)} once a Pod has
 * been created and started. Carries the metadata the global-orchestrator
 * records on the pod row:
 *
 * <ul>
 *   <li>{@link #baseUrl()} — what the global uses to reach the pod's REST API.</li>
 *   <li>{@link #imageDigest()} — the image identity the Pod was created from
 *       (the configured image reference on Kubernetes). The recycler diffs it
 *       against {@link PodProvisioner#currentImageDigest()} to detect "image
 *       rolled out; pods are stale". Null when the provisioner can't surface
 *       it.</li>
 *   <li>{@link #createdAt()} — wall-clock at Pod create. Used by the recycler's
 *       max-age check, anchored on creation rather than registration so a
 *       local-orch restart doesn't reset the age clock.</li>
 * </ul>
 */
public record ProvisionResult(
        String baseUrl,
        String imageDigest,
        Instant createdAt) {
}
