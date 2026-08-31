package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.TaskState;

import java.time.Instant;
import java.util.Map;

/**
 * What one attempt at a task decided. An executor never blocks: it either
 * settles the task or says when it wants to be looked at again
 * ({@link #dueAt}), and the engine turns that into the execution's next tick.
 *
 * @param attempt the attempt number this outcome consumed, or null to leave
 *                the task's counter alone. An executor that retries across
 *                ticks MUST set it — the engine has no way to tell a real
 *                attempt from a "not due yet" poll, so a null here from a
 *                retrying executor is an infinite loop.
 * @param runId   the run a load-test task launched, recorded once and never
 *                replaced — null from every other executor
 */
public record TaskOutcome(TaskState state, Instant dueAt, Map<String, Object> result,
                          String errorReason, String runId, Integer attempt) {

    public static TaskOutcome running(Instant dueAt, Map<String, Object> result) {
        return new TaskOutcome(TaskState.RUNNING, dueAt, result, null, null, null);
    }

    /** Still running, and this attempt counted — the retry form. */
    public static TaskOutcome retrying(Instant dueAt, Map<String, Object> result, int attempt) {
        return new TaskOutcome(TaskState.RUNNING, dueAt, result, null, null, attempt);
    }

    public static TaskOutcome runningWithRun(Instant dueAt, Map<String, Object> result, String runId) {
        return new TaskOutcome(TaskState.RUNNING, dueAt, result, null, runId, null);
    }

    public static TaskOutcome awaitingApproval(Instant deadline, Map<String, Object> result) {
        return new TaskOutcome(TaskState.AWAITING_APPROVAL, deadline, result, null, null, null);
    }

    public static TaskOutcome succeeded(Map<String, Object> result) {
        return new TaskOutcome(TaskState.SUCCEEDED, null, result, null, null, null);
    }

    public static TaskOutcome succeeded(Map<String, Object> result, int attempt) {
        return new TaskOutcome(TaskState.SUCCEEDED, null, result, null, null, attempt);
    }

    public static TaskOutcome failed(String reason, Map<String, Object> result) {
        return new TaskOutcome(TaskState.FAILED, null, result, reason, null, null);
    }

    public static TaskOutcome failed(String reason, Map<String, Object> result, int attempt) {
        return new TaskOutcome(TaskState.FAILED, null, result, reason, null, attempt);
    }

    /** A settled load test, carrying the run it watched so the task row keeps the link. */
    public static TaskOutcome settledWithRun(TaskState state, Map<String, Object> result,
                                             String errorReason, String runId) {
        return new TaskOutcome(state, null, result, errorReason, runId, null);
    }

    public boolean isSettled() {
        return state.isTerminal();
    }
}
