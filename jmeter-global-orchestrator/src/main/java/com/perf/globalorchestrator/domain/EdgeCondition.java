package com.perf.globalorchestrator.domain;

/**
 * When an edge lets its target run, evaluated against the source task's
 * outcome.
 *
 * <p>{@link #ON_FAILURE} does double duty: drawing one is also what declares a
 * failure <em>handled</em>, so the execution finishes SUCCEEDED instead of
 * FAILED. That is the only way to say "run the test, email either way, and
 * don't call the workflow broken".
 */
public enum EdgeCondition {
    ON_SUCCESS,
    ON_FAILURE,
    ALWAYS;

    /**
     * True when a source task ending in {@code outcome} satisfies this edge.
     *
     * <p>{@link #ALWAYS} means "however it went", not "however it ended": a
     * SKIPPED or CANCELLED source never ran, so it satisfies nothing — otherwise
     * a skipped branch would keep firing its downstream tasks.
     */
    public boolean satisfiedBy(TaskState outcome) {
        return switch (this) {
            case ALWAYS     -> outcome == TaskState.SUCCEEDED || outcome == TaskState.FAILED;
            case ON_SUCCESS -> outcome == TaskState.SUCCEEDED;
            case ON_FAILURE -> outcome == TaskState.FAILED;
        };
    }
}
