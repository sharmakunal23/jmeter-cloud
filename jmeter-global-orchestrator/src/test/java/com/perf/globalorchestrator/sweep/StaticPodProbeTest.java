package com.perf.globalorchestrator.sweep;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.repo.PodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StaticPodProbe — reverse liveness for declared workers")
class StaticPodProbeTest {

    private static final Instant T = Instant.parse("2026-07-27T12:00:00Z");

    private PodRepository pods;
    private LocalOrchestratorClient client;
    private StaticPodProbe probe;

    @BeforeEach
    void setUp() {
        pods = mock(PodRepository.class);
        client = mock(LocalOrchestratorClient.class);
        probe = new StaticPodProbe(pods, client, 4, 5000);
    }

    @Test
    @DisplayName("a reachable worker gets its heartbeat refreshed — the same evidence the worker's "
            + "own heartbeat would carry, so nothing downstream needs to change")
    void reachableWorkerIsRefreshed() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of(pod("w-1", PodState.IDLE)));
        when(client.isHealthy(argThat(w -> w != null && "http://w-1:8080".equals(w.baseUrl())))).thenReturn(true);
        when(pods.heartbeat("w-1")).thenReturn(1);

        StaticPodProbe.ProbeSummary summary = probe.doProbe();

        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.reachable()).isEqualTo(1);
        assertThat(summary.unreachable()).isZero();
        verify(pods).heartbeat("w-1");
    }

    @Test
    @DisplayName("an unreachable worker is left alone — PodSweeper stays the single owner of the "
            + "LOST transition, so there is one staleness rule rather than two that can disagree")
    void unreachableWorkerIsNotTouched() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of(pod("w-1", PodState.IDLE)));
        when(client.isHealthy(argThat(w -> w != null && "http://w-1:8080".equals(w.baseUrl())))).thenReturn(false);

        StaticPodProbe.ProbeSummary summary = probe.doProbe();

        assertThat(summary.unreachable()).isEqualTo(1);
        verify(pods, never()).heartbeat(anyString());
    }

    @Test
    @DisplayName("a recovered worker that was swept LOST is probed and refreshed — heartbeat()'s "
            + "existing LOST->IDLE flip is what makes it claimable again, with no operator action")
    void lostWorkerRecoversOnItsOwn() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of(pod("w-1", PodState.LOST)));
        when(client.isHealthy(argThat(w -> w != null && "http://w-1:8080".equals(w.baseUrl())))).thenReturn(true);
        when(pods.heartbeat("w-1")).thenReturn(1);

        assertThat(probe.doProbe().reachable()).isEqualTo(1);
        verify(pods).heartbeat("w-1");
    }

    @Test
    @DisplayName("one dead worker does not stop the rest of the fleet being probed")
    void oneFailureDoesNotAbortTheTick() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of(
                pod("w-1", PodState.IDLE), pod("w-2", PodState.IDLE), pod("w-3", PodState.IDLE)));
        when(client.isHealthy(argThat(w -> w != null && "http://w-1:8080".equals(w.baseUrl())))).thenReturn(true);
        when(client.isHealthy(argThat(w -> w != null && "http://w-2:8080".equals(w.baseUrl())))).thenThrow(new RuntimeException("connect reset"));
        when(client.isHealthy(argThat(w -> w != null && "http://w-3:8080".equals(w.baseUrl())))).thenReturn(true);
        when(pods.heartbeat(anyString())).thenReturn(1);

        StaticPodProbe.ProbeSummary summary = probe.doProbe();

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.reachable()).isEqualTo(2);
        assertThat(summary.unreachable()).isEqualTo(1);
        verify(pods).heartbeat("w-1");
        verify(pods).heartbeat("w-3");
        verify(pods, never()).heartbeat("w-2");
    }

    @Test
    @DisplayName("only declared workers are probed — a DYNAMIC row left over from before a mode "
            + "flip belongs to the absent provisioner, not here")
    void onlyStaticRowsAreQueried() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of());

        assertThat(probe.doProbe().total()).isZero();
        verify(pods).findBySource(PodSource.STATIC);
        verify(pods, never()).findAll();
    }

    @Test
    @DisplayName("a worker released mid-probe (rowcount 0) is not counted as alive")
    void releasedMidProbeIsNotCountedReachable() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of(pod("w-1", PodState.IDLE)));
        when(client.isHealthy(argThat(w -> w != null && "http://w-1:8080".equals(w.baseUrl())))).thenReturn(true);
        when(pods.heartbeat("w-1")).thenReturn(0);

        assertThat(probe.doProbe().reachable()).isZero();
    }

    @Test
    @DisplayName("a row with no address is skipped rather than throwing the tick away")
    void missingBaseUrlIsSkipped() {
        when(pods.findBySource(PodSource.STATIC)).thenReturn(List.of(
                new Pod("w-1", "na-east", null, PodState.IDLE, T, T, "appId", 0, null, null,
                        PodSource.STATIC)));

        assertThat(probe.doProbe().unreachable()).isEqualTo(1);
        verify(pods, never()).heartbeat(anyString());
    }

    private static Pod pod(String id, PodState state) {
        return new Pod(id, "na-east", "http://" + id + ":8080", state, T, T, "appId", 0,
                null, null, PodSource.STATIC);
    }
}
