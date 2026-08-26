package com.perf.k8sorchestrator.provision;

import com.perf.k8sorchestrator.client.LocalOrchestratorClient;
import com.perf.k8sorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.k8sorchestrator.domain.Application;
import com.perf.k8sorchestrator.domain.Pod;
import com.perf.k8sorchestrator.domain.PodState;
import com.perf.k8sorchestrator.domain.RecyclePolicy;
import com.perf.k8sorchestrator.provision.RecycleEvaluator.RecycleReason;
import com.perf.k8sorchestrator.repo.ApplicationRepository;
import com.perf.k8sorchestrator.repo.PodRepository;
import com.perf.k8sorchestrator.repo.RunRepository;
import com.perf.k8sorchestrator.service.RunAuditWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PodRecycler — active-run safety + drained-pod forensics")
class PodRecyclerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final String POD = "jmeter-poc-us-east-1-worker-2";

    private PodRepository pods;
    private ApplicationRepository apps;
    private PodProvisioner provisioner;
    private PodSpinService spinService;
    private LocalOrchestratorClient localClient;
    private RecycleEvaluator evaluator;
    private RunRepository runs;
    private RunAuditWriter audit;
    private PodRecycler recycler;

    @BeforeEach
    void setUp() {
        pods = mock(PodRepository.class);
        apps = mock(ApplicationRepository.class);
        provisioner = mock(PodProvisioner.class);
        spinService = mock(PodSpinService.class);
        localClient = mock(LocalOrchestratorClient.class);
        evaluator = mock(RecycleEvaluator.class);
        runs = mock(RunRepository.class);
        audit = mock(RunAuditWriter.class);
        recycler = new PodRecycler(pods, apps, provisioner, spinService, localClient,
                evaluator, runs, audit);

        when(provisioner.currentImageDigest()).thenReturn("img-current");
        when(apps.findById("appId")).thenReturn(Optional.of(app(RecyclePolicy.DRAIN_AFTER_RUN)));
    }

    @Test
    @DisplayName("does NOT recycle a pod whose run is still non-terminal — the mid-run drain bug")
    void holds_recycle_while_run_active() {
        when(pods.findAll()).thenReturn(List.of(pod(1)));
        when(pods.isWorkerBoundToNonTerminalRun(POD)).thenReturn(true); // run still RUNNING

        PodRecycler.RecycleSummary summary = recycler.doSweep();

        assertThat(summary.recycled).isEmpty();
        verify(provisioner, never()).stopAndRemove(anyString());
        // Short-circuits before the policy decision and before any log capture.
        verify(evaluator, never()).decide(any(), any(), any());
        verify(localClient, never()).getLogs(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("drains once the run is terminal, capturing the JMeter log tail BEFORE removing the container")
    void drains_after_run_terminal_and_captures_logs_first() {
        Pod pod = pod(1);
        when(pods.findAll()).thenReturn(List.of(pod));
        when(pods.isWorkerBoundToNonTerminalRun(POD)).thenReturn(false); // run is terminal
        when(pods.markDrainingForRecycle(POD)).thenReturn(1);
        when(evaluator.decide(any(), any(), eq("img-current"))).thenReturn(RecycleReason.DRAIN_AFTER_RUN);
        when(localClient.getLogs(eq(pod.baseUrl()), eq(200), eq("jmeter")))
                .thenReturn(new LogsResult(200, "…JMeter exit forensics tail…"));
        when(runs.findMostRecentRunIdForWorker(POD)).thenReturn(Optional.of("01RUN"));

        PodRecycler.RecycleSummary summary = recycler.doSweep();

        assertThat(summary.recycled).containsEntry(POD, RecycleReason.DRAIN_AFTER_RUN);
        // Forensics captured BEFORE the container is torn down.
        var ordered = inOrder(localClient, provisioner);
        ordered.verify(localClient).getLogs(pod.baseUrl(), 200, "jmeter");
        ordered.verify(provisioner).stopAndRemove(POD);
        // DRAIN_AFTER_RUN drains without a replacement.
        verify(spinService, never()).spin(anyString(), anyString(), anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private Pod pod(long runsServed) {
        return new Pod(POD, "us-east-1", "http://" + POD + ":8080",
                PodState.IDLE, NOW, NOW.minusSeconds(60), "appId",
                runsServed, "img-current", NOW.minusSeconds(600),
                com.perf.k8sorchestrator.domain.PodSource.DYNAMIC);
    }

    private Application app(RecyclePolicy policy) {
        return new Application("appId", "demo", null, null, List.of(),
                null, NOW, null, null, null, policy, null, null, false);
    }
}
