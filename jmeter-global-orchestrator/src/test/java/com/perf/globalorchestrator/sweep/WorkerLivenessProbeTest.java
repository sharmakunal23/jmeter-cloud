package com.perf.globalorchestrator.sweep;

import com.perf.globalorchestrator.client.RegionalClient;
import com.perf.globalorchestrator.client.RegionalClient.WorkerLiveness;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionProperties;
import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.service.RunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WorkerLivenessProbe — the kubelet decides LOST for dynamic workers")
class WorkerLivenessProbeTest {

    private static final String URL = "http://na-east-control-plane:30088";
    private RegionRegistry regions;
    private RegionalClient client;
    private PodRepository pods;
    private RunService runService;
    private com.perf.globalorchestrator.provision.PodProvisioner provisioner;
    private WorkerLivenessProbe probe;

    @BeforeEach
    void setUp() {
        regions = new RegionRegistry(new RegionProperties("na-east=" + URL + ",lab"));
        regions.markReachable("na-east", new RegionCapabilities("na-east", "jmeter-cloud", "workers", "img", 8080, "dev"));
        client = mock(RegionalClient.class);
        pods = mock(PodRepository.class);
        runService = mock(RunService.class);
        provisioner = mock(com.perf.globalorchestrator.provision.PodProvisioner.class);
        probe = new WorkerLivenessProbe(regions, client, pods, runService, provisioner, 300_000, 600_000);
        when(pods.markLost(anyString())).thenReturn(1);
    }

    private static Pod pod(String id, String region, PodState state) {
        return pod(id, region, state, 1, Instant.now());
    }

    private static Pod pod(String id, String region, PodState state, long runsServed, Instant provisionedAt) {
        return new Pod(id, region, "http://" + id + ".workers:8080", state, Instant.now(), Instant.now(),
                "APP", runsServed, "img", provisionedAt, PodSource.DYNAMIC);
    }

    private static WorkerLiveness live(String name, boolean ready, boolean dead, String reason, Integer exit) {
        return new WorkerLiveness(name, "APP", dead ? "Failed" : "Running", ready, dead, reason, exit, 0, null);
    }

    @Test
    @DisplayName("ready refreshes the heartbeat; OOMKilled marks LOST with the exit code and fails the members; absent marks LOST; starting is left alone; direct regions are never touched")
    void verdicts() {
        when(pods.findBySource(PodSource.DYNAMIC)).thenReturn(List.of(
                pod("w-ready", "na-east", PodState.IDLE),
                pod("w-oom", "na-east", PodState.IDLE),
                pod("w-absent", "na-east", PodState.IDLE),
                pod("w-starting", "na-east", PodState.LOST),
                pod("w-direct", "lab", PodState.IDLE)));
        when(client.listWorkers(URL)).thenReturn(List.of(
                live("w-ready", true, false, null, null),
                live("w-oom", false, true, "OOMKilled", 137),
                live("w-starting", false, false, null, null)));

        WorkerLivenessProbe.Summary s = probe.doProbe();

        verify(pods).heartbeat("w-ready");
        assertThat(s.lost).containsEntry("w-oom", "worker Pod OOMKilled (exit 137)")
                          .containsEntry("w-absent", "worker Pod absent from cluster na-east")
                          .hasSize(2);
        verify(runService).failMembersOnLostWorker("w-oom", "worker Pod OOMKilled (exit 137)");
        verify(runService).failMembersOnLostWorker("w-absent", "worker Pod absent from cluster na-east");
        verify(pods, never()).markLost("w-starting");
        verify(pods, never()).markLost("w-direct");
        verify(pods, never()).heartbeat("w-direct");
        assertThat(s.starting).isEqualTo(1);
        assertThat(s.alive).isEqualTo(1);
    }

    @Test
    @DisplayName("a spun pod that never became ready is torn down — dead, absent, or overdue — instead of holding capacity forever")
    void neverReadyPodsAreReaped() {
        Instant longAgo = Instant.now().minusSeconds(3600);
        when(pods.findBySource(PodSource.DYNAMIC)).thenReturn(List.of(
                pod("s-dead", "na-east", PodState.LOST, 0, Instant.now()),
                pod("s-absent", "na-east", PodState.LOST, 0, Instant.now()),
                pod("s-overdue", "na-east", PodState.LOST, 0, longAgo),
                pod("s-fresh", "na-east", PodState.LOST, 0, Instant.now()),
                pod("served-dead", "na-east", PodState.IDLE, 3, longAgo)));
        when(client.listWorkers(URL)).thenReturn(List.of(
                new WorkerLiveness("s-dead", "APP", "Pending", false, true, "Unschedulable", null, 0, "insufficient memory"),
                new WorkerLiveness("s-overdue", "APP", "Pending", false, false, null, null, 0, null),
                new WorkerLiveness("s-fresh", "APP", "Pending", false, false, null, null, 0, null),
                new WorkerLiveness("served-dead", "APP", "Failed", false, true, "OOMKilled", 137, 0, null)));

        WorkerLivenessProbe.Summary s = probe.doProbe();

        assertThat(s.reaped).containsOnlyKeys("s-dead", "s-absent", "s-overdue");
        assertThat(s.reaped.get("s-dead")).contains("Unschedulable");
        verify(provisioner).stopAndRemove("na-east", "s-dead");
        verify(pods).deleteByPodId("s-dead");
        verify(pods).deleteByPodId("s-absent");
        verify(pods).deleteByPodId("s-overdue");
        verify(pods, never()).deleteByPodId("s-fresh");
        assertThat(s.starting).isEqualTo(1);
        // A pod that served runs keeps its row and its reason for forensics.
        assertThat(s.lost).containsOnlyKeys("served-dead");
        verify(pods, never()).deleteByPodId("served-dead");
    }

    @Test
    @DisplayName("a pod already LOST is not failed again — markLost's rowcount gates the member update")
    void idempotent() {
        when(pods.findBySource(PodSource.DYNAMIC)).thenReturn(List.of(pod("w-oom", "na-east", PodState.LOST)));
        when(client.listWorkers(URL)).thenReturn(List.of(live("w-oom", false, true, "OOMKilled", 137)));
        when(pods.markLost("w-oom")).thenReturn(0);

        WorkerLivenessProbe.Summary s = probe.doProbe();

        assertThat(s.lost).isEmpty();
        verify(runService, never()).failMembersOnLostWorker(anyString(), anyString());
    }

    @Test
    @DisplayName("a region that is briefly unreachable changes nothing; one unreachable past regionLostAfterMs loses every worker")
    void unreachableRegion() {
        when(pods.findBySource(PodSource.DYNAMIC)).thenReturn(List.of(pod("w-1", "na-east", PodState.IDLE)));
        regions.markUnreachable("na-east", "connection refused");

        WorkerLivenessProbe.Summary brief = probe.doProbe();
        assertThat(brief.lost).isEmpty();
        assertThat(brief.skippedRegions).containsExactly("na-east");
        verify(client, never()).listWorkers(anyString());

        WorkerLivenessProbe impatient = new WorkerLivenessProbe(regions, client, pods, runService, provisioner, -1, 600_000);
        WorkerLivenessProbe.Summary gone = impatient.doProbe();
        assertThat(gone.lost).containsKey("w-1");
        assertThat(gone.lost.get("w-1")).startsWith("region na-east unreachable for");
    }
}
