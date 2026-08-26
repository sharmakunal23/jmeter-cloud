package com.perf.globalorchestrator.domain;

/**
 * Who owns a worker's lifecycle. Persisted as
 * {@code globalOrchestrator.pod."source"} (V29).
 */
public enum PodSource {

    /**
     * The control plane created this worker and owns it end to end: spin,
     * restart, recycle, drain. Liveness comes from the worker's own
     * {@code POST /api/v1/heartbeat}. Every row that predates V29.
     */
    DYNAMIC,

    /**
     * The operator deployed this worker and declared it against an
     * (application, region). The control plane may claim and fan out to it,
     * but must never create, restart or destroy it — releasing it removes
     * the registry row and leaves the worker running.
     *
     * <p>Liveness is maintained by {@code StaticPodProbe} rather than by
     * heartbeats: a declared worker need not know the control plane exists,
     * so waiting for it to call in would let {@code PodSweeper} sweep the
     * whole fleet to {@link PodState#LOST} within the heartbeat window.
     */
    STATIC
}
