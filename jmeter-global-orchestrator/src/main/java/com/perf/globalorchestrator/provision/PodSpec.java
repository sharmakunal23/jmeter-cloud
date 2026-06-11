package com.perf.globalorchestrator.provision;

/**
 * Inputs needed to create one local-orchestrator container.
 *
 * <p>{@link #podName()} doubles as both the Docker container name and the
 * pod's network hostname, so the global-orchestrator can reach the pod at
 * {@code http://{podName}:8080} once it's running. The naming convention
 * is {@code {applicationName}-{region}-worker-{n}} (see
 * {@code PodNameAllocator} in Phase 2).
 *
 * <p>{@link #applicationId()} and {@link #applicationName()} are passed
 * to the container as env vars and labels:
 * <ul>
 *   <li>env {@code APPLICATION_ID} — the local-orch's {@code PodRegistrar}
 *       includes this in its {@code POST /api/v1/registerPod} body so
 *       {@code globalOrchestrator.pod.applicationId} is populated.</li>
 *   <li>label {@code com.perf.jmeterCloud.applicationId} — lets the
 *       provisioner list/reconcile containers by app without going
 *       through the registry.</li>
 * </ul>
 */
public record PodSpec(
        String podName,
        String applicationId,
        String applicationName,
        String region) {

    public PodSpec {
        if (podName == null || podName.isBlank()) {
            throw new IllegalArgumentException("podName is required");
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
