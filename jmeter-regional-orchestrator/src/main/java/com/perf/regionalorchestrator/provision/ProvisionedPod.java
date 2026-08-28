package com.perf.regionalorchestrator.provision;

import java.time.Instant;

/**
 * A Pod the provisioner currently sees in the cluster. {@link #status()} is the
 * Pod phase mapped to {@code running} / {@code created} / {@code exited} /
 * {@code unknown}.
 */
public record ProvisionedPod(
        String podName,
        String applicationId,
        String region,
        String status,
        Instant startedAt,
        String imageDigest) {
}
