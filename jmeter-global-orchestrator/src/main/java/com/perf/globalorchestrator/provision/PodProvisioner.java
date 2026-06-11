package com.perf.globalorchestrator.provision;

import java.util.List;

/**
 * Phase 1 of the capacity rework. The global-orchestrator owns the lifecycle
 * of local-orchestrator containers — instead of being statically declared
 * in docker-compose, pods are spun up on demand bound to a specific
 * application. The local-only implementation drives the host docker daemon
 * via a mounted unix socket; a future K8s implementation will sit behind
 * the same interface.
 *
 * <h2>Identity</h2>
 * Container name == network hostname == pod registry key. The convention is
 * {@code {applicationName}-{region}-worker-{n}}. Operations are addressed by
 * that name; the provisioner doesn't track its own state.
 *
 * <h2>Idempotency</h2>
 * {@link #createAndStart(PodSpec)} is idempotent on {@code podName}:
 * if a container with that name already exists, it's started (no-op if
 * already running) rather than rejected. Callers that want strict
 * "must not exist" semantics check {@link #exists(String)} first.
 */
public interface PodProvisioner {

    /**
     * Creates the container if missing, then starts it. Returns a
     * {@link ProvisionResult} carrying the URL the global-orchestrator can
     * reach the pod at (e.g. {@code http://payments-east-worker-1:8080})
     * plus the image digest + creation timestamp WORKER-HYGIENE Phase B
     * records on the pod row.
     */
    ProvisionResult createAndStart(PodSpec spec);

    /**
     * Stops the container and removes it from the daemon. No-op if missing.
     * Drain calls this once the registry row + run-claim accounting is
     * cleared (Phase 3).
     */
    void stopAndRemove(String podName);

    /** Stops a running container without removing it. No-op if missing. */
    void stop(String podName);

    /** Starts a previously-stopped container. Throws if the container is missing. */
    void start(String podName);

    /** Stop + start in sequence. Throws if the container is missing. */
    void restart(String podName);

    /** True if a container with this name exists in any state (running, exited, created). */
    boolean exists(String podName);

    /** True if a container with this name exists AND is currently running. */
    boolean isRunning(String podName);

    /**
     * Lists all containers managed by this provisioner (label-tagged) for
     * the given application + region. Used by the Phase 2 reconciler at
     * boot and by {@code GET /capacity/.../pods} in Phase 3 to cross-check
     * registry rows against the daemon.
     *
     * @param applicationId required
     * @param region        nullable — when null, returns all regions for the app
     */
    List<ProvisionedPod> listFor(String applicationId, String region);

    /**
     * WORKER-HYGIENE Phase D — returns the sha256 ID of the configured
     * pod image (e.g. {@code jmeter-local-orchestrator:dev}) as the
     * daemon currently sees it. The recycler diffs this against
     * {@code pod.imageDigest} to detect "image was rebuilt; recycle
     * stale pods." Returns {@code null} when the daemon can't be
     * reached or the image isn't present — caller treats null as
     * "skip the image-mismatch check this tick."
     */
    String currentImageDigest();
}
