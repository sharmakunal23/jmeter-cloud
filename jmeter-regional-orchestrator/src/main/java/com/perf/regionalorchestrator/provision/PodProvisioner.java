package com.perf.regionalorchestrator.provision;

import java.util.List;
import java.util.Optional;

/**
 * Lifecycle of the worker Pods in this cluster, addressed by pod name. Every
 * operation is idempotent on the name: {@link #createAndStart} reuses a live
 * pod and recreates a terminal one, and the delete-shaped calls no-op when the
 * pod is already gone.
 */
public interface PodProvisioner {

    ProvisionResult createAndStart(PodSpec spec);

    /** Deletes the pod. No-op if missing. */
    void stopAndRemove(String podName);

    /** Bare Pods have no stopped state — same as {@link #stopAndRemove}. */
    void stop(String podName);

    /** No-op on a live pod, recreates a terminal one, throws {@link IllegalStateException} if missing. */
    void start(String podName);

    /** Delete-and-recreate from the pod's own labels. Throws {@link IllegalStateException} if missing. */
    void restart(String podName);

    boolean exists(String podName);

    boolean isRunning(String podName);

    /**
     * Pods this provisioner manages for an application group, optionally
     * narrowed to a region (null = all).
     */
    List<ProvisionedPod> listFor(String groupId, String region);

    /** Every managed Pod with its kubelet-reported liveness — the hub's LOST source. */
    List<WorkerState> listWorkers();

    Optional<WorkerState> workerState(String podName);

    /** The container's stdout tail as kept by the kubelet; empty when the Pod is gone. */
    Optional<String> podLog(String podName, int tailLines);

    /** The configured image reference — the identity the recycler diffs against. */
    String currentImageDigest();

    /** {@code http://{podName}.{headlessService}:{port}} — valid before the pod exists. */
    String baseUrlFor(String podName);

    /**
     * How many more workers the namespace's quotas admit right now
     * ({@link NamespaceCapacity#UNBOUNDED} when nothing bounds it) — the hub
     * reads it from {@code GET /api/v1/capabilities} to refuse a spin up front.
     */
    default NamespaceCapacity capacity() {
        return NamespaceCapacity.UNBOUNDED;
    }
}
