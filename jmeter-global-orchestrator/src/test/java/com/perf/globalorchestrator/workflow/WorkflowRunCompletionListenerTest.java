package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.RunTerminalEvent;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wake path. Its contract is as much about what it must NOT do — break the
 * run that published the event — as about what it does.
 */
@DisplayName("WorkflowRunCompletionListener — waking the execution a run belongs to")
class WorkflowRunCompletionListenerTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    private WorkflowTaskRepository tasks;
    private WorkflowExecutionRepository executions;
    private WorkflowRunCompletionListener listener;

    @BeforeEach
    void setUp() {
        tasks = mock(WorkflowTaskRepository.class);
        executions = mock(WorkflowExecutionRepository.class);
        listener = new WorkflowRunCompletionListener(tasks, executions);
    }

    private static WorkflowTask taskOwning(String runId) {
        return new WorkflowTask("t1", "ex1", "perf", NodeType.LOAD_TEST, "Peak load",
                TaskState.RUNNING, 1, "payments", runId, NOW, null, NOW.plusSeconds(15), null, null);
    }

    @Test
    @DisplayName("a run a workflow launched wakes that execution")
    void wakesTheOwningExecution() {
        when(tasks.findByRunId("run-1")).thenReturn(Optional.of(taskOwning("run-1")));
        when(executions.nudge(eq("ex1"), any())).thenReturn(true);

        listener.onRunTerminal(new RunTerminalEvent("run-1", RunState.COMPLETED, NOW));

        verify(executions).nudge(eq("ex1"), any());
    }

    @Test
    @DisplayName("an aborted or failed run wakes it too — the workflow decides what that means, not this")
    void wakesOnEveryTerminalState() {
        when(tasks.findByRunId(anyString())).thenReturn(Optional.of(taskOwning("run-2")));

        listener.onRunTerminal(new RunTerminalEvent("run-2", RunState.ABORTED, NOW));
        listener.onRunTerminal(new RunTerminalEvent("run-2", RunState.FAILED, NOW));

        verify(executions, times(2)).nudge(eq("ex1"), any());
    }

    @Test
    @DisplayName("an ordinary run costs one indexed lookup and nothing else")
    void ordinaryRunDoesNothing() {
        when(tasks.findByRunId("run-3")).thenReturn(Optional.empty());

        listener.onRunTerminal(new RunTerminalEvent("run-3", RunState.COMPLETED, NOW));

        verify(executions, never()).nudge(anyString(), any());
    }

    @Test
    @DisplayName("a failing wake never escapes — the run that published the event must not fail")
    void neverThrowsIntoTheRunPath() {
        when(tasks.findByRunId("run-4")).thenThrow(new IllegalStateException("database is down"));

        // No exception: the load test's own poll is the backstop.
        listener.onRunTerminal(new RunTerminalEvent("run-4", RunState.COMPLETED, NOW));

        verify(executions, never()).nudge(anyString(), any());
    }
}
