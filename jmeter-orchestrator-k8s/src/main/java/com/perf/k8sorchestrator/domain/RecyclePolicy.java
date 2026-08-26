package com.perf.k8sorchestrator.domain;

/**
 * Pod recycle policy for a registered application. Determines when the
 * Phase D reconciler (WORKER-HYGIENE) drain-and-replaces a pod bound to
 * this app.
 *
 * <p>Per-policy threshold rules (enforced by the DB CHECK + the controller
 * validator):
 *
 * <table>
 *   <tr><th>Policy</th><th>maxRunsPerPod</th><th>podMaxAgeHours</th></tr>
 *   <tr><td>{@link #REUSE}</td>    <td>null</td>    <td>null</td></tr>
 *   <tr><td>{@link #MAX_RUNS}</td> <td>required</td><td>null</td></tr>
 *   <tr><td>{@link #MAX_AGE}</td>  <td>null</td>    <td>required</td></tr>
 *   <tr><td>{@link #BOTH}</td>     <td>required</td><td>required</td></tr>
 *   <tr><td>{@link #EVERY_RUN}</td><td>null</td>    <td>null</td></tr>
 * </table>
 */
public enum RecyclePolicy {
    /** No automatic recycle. Today's default; pods live until the operator manually drains. */
    REUSE,
    /** Recycle after {@code pod.runsServed >= application.maxRunsPerPod}. */
    MAX_RUNS,
    /** Recycle after {@code now - pod.provisionedAt >= application.podMaxAgeHours}. */
    MAX_AGE,
    /** Recycle when either MAX_RUNS or MAX_AGE threshold fires. */
    BOTH,
    /** Recycle after every single run. Regression-baseline / paranoid mode. */
    EVERY_RUN,
    /**
     * Drain after every single run <em>without</em> spinning a replacement.
     * Unlike {@link #EVERY_RUN} (drain-and-replace — keeps a warm worker
     * ready for the next run), this tears the worker down and leaves the
     * slot empty (cost-saving; the operator re-provisions on demand).
     */
    DRAIN_AFTER_RUN
}
