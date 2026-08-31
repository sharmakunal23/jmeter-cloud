package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
import com.perf.globalorchestrator.domain.EmailNode;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The design-time warning that catches the most expensive workflow mistake:
 * a chain of "on success" links whose failure silently takes the result email
 * down with it.
 */
@DisplayName("WorkflowService — warning when a failure would silence a notification")
class WorkflowServiceValidationTest {

    private static final NodePosition P = new NodePosition(0, 0);

    private ApplicationGroupRepository groups;
    private GroupCapacityRepository capacity;
    private WorkflowRepository workflows;
    private WorkflowExecutionRepository executions;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        groups = mock(ApplicationGroupRepository.class);
        capacity = mock(GroupCapacityRepository.class);
        ApplicationRepository applications = mock(ApplicationRepository.class);
        when(groups.findById("cps")).thenReturn(Optional.of(
                new ApplicationGroup("cps", "Group", null, Instant.now(), null)));
        when(applications.findAll()).thenReturn(List.of());
        when(capacity.findByGroupId("cps")).thenReturn(List.of());
        workflows = mock(WorkflowRepository.class);
        executions = mock(WorkflowExecutionRepository.class);
        service = new WorkflowService(workflows, executions,
                mock(WorkflowTaskRepository.class), groups, applications, capacity);
    }

    private static WorkflowNode delay(String id, String name) {
        return new DelayNode(id, name, P, JoinPolicy.ALL, 5);
    }

    private static WorkflowNode email(String id, String name, JoinPolicy join) {
        return new EmailNode(id, name, P, join, List.of("a@b.com"), List.of(), List.of(), "S", "B", false);
    }

    private static WorkflowEdge edge(String from, String to, EdgeCondition c) {
        return new WorkflowEdge(from + "-" + to + c, from, to, c);
    }

    @Test
    @DisplayName("a success-only chain ending in an email is warned about, and the warning says how to fix it")
    void warnsWhenAFailureSilencesTheEmail() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(delay("gate", "Check smokeapp"), delay("wait", "Settle"), email("m1", "Tell the team", JoinPolicy.ALL)),
                List.of(edge("gate", "wait", EdgeCondition.ON_SUCCESS),
                        edge("wait", "m1", EdgeCondition.ALWAYS)));

        WorkflowValidation v = service.validate("cps", g);

        assertThat(v.valid()).isTrue();          // a warning, never a block
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w)
                .contains("if 'Check smokeapp' fails")
                .contains("'Tell the team'")
                .contains("add an 'on failure' link"));
    }

    @Test
    @DisplayName("an on-failure link carries the failure onward, so there is nothing to warn about")
    void noWarningWhenFailureIsHandled() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(delay("gate", "Check"), email("m1", "Tell the team", JoinPolicy.ANY)),
                List.of(edge("gate", "m1", EdgeCondition.ON_SUCCESS),
                        edge("gate", "m1", EdgeCondition.ON_FAILURE)));

        assertThat(service.validate("cps", g).warnings()).isEmpty();
    }

    @Test
    @DisplayName("an ALWAYS link also carries it onward")
    void noWarningBehindAnAlwaysLink() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(delay("gate", "Check"), email("m1", "Tell the team", JoinPolicy.ALL)),
                List.of(edge("gate", "m1", EdgeCondition.ALWAYS)));

        assertThat(service.validate("cps", g).warnings()).isEmpty();
    }

    @Test
    @DisplayName("a graph with no email tasks has nothing to silence")
    void noWarningWithoutEmails() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(delay("a", "One"), delay("b", "Two")),
                List.of(edge("a", "b", EdgeCondition.ON_SUCCESS)));

        assertThat(service.validate("cps", g).warnings()).isEmpty();
    }
}

@DisplayName("WorkflowService — editing while a run is in progress")
class WorkflowServiceUpdateGuardTest {

    private static final NodePosition P = new NodePosition(0, 0);
    private static final WorkflowGraph GRAPH = new WorkflowGraph(1,
            List.of(new DelayNode("d", "Wait", P, JoinPolicy.ALL, 5)), List.of());

    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowExecutionRepository executions = mock(WorkflowExecutionRepository.class);
    private final ApplicationGroupRepository groups = mock(ApplicationGroupRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final GroupCapacityRepository capacity = mock(GroupCapacityRepository.class);
    private final WorkflowService service = new WorkflowService(
            workflows, executions, mock(WorkflowTaskRepository.class), groups, applications, capacity);

    private Workflow stored() {
        return new Workflow("wf1", "cps", "Nightly", null, GRAPH, true, 3,
                "alice", Instant.now(), "alice", Instant.now(), null);
    }

    @BeforeEach
    void setUp() {
        when(workflows.findById("wf1")).thenReturn(Optional.of(stored()));
        when(groups.findById("cps")).thenReturn(Optional.of(
                new ApplicationGroup("cps", "Group", null, Instant.now(), null)));
        when(applications.findAll()).thenReturn(List.of());
        when(capacity.findByGroupId("cps")).thenReturn(List.of());
        when(executions.countRunning("wf1")).thenReturn(1);
    }

    @Test
    @DisplayName("changing the graph while a run is in progress is refused")
    void graphChangeRefused() {
        WorkflowGraph edited = new WorkflowGraph(1,
                List.of(new DelayNode("d", "Wait longer", P, JoinPolicy.ALL, 30)), List.of());

        assertThatThrownBy(() -> service.update("wf1", 3, "Nightly", null, edited, true, Actor.system("t")))
                .isInstanceOf(WorkflowService.WorkflowBusyException.class);
    }

    @Test
    @DisplayName("disabling a workflow is not a graph change, so a run in progress does not block it")
    void disableAllowedWhileRunning() {
        // PUT is the only mutation route; guarding the whole request would leave
        // an operator no way to stop the NEXT launch while one is running.
        when(workflows.update(eq("wf1"), eq(3), eq("Nightly"), any(), any(), eq(false), any(), any()))
                .thenReturn(Optional.of(stored()));

        service.update("wf1", 3, "Nightly", null, GRAPH, false, Actor.system("t"));

        verify(workflows).update(eq("wf1"), eq(3), eq("Nightly"), any(), any(), eq(false), any(), any());
    }
}
