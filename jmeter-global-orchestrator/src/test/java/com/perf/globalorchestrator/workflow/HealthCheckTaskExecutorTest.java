package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.HealthCheckNode;
import com.perf.globalorchestrator.domain.HealthRequirement;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.service.HealthProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The health gate's retry accounting. The bug this pins: an executor that
 * retries across ticks has to report the attempt it consumed, or the engine
 * writes the old counter back and {@code attempts: 3} retries for ever.
 */
@DisplayName("HealthCheckTaskExecutor — gate and retry accounting")
class HealthCheckTaskExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final NodePosition P = new NodePosition(0, 0);

    private ApplicationRepository applications;
    private HealthProbe probe;
    private HealthCheckTaskExecutor executor;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationRepository.class);
        probe = mock(HealthProbe.class);
        executor = new HealthCheckTaskExecutor(applications, probe);
    }

    private static HealthCheckNode node(HealthRequirement req, Integer minHealthy, int attempts) {
        return new HealthCheckNode("h", "Check payments", P, JoinPolicy.ALL, "payments",
                req, minHealthy, attempts, 5, 5);
    }

    private static TaskContext ctx(WorkflowTask task) {
        WorkflowExecution ex = new WorkflowExecution("ex", "wf", "cps", "WF", WorkflowGraph.empty(),
                ExecutionState.RUNNING, null, "tester", NOW, null, NOW, List.of());
        ApplicationGroup group = new ApplicationGroup("cps", "Group", null, NOW, null);
        return new TaskContext(ex, group, task, NOW);
    }

    private static WorkflowTask task(TaskState state, int attempt, Instant dueAt) {
        return new WorkflowTask("t", "ex", "h", NodeType.HEALTH_CHECK, "Check payments",
                state, attempt, "payments", null, NOW, null, dueAt, null, null);
    }

    private void registerApp(String... endpoints) {
        when(applications.findVisibleByName("payments")).thenReturn(Optional.of(new Application(
                "app1", "payments", null, null, List.of(endpoints), NOW, null, null, null, "cps", "PAYMENTS")));
    }

    private static Map<String, Object> endpoint(boolean ok) {
        return ok ? Map.of("url", "u", "ok", true, "statusCode", 200)
                  : Map.of("url", "u", "ok", false, "statusCode", 503);
    }

    @Test
    @DisplayName("every endpoint healthy passes the gate on the first attempt")
    void allHealthyPasses() {
        registerApp("http://a/health", "http://b/health");
        when(probe.probeAll(anyList(), any(Duration.class)))
                .thenReturn(List.of(endpoint(true), endpoint(true)));

        TaskOutcome out = executor.start(node(HealthRequirement.ALL, null, 3), ctx(task(TaskState.PENDING, 0, null)));

        assertThat(out.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(out.attempt()).isEqualTo(1);
        assertThat(out.result()).containsEntry("healthy", 2).containsEntry("required", 2);
    }

    @Test
    @DisplayName("a retry reports the attempt it used — otherwise the counter never moves and the gate loops for ever")
    void retryReportsItsAttempt() {
        registerApp("http://a/health", "http://b/health");
        when(probe.probeAll(anyList(), any(Duration.class)))
                .thenReturn(List.of(endpoint(true), endpoint(false)));

        TaskOutcome first = executor.start(node(HealthRequirement.ALL, null, 3), ctx(task(TaskState.PENDING, 0, null)));
        assertThat(first.state()).isEqualTo(TaskState.RUNNING);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.dueAt()).isEqualTo(NOW.plusSeconds(5));

        // Tick two: the row now says attempt 1, so this is attempt 2.
        TaskOutcome second = executor.poll(node(HealthRequirement.ALL, null, 3),
                ctx(task(TaskState.RUNNING, 1, NOW.minusSeconds(1))));
        assertThat(second.attempt()).isEqualTo(2);

        // Tick three exhausts the budget and fails, rather than retrying a fourth time.
        TaskOutcome third = executor.poll(node(HealthRequirement.ALL, null, 3),
                ctx(task(TaskState.RUNNING, 2, NOW.minusSeconds(1))));
        assertThat(third.state()).isEqualTo(TaskState.FAILED);
        assertThat(third.attempt()).isEqualTo(3);
        assertThat(third.errorReason()).contains("after 3 attempt(s)");
    }

    @Test
    @DisplayName("a poll before the retry is due changes nothing")
    void pollBeforeDueIsANoOp() {
        TaskOutcome out = executor.poll(node(HealthRequirement.ALL, null, 3),
                ctx(task(TaskState.RUNNING, 1, NOW.plusSeconds(30))));
        assertThat(out.state()).isEqualTo(TaskState.RUNNING);
        assertThat(out.attempt()).isNull();   // no attempt consumed
    }

    @Test
    @DisplayName("ANY passes on one healthy endpoint")
    void anyPasses() {
        registerApp("http://a/health", "http://b/health");
        when(probe.probeAll(anyList(), any(Duration.class)))
                .thenReturn(List.of(endpoint(false), endpoint(true)));

        TaskOutcome out = executor.start(node(HealthRequirement.ANY, null, 1), ctx(task(TaskState.PENDING, 0, null)));
        assertThat(out.state()).isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    @DisplayName("AT_LEAST asking for more endpoints than exist is clamped, not made unpassable")
    void atLeastIsClamped() {
        registerApp("http://a/health");
        when(probe.probeAll(anyList(), any(Duration.class))).thenReturn(List.of(endpoint(true)));

        TaskOutcome out = executor.start(node(HealthRequirement.AT_LEAST, 5, 1), ctx(task(TaskState.PENDING, 0, null)));
        assertThat(out.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(out.result()).containsEntry("required", 1);
    }

    @Test
    @DisplayName("an application with no endpoints fails the gate rather than passing it")
    void noEndpointsIsAFailure() {
        registerApp();
        TaskOutcome out = executor.start(node(HealthRequirement.ALL, null, 3), ctx(task(TaskState.PENDING, 0, null)));
        assertThat(out.state()).isEqualTo(TaskState.FAILED);
        assertThat(out.errorReason()).contains("no health endpoints configured");
    }

    @Test
    @DisplayName("an unregistered application fails the gate")
    void unknownApplicationFails() {
        when(applications.findVisibleByName("payments")).thenReturn(Optional.empty());
        TaskOutcome out = executor.start(node(HealthRequirement.ALL, null, 3), ctx(task(TaskState.PENDING, 0, null)));
        assertThat(out.state()).isEqualTo(TaskState.FAILED);
        assertThat(out.errorReason()).contains("is not registered");
    }

    @Test
    @DisplayName("an archived application stops the gate — it reads the same visible registry validation does")
    void archivedApplicationFails() {
        // findVisibleByName excludes archived rows, so an app retired mid-execution
        // looks gone here exactly as it does to the validator.
        when(applications.findVisibleByName("payments")).thenReturn(Optional.empty());
        TaskOutcome out = executor.start(node(HealthRequirement.ALL, null, 3), ctx(task(TaskState.PENDING, 0, null)));
        assertThat(out.state()).isEqualTo(TaskState.FAILED);
        assertThat(out.errorReason()).contains("archived");
    }
}
