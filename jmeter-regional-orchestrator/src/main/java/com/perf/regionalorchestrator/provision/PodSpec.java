package com.perf.regionalorchestrator.provision;

/**
 * Inputs needed to create one worker Pod — the body of {@code POST /api/v1/pods}.
 *
 * <p>{@link #podName()} doubles as the Pod name and its network hostname
 * ({@code http://{podName}.{headlessService}:8080}); the global allocates it as
 * {@code {applicationName}-{region}-{n}}. {@link #applicationId()} is stamped
 * as the {@code APPLICATION_ID} env var (the worker reports it on register) and
 * as a label (so the global can list by app without the registry).
 */
public record PodSpec(
        String podName,
        String applicationId,
        String applicationName,
        String region) {

    public PodSpec {
        if (!PodNames.isValid(podName)) {
            throw new IllegalArgumentException("podName must be a DNS-1123 label; got '" + podName + "'");
        }
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId is required");
        }
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region is required");
        }
    }
}
