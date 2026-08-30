package com.perf.globalorchestrator.provision;

/**
 * Inputs needed to create one worker Pod.
 *
 * <p>{@link #podName()} doubles as the Pod name and its network hostname, so
 * the global-orchestrator can reach it at
 * {@code http://{podName}.{headlessService}:8080} once it's running. The naming
 * convention is {@code {groupId}-{region}-worker-{n}} (see {@code PodNameAllocator}).
 *
 * <p>{@link #groupId()} reaches the container as the {@code GROUP_ID} env var
 * and the {@code com.perf.jmeterCloud.groupId} label, so the provisioner can
 * list/reconcile a group's Pods without going through the registry.
 */
public record PodSpec(
        String podName,
        String groupId,
        String region) {

    public PodSpec {
        if (podName == null || podName.isBlank()) {
            throw new IllegalArgumentException("podName is required");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region is required");
        }
    }
}
