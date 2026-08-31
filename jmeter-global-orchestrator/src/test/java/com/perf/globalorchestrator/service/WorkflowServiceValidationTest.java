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
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        service = new WorkflowService(mock(WorkflowRepository.class), mock(WorkflowExecutionRepository.class),
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
