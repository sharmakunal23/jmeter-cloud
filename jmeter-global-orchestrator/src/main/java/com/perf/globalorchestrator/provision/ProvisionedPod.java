package com.perf.globalorchestrator.provision;

import java.time.Instant;

/**
 * A Pod the provisioner currently sees in the cluster. Returned by
 * {@link PodProvisioner#listFor(String, String)} during reconciliation, not
 * derived from the {@code globalOrchestrator.pod} registry table.
 *
 * <p>{@link #status()} is the Pod phase mapped to {@code running} /
 * {@code exited} / {@code created}. The reconciler maps this to whether the
 * registry row should exist or not.
 */
public record ProvisionedPod(
        String podName,
        String groupId,
        String region,
        String status,
        Instant startedAt,
        String imageDigest) {

    /** Back-compat factory for call sites that don't surface the image digest. */
    public ProvisionedPod(String podName, String groupId, String region,
                          String status, Instant startedAt) {
        this(podName, groupId, region, status, startedAt, null);
    }
}
