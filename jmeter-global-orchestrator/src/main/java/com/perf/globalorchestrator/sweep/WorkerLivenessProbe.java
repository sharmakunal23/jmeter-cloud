package com.perf.globalorchestrator.sweep;

import com.perf.globalorchestrator.client.RegionalClient;
import com.perf.globalorchestrator.client.RegionalClient.WorkerLiveness;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.provision.ConditionalOnProvisioningMode;
import com.perf.globalorchestrator.provision.PodProvisioner;
import com.perf.globalorchestrator.provision.ProvisioningMode;
import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionStatus;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Liveness for dynamic workers, from the kubelet instead of heartbeats: one
 * {@code GET /api/v1/workers} per routed region per tick, then for each
 * registry row in that region — ready refreshes the heartbeat (LOST → IDLE),
 * dead or absent marks it LOST with the kubelet's reason ({@code OOMKilled
 * (exit 137)}, {@code Unschedulable}, {@code absent}) and fails its active run
 * members with that reason. Starting pods are left alone.
 *
 * <p>A spun pod that never became ready — still LOST with no run served — is
 * torn down (Pod and row) once the kubelet calls it dead or after
 * {@code startingTimeoutMs}, so it stops holding capacity.
 *
 * <p>A region whose regional orchestrator has been unreachable longer than
 * {@code regionLostAfterMs} has all its dynamic workers marked LOST — the only
 * way a vanished data center resolves its runs. Shorter outages change nothing:
 * the workers are still running JMeter, and their metrics still land.
 */
@Component
@ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
public class WorkerLivenessProbe {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerLivenessProbe.class);

    private final RegionRegistry regions;
    private final RegionalClient client;
    private final PodRepository pods;
    private final RunService runService;
    private final PodProvisioner provisioner;
    private final long regionLostAfterMs;
    private final long startingTimeoutMs;
    private final Instant bootedAt = Instant.now();

    public WorkerLivenessProbe(RegionRegistry regions, RegionalClient client, PodRepository pods,
                               RunService runService, PodProvisioner provisioner,
                               @Value("${globalOrchestrator.workerLiveness.regionLostAfterMs:300000}") long regionLostAfterMs,
                               @Value("${globalOrchestrator.workerLiveness.startingTimeoutMs:600000}") long startingTimeoutMs) {
        this.regions = regions;
        this.client = client;
        this.pods = pods;
        this.runService = runService;
        this.provisioner = provisioner;
        this.regionLostAfterMs = regionLostAfterMs;
        this.startingTimeoutMs = startingTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${globalOrchestrator.workerLiveness.intervalMs:15000}",
               initialDelayString = "${globalOrchestrator.workerLiveness.initialDelayMs:5000}")
    public void probe() {
        try {
            Summary s = doProbe();
            if (!s.lost.isEmpty()) {
                LOG.warn("WorkerLivenessProbe marked {} worker(s) LOST: {}", s.lost.size(), s.lost);
            }
        } catch (Exception e) {
            LOG.warn("WorkerLivenessProbe tick failed: {}", e.toString());
        }
    }

    public Summary doProbe() {
        Summary summary = new Summary();
        List<Pod> dynamic = pods.findBySource(PodSource.DYNAMIC);
        for (String region : regions.routedIds()) {
            List<Pod> mine = dynamic.stream()
                    .filter(p -> region.equals(p.region()) && p.state() != PodState.DRAINING_FOR_RECYCLE)
                    .toList();
            if (mine.isEmpty()) continue;
            Optional<RegionStatus> status = regions.statusOf(region);
            if (status.map(RegionStatus::reachable).map(Boolean.FALSE::equals).orElse(false)) {
                Instant lastSeen = status.get().lastSeenAt() == null ? bootedAt : status.get().lastSeenAt();
                long downMs = Duration.between(lastSeen, Instant.now()).toMillis();
                if (downMs > regionLostAfterMs) {
                    String reason = "region " + region + " unreachable for " + (downMs / 60_000) + " min";
                    mine.forEach(p -> lose(p, reason, summary));
                }
                summary.skippedRegions.add(region);
                continue;
            }
            Map<String, WorkerLiveness> live;
            try {
                live = client.listWorkers(regions.urlOf(region).orElseThrow()).stream()
                        .collect(Collectors.toMap(WorkerLiveness::podName, Function.identity(), (a, b) -> a));
            } catch (RuntimeException e) {
                LOG.debug("liveness list for region {} failed: {}", region, e.getMessage());
                summary.skippedRegions.add(region);
                continue;
            }
            for (Pod p : mine) {
                WorkerLiveness w = live.get(p.podId());
                // A pod that never served a run and is still LOST is "starting":
                // dead or overdue, it is torn down rather than kept for forensics.
                boolean starting = p.state() == PodState.LOST && p.runsServed() == 0;
                if (w == null) {
                    if (starting) reap(p, "never became ready — Pod absent from cluster " + region, summary);
                    else lose(p, "worker Pod absent from cluster " + region, summary);
                } else if (w.dead()) {
                    if (starting) reap(p, "never became ready — " + describe(w), summary);
                    else lose(p, describe(w), summary);
                } else if (w.ready()) {
                    pods.heartbeat(p.podId());
                    summary.alive++;
                } else if (starting && p.provisionedAt() != null
                        && p.provisionedAt().isBefore(Instant.now().minus(Duration.ofMillis(startingTimeoutMs)))) {
                    reap(p, "never became ready within " + (startingTimeoutMs / 60_000) + " min (phase " + w.phase() + ")", summary);
                } else {
                    summary.starting++;
                }
            }
        }
        return summary;
    }

    private void lose(Pod p, String reason, Summary summary) {
        if (pods.markLost(p.podId()) == 1) {
            summary.lost.put(p.podId(), reason);
            runService.failMembersOnLostWorker(p.podId(), reason);
        }
    }

    /** Deletes a never-ready pod and its row so it stops holding capacity. */
    private void reap(Pod p, String reason, Summary summary) {
        try {
            provisioner.stopAndRemove(p.region(), p.podId());
        } catch (RuntimeException e) {
            LOG.debug("reap {}: stopAndRemove failed ({}); removing the row anyway", p.podId(), e.getMessage());
        }
        pods.deleteByPodId(p.podId());
        summary.reaped.put(p.podId(), reason);
        LOG.warn("WorkerLivenessProbe reaped {}: {}", p.podId(), reason);
    }

    static String describe(WorkerLiveness w) {
        StringBuilder sb = new StringBuilder("worker Pod ").append(w.reason() == null ? "dead" : w.reason());
        if (w.exitCode() != null) sb.append(" (exit ").append(w.exitCode()).append(')');
        if (w.message() != null && !w.message().isBlank()) sb.append(": ").append(w.message());
        return sb.toString();
    }

    public static final class Summary {
        public final Map<String, String> lost = new LinkedHashMap<>();
        public final Map<String, String> reaped = new LinkedHashMap<>();
        public final List<String> skippedRegions = new java.util.ArrayList<>();
        public int alive;
        public int starting;
    }
}
