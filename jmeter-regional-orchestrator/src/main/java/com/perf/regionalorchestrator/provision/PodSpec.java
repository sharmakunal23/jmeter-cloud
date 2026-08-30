package com.perf.regionalorchestrator.provision;

/**
 * Inputs needed to create one worker Pod — the body of {@code POST /api/v1/pods}.
 *
 * <p>{@link #podName()} doubles as the Pod name and its network hostname
 * ({@code http://{podName}.{headlessService}:8080}); the global allocates it as
 * {@code {groupId}-{region}-{n}}. {@link #groupId()} is the application group
 * whose pool the worker joins — stamped as the {@code GROUP_ID} env var (the
 * worker reports it on register) and as a label (so the global can list a
 * group's workers without the registry).
 */
public record PodSpec(
        String podName,
        String groupId,
        String region) {

    public PodSpec {
        if (!PodNames.isValid(podName)) {
            throw new IllegalArgumentException("podName must be a DNS-1123 label; got '" + podName + "'");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region is required");
        }
    }
}
