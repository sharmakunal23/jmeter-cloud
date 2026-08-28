package com.perf.globalorchestrator.provision;

import java.util.List;

/**
 * Lifecycle of the worker pods the control plane owns under
 * {@code PROVISIONING_MODE=DYNAMIC} — spun up on demand, bound to one
 * application, addressed by {@code (region, podName)} because each region's
 * pods live in a different cluster. {@link RegionalPodProvisioner} forwards
 * every call to that region's {@code jmeter-regional-orchestrator};
 * {@link StaticPodProvisioner} answers reads from the registry and refuses
 * writes.
 *
 * <p>Pod name == network hostname == registry key, allocated as
 * {@code {applicationName}-{region}-worker-{n}}. {@link #createAndStart} is
 * idempotent on the name: a live pod is reused, a terminal one recreated.
 */
public interface PodProvisioner {

    /** Creates the pod if missing, then starts it; returns what the registry records. */
    ProvisionResult createAndStart(PodSpec spec);

    /** Deletes the pod. No-op if missing. */
    void stopAndRemove(String region, String podName);

    /** Bare Pods have no stopped state — same as {@link #stopAndRemove}. */
    void stop(String region, String podName);

    /** No-op on a live pod, recreates a terminal one; throws {@link IllegalStateException} if missing. */
    void start(String region, String podName);

    /** Delete-and-recreate. Throws {@link IllegalStateException} if missing. */
    void restart(String region, String podName);

    boolean exists(String region, String podName);

    /** Process up (Pod phase Running) — not application health. */
    boolean isRunning(String region, String podName);

    /** Answering its readiness probe — the worker's HTTP is up. Defaults to {@link #isRunning}. */
    default boolean isReady(String region, String podName) {
        return isRunning(region, podName);
    }

    /**
     * Pods managed for an application, narrowed to a region or across all
     * regions when {@code region} is null. {@code applicationId} is required.
     */
    List<ProvisionedPod> listFor(String applicationId, String region);

    /**
     * Every pod the substrate manages in a region, whatever the application —
     * the reconciler's view after a registry wipe. Default: nothing.
     */
    default List<ProvisionedPod> listAll(String region) {
        return List.of();
    }

    /**
     * The image identity the recycler diffs {@code pod.imageDigest} against;
     * {@code null} means "unknown — skip the image-mismatch check this tick".
     */
    String currentImageDigest(String region);

    /** The URL the pod is reachable at inside its region, valid before the pod exists. */
    String baseUrlFor(String region, String podName);
}
