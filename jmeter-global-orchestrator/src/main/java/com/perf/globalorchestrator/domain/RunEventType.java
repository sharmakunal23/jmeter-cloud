package com.perf.globalorchestrator.domain;

/**
 * The kinds of state-changing operator actions captured in
 * {@code ORCH_RUN_EVENT}. Stored as the constant {@code name()};
 * see {@link com.perf.globalorchestrator.repo.RunEventRepository}.
 *
 * <p>Only operator-driven run mutations are events. Read operations
 * ({@code GET /runs/{id}}) and background-system actions (PodSweeper,
 * PodReconciler marking pods LOST) are deliberately excluded.
 */
public enum RunEventType {
    /** A run was launched: {@code POST /api/v1/runs}. */
    RUN_START,
    /** Workers were added to a RUNNING run: {@code POST /runs/{id}/scaleUp}. */
    SCALE_UP,
    /** Workers were drained from a RUNNING run: {@code POST /runs/{id}/scaleDown}. */
    SCALE_DOWN,
    /**
     * A single worker was drained via the UI's per-worker control. Wire-wise
     * this is a {@code SCALE_DOWN} with one workerId; it gets its own type
     * because the UI dialog presents it as a distinct action.
     */
    DRAIN_WORKER,
    /** A run was aborted (force-terminate via {@code POST /runs/{id}/abort}). */
    ABORT,
    /**
     * Runtime JMeter properties were pushed to one or more RUNNING workers
     * via {@code POST /runs/{id}/properties} (UX-DYNAMICS T5). The payload
     * records the targets, the key/value map (they are the same {@code -J}
     * values the run page already displays — not secrets) and the ok/failed
     * split.
     */
    PROPERTIES_UPDATED,
    /**
     * Artifact provenance (system actor, recorded per fan-out — launch and
     * scale-up alike): at least one worker reused its staged dataFiles copy.
     * Payload: the blobId + the reused/downloaded splits.
     */
    DATA_FILES_REUSED,
    /** Same shape as {@link #DATA_FILES_REUSED} — every worker downloaded fresh. */
    DATA_FILES_UPLOADED,
    /**
     * The original launch fan-out delivered the test plan to its accepted
     * workers (system actor; once per run — scale-up joiners re-fetch the
     * same blob and are not re-announced). Payload: the blobId + workers.
     */
    TEST_PLAN_UPLOADED,
    /**
     * The original launch fan-out staged the run's library plugins (system
     * actor; only when the run selected plugins). Payload: name@version list
     * + workers.
     */
    PLUGINS_UPLOADED,
    /**
     * A worker removed its preserved run artifacts after a successful results
     * upload (system actor; follows {@link #RESULTS_SAVED}, one per worker).
     */
    ARTIFACTS_CLEARED,
    /** A run was stopped (future endpoint). */
    STOP,
    /**
     * A run was soft-deleted ("hidden") via {@code DELETE /runs/{id}} so it
     * drops out of the default listing. The row + members + audit trail are
     * retained (reversible) — this event records who hid it, when, and why.
     */
    DELETE,

    // ── Platform-detected lifecycle events (actorSource = system) ──────────
    /** The run finished successfully (all members reached a successful terminal). */
    RUN_COMPLETED,
    /** The run ended in failure (no successful members). */
    RUN_FAILED,
    /** The run ended aborted (at least one member aborted). */
    RUN_ABORTED,
    /**
     * One or more of the run's workers were recycled by the worker-hygiene
     * policy after the run released them (best-effort attribution — a pod can
     * outlive a run, so this is the pod's most-recent run).
     */
    WORKERS_RECYCLED,
    /**
     * A worker finished uploading its results to the Document Service (only
     * for runs launched with saveResults=true). One per worker, emitted when
     * the platform observes the worker's uploadState reach UPLOADED.
     */
    RESULTS_SAVED
}
