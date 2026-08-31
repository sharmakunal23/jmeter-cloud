package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerStatusFetcher;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunEventType;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Save Results / UX-DYNAMICS events — the post-terminal observation loop:
 * RESULTS_SAVED + ARTIFACTS_CLEARED from the worker's status snapshot, with
 * hashed deterministic eventIds and the both-events durable stop-condition.
 *
 * <p>The snapshots deliberately carry STRING scalars ("UPLOADED", "true") —
 * that is what the status parsers produce on the wire (they stringify), and a
 * Boolean-typed fixture would have masked the guard bug this pins.
 */
@DisplayName("RunService — post-terminal RESULTS_SAVED + ARTIFACTS_CLEARED")
class RunServicePostRunEventsTest {

    private static final String RUN_ID = "01HXC2VQK4M9N6P5T0YBX2WZ03";
    private static final String W1 = "smokeapp-na-east-worker-1";

    private RunRepository runs;
    private RunEventRepository auditEvents;
    private RunAuditWriter audit;
    private WorkerStatusFetcher statusFetcher;
    private RunService service;

    @BeforeEach
    void setUp() {
        runs = mock(RunRepository.class);
        auditEvents = mock(RunEventRepository.class);
        audit = mock(RunAuditWriter.class);
        statusFetcher = mock(WorkerStatusFetcher.class);
        service = new RunService(
                runs,
                auditEvents,
                audit,
                mock(PodRepository.class),
                mock(ApplicationRepository.class),
                mock(GroupCapacityRepository.class),
                mock(LocalOrchestratorClient.class),
                statusFetcher,
                mock(RunMetricsRepository.class),
                mock(MetricsGroupResolver.class),
                mock(RunTrendRepository.class),
                mock(PluginRepository.class),
                null,
                mock(ProvisioningProperties.class),
                "us-east-1", 1, 100, 1000L);
    }

    private static Run completedSaveResultsRun() {
        RunFleetMember m = new RunFleetMember(RUN_ID, W1, "na-east", MemberState.COMPLETED,
                null, 202, "http://" + W1 + ":8080", Instant.now(), Instant.now(), Instant.now(),
                Map.of(), null, null);
        return new Run(RUN_ID, "na-east", "plan-blob", "data-blob", "smokeapp", "tester",
                RunState.COMPLETED, null, Instant.now(), Instant.now().minusSeconds(120),
                Instant.now(), true, List.of(m));
    }

    /** The wire shape: the status parsers stringify every scalar. */
    private static Map<String, Object> uploadedSnapshot() {
        return Map.of(
                "runId", RUN_ID,
                "state", "COMPLETED",
                "uploadState", "UPLOADED",
                "uploadTarget", "doc-service://results-x.jtl.gz",
                "artifactsCleared", "true");
    }

    @Test
    @DisplayName("an UPLOADED + cleared snapshot emits both events with 64-hex deterministic ids")
    void emitsBoth() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(completedSaveResultsRun()));
        when(auditEvents.workerIdsWithEvent(RUN_ID, "RESULTS_SAVED")).thenReturn(Set.of());
        when(auditEvents.workerIdsWithEvent(RUN_ID, "ARTIFACTS_CLEARED")).thenReturn(Set.of());
        when(statusFetcher.fetch(any())).thenReturn(Map.of(W1, uploadedSnapshot()));

        service.refreshAndGet(RUN_ID);

        verify(audit).record(
                eq(RunAuditWriter.deterministicId("resultsSaved:" + RUN_ID + ":" + W1)),
                eq(RUN_ID), eq(RunEventType.RESULTS_SAVED), any(Actor.class), any(), eq("ok"));
        verify(audit).record(
                eq(RunAuditWriter.deterministicId("artifactsCleared:" + RUN_ID + ":" + W1)),
                eq(RUN_ID), eq(RunEventType.ARTIFACTS_CLEARED), any(Actor.class), any(), eq("ok"));
    }

    @Test
    @DisplayName("both events durably recorded = the terminal fast-path, no worker poll")
    void stopCondition() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(completedSaveResultsRun()));
        when(auditEvents.workerIdsWithEvent(RUN_ID, "RESULTS_SAVED")).thenReturn(Set.of(W1));
        when(auditEvents.workerIdsWithEvent(RUN_ID, "ARTIFACTS_CLEARED")).thenReturn(Set.of(W1));

        service.refreshAndGet(RUN_ID);

        verify(statusFetcher, never()).fetch(any());
    }

    @Test
    @DisplayName("RESULTS_SAVED recorded but not ARTIFACTS_CLEARED: still polls, emits only the cleanup event")
    void clearedLagsUpload() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(completedSaveResultsRun()));
        when(auditEvents.workerIdsWithEvent(RUN_ID, "RESULTS_SAVED")).thenReturn(Set.of(W1));
        when(auditEvents.workerIdsWithEvent(RUN_ID, "ARTIFACTS_CLEARED")).thenReturn(Set.of());
        when(statusFetcher.fetch(any())).thenReturn(Map.of(W1, uploadedSnapshot()));

        service.refreshAndGet(RUN_ID);

        verify(audit, never()).record(anyString(), eq(RUN_ID), eq(RunEventType.RESULTS_SAVED),
                any(Actor.class), any(), anyString());
        verify(audit).record(anyString(), eq(RUN_ID), eq(RunEventType.ARTIFACTS_CLEARED),
                any(Actor.class), any(), eq("ok"));
    }

    @Test
    @DisplayName("no artifactsCleared flag in the snapshot = no ARTIFACTS_CLEARED event")
    void notClearedYet() {
        when(runs.findByRunId(RUN_ID)).thenReturn(Optional.of(completedSaveResultsRun()));
        when(auditEvents.workerIdsWithEvent(RUN_ID, "RESULTS_SAVED")).thenReturn(Set.of());
        when(auditEvents.workerIdsWithEvent(RUN_ID, "ARTIFACTS_CLEARED")).thenReturn(Set.of());
        when(statusFetcher.fetch(any())).thenReturn(Map.of(W1, Map.of(
                "runId", RUN_ID, "state", "COMPLETED", "uploadState", "UPLOADED")));

        service.refreshAndGet(RUN_ID);

        verify(audit).record(anyString(), eq(RUN_ID), eq(RunEventType.RESULTS_SAVED),
                any(Actor.class), any(), eq("ok"));
        verify(audit, never()).record(anyString(), eq(RUN_ID), eq(RunEventType.ARTIFACTS_CLEARED),
                any(Actor.class), any(), anyString());
    }
}
