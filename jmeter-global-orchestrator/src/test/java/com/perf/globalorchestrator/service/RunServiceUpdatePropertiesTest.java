package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerStatusFetcher;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.http.UpdateRunPropertiesRequest;
import com.perf.globalorchestrator.http.UpdateRunPropertiesResponse;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PluginRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RunEventRepository;
import com.perf.globalorchestrator.repo.RunMetricsRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunTrendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UX-DYNAMICS T5 — the runtime property push: the RUNNING gate, default
 * all-active targeting, per-worker partial results, the merge into the
 * member's persisted snapshot, and the PROPERTIES_UPDATED audit event.
 */
@DisplayName("RunService — runtime property updates")
class RunServiceUpdatePropertiesTest {

    private static final String RUN_ID = "01HXC2VQK4M9N6P5T0YBX2WZ01";

    private RunRepository runs;
    private RunAuditWriter audit;
    private LocalOrchestratorClient client;
    private RunService service;

    @BeforeEach
    void setUp() throws Exception {
        runs = mock(RunRepository.class);
        audit = mock(RunAuditWriter.class);
        client = mock(LocalOrchestratorClient.class);
        service = new RunService(
                runs,
                mock(RunEventRepository.class),
                audit,
                mock(PodRepository.class),
                mock(ApplicationRepository.class),
                mock(GroupCapacityRepository.class),
                client,
                mock(WorkerStatusFetcher.class),
                mock(RunMetricsRepository.class),
                mock(MetricsGroupResolver.class),
                mock(RunTrendRepository.class),
                mock(PluginRepository.class),
                null,
                "us-east-1", 1, 100, 1000L);
    }

    private static RunFleetMember member(String workerId, MemberState state, Map<String, String> props) {
        return new RunFleetMember(RUN_ID, workerId, "na-east", state, null, null,
                "http://" + workerId + ":8080", Instant.now(), Instant.now(), null,
                props, null, null);
    }

    private static Run runningRun(RunFleetMember... members) {
        return new Run(RUN_ID, "na-east", "plan-blob", null, "checkout-svc", "tester",
                RunState.RUNNING, null, Instant.now(), Instant.now(), null,
                false, List.of(members));
    }

    @Test
    @DisplayName("duplicate workerIds collapse to one push and one result row")
    void duplicateWorkerIds_collapse() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(
                runningRun(member("w1", MemberState.RUNNING, Map.of()))));
        when(client.updateTestProperties(eq(RUN_ID), any(), anyMap()))
                .thenReturn(new LocalOrchestratorClient.UpdatePropertiesResult(200, "{}", true));

        UpdateRunPropertiesResponse resp = service.updateRunProperties(RUN_ID,
                new UpdateRunPropertiesRequest(List.of("w1", "w1"), Map.of("k", "v")),
                Actor.ANONYMOUS_ACTOR);

        assertThat(resp.requested()).isEqualTo(1);
        assertThat(resp.results()).hasSize(1);
        verify(client).updateTestProperties(eq(RUN_ID), any(), anyMap());
    }

    @Test
    @DisplayName("a non-RUNNING run is a 409 gate — no RPC is attempted")
    void notRunning_rejects() {
        Run done = new Run(RUN_ID, "na-east", "plan-blob", null, "checkout-svc", "tester",
                RunState.COMPLETED, null, Instant.now(), Instant.now(), Instant.now(),
                false, List.of(member("w1", MemberState.COMPLETED, Map.of())));
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.updateRunProperties(RUN_ID,
                new UpdateRunPropertiesRequest(null, Map.of("k", "v")), Actor.ANONYMOUS_ACTOR))
                .isInstanceOf(RunService.RunPropertiesNotUpdatableException.class);
        verify(client, never()).updateTestProperties(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("an invalid property map fails before any RPC")
    void invalidProperties_rejectBeforeRpc() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(
                runningRun(member("w1", MemberState.RUNNING, Map.of()))));
        assertThatThrownBy(() -> service.updateRunProperties(RUN_ID,
                new UpdateRunPropertiesRequest(null, Map.of("1bad", "v")), Actor.ANONYMOUS_ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).updateTestProperties(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a workerId that is not an active member rejects the whole request")
    void unknownWorkerId_rejects() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(
                runningRun(member("w1", MemberState.RUNNING, Map.of()))));
        assertThatThrownBy(() -> service.updateRunProperties(RUN_ID,
                new UpdateRunPropertiesRequest(List.of("ghost"), Map.of("k", "v")), Actor.ANONYMOUS_ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
        verify(client, never()).updateTestProperties(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("default targeting hits every ACCEPTED/RUNNING member; a partial failure is per-row truth; success merges the snapshot")
    void partialSuccess_mergesAndAudits() {
        RunFleetMember w1 = member("w1", MemberState.RUNNING, Map.of("USER_OFFSET", "0"));
        RunFleetMember w2 = member("w2", MemberState.ACCEPTED, Map.of());
        RunFleetMember terminal = member("w3", MemberState.COMPLETED, Map.of());
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(runningRun(w1, w2, terminal)));
        when(client.updateTestProperties(eq(RUN_ID), any(), anyMap())).thenAnswer(inv -> {
            com.perf.globalorchestrator.client.WorkerRef ref = inv.getArgument(1);
            return "w1".equals(ref.podName())
                    ? new LocalOrchestratorClient.UpdatePropertiesResult(200, "{}", true)
                    : new LocalOrchestratorClient.UpdatePropertiesResult(502, "beanshell down", false);
        });

        UpdateRunPropertiesResponse resp = service.updateRunProperties(RUN_ID,
                new UpdateRunPropertiesRequest(null, Map.of("rampSeconds", "60")), Actor.ANONYMOUS_ACTOR);

        assertThat(resp.requested()).isEqualTo(2);   // w3 (COMPLETED) excluded
        assertThat(resp.applied()).containsExactly("rampSeconds");
        assertThat(resp.results()).hasSize(2);
        assertThat(resp.results().stream().filter(UpdateRunPropertiesResponse.WorkerResult::ok))
                .extracting(UpdateRunPropertiesResponse.WorkerResult::workerId).containsExactly("w1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> merged = ArgumentCaptor.forClass(Map.class);
        verify(runs).updateMemberProperties(eq(RUN_ID), eq("w1"), merged.capture());
        assertThat(merged.getValue())
                .containsEntry("USER_OFFSET", "0")
                .containsEntry("rampSeconds", "60");
        verify(runs, never()).updateMemberProperties(eq(RUN_ID), eq("w2"), anyMap());
        verify(audit).record(eq(RUN_ID), eq(com.perf.globalorchestrator.domain.RunEventType.PROPERTIES_UPDATED),
                any(Actor.class), any(), eq("partial"));
    }
}
