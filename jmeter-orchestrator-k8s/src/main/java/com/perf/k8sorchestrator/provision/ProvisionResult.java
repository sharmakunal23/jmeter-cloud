package com.perf.k8sorchestrator.provision;

import java.time.Instant;

/**
 * Returned by {@link PodProvisioner#createAndStart(PodSpec)} once a container
 * has been created and started. Carries the metadata the global-orchestrator
 * needs to record on the pod row:
 *
 * <ul>
 *   <li>{@link #baseUrl()} — what the global uses to reach the pod's REST API.</li>
 *   <li>{@link #imageDigest()} — sha256 ID of the image the container was created
 *       from. WORKER-HYGIENE Phase B/D uses this to detect "image rebuilt;
 *       pods are stale" via a diff against {@code docker image inspect
 *       jmeter-local-orchestrator:dev}. Null when the daemon can't surface it
 *       (cross-runtime portability — k8s provisioner may compute this
 *       differently).</li>
 *   <li>{@link #createdAt()} — wall-clock at container create. Used by Phase D's
 *       max-age check, anchored on creation rather than registration so a
 *       local-orch restart doesn't reset the age clock.</li>
 * </ul>
 */
public record ProvisionResult(
        String baseUrl,
        String imageDigest,
        Instant createdAt) {
}
