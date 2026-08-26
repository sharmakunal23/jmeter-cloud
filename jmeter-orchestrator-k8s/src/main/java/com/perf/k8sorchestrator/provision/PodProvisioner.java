package com.perf.k8sorchestrator.provision;

import java.util.List;

/**
 * The orchestrator owns the lifecycle of local-orchestrator workers — pods
 * are spun up on demand bound to a specific application. In this project the
 * implementation is {@link K8sPodProvisioner} (fabric8 against the cluster
 * API); the interface itself is substrate-neutral and was inherited verbatim
 * from the Docker-backed jmeter-global-orchestrator.
 *
 * <h2>Identity</h2>
 * Pod name == network hostname == pod registry key. The convention is
 * {@code {applicationName}-{region}-worker-{n}}. Operations are addressed by
 * that name; the provisioner doesn't track its own state.
 *
 * <h2>Idempotency</h2>
 * {@link #createAndStart(PodSpec)} is idempotent on {@code podName}:
 * if a pod with that name already exists, it's started (no-op if
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
     * Returns the sha256 ID of the configured
     * pod image (e.g. {@code jmeter-local-orchestrator:dev}) as the
     * daemon currently sees it. The recycler diffs this against
     * {@code pod.imageDigest} to detect "image was rebuilt; recycle
     * stale pods." Returns {@code null} when the daemon can't be
     * reached or the image isn't present — caller treats null as
     * "skip the image-mismatch check this tick."
     */
    String currentImageDigest();

    /**
     * The URL the orchestrator (and the fan-out path) reaches this pod at —
     * the same value {@code createAndStart} returns in its
     * {@link ProvisionResult}, computable without the pod existing. The
     * reconciler uses it when adopting an orphan whose registry row is gone.
     */
    String baseUrlFor(String podName);
}
