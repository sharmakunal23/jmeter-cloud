package com.perf.globalorchestrator.sweep;

import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Background task that flips registered pods to {@code LOST} when their
 * last heartbeat is older than {@code globalOrchestrator.pod.lostAfterMs}.
 *
 * <p>Default: pods heartbeat every 30 s; the sweeper marks them LOST
 * after 90 s of silence. The 3× multiplier tolerates one missed
 * heartbeat without flapping the pod's state.
 *
 * <p>Runs every {@code globalOrchestrator.pod.sweepIntervalMs} (default
 * 30 s). At fleet sizes that fit on a single global-orchestrator
 * instance the work is a single UPDATE.
 */
@Component
public class PodSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(PodSweeper.class);

    private final PodRepository pods;
    private final RunService runService;
    private final RegionRegistry regions;
    private final long lostAfterMs;
    private final long launchTimeoutMs;

    public PodSweeper(
            PodRepository pods,
            RunService runService,
            RegionRegistry regions,
            @Value("${globalOrchestrator.pod.lostAfterMs:90000}") long lostAfterMs,
            @Value("${globalOrchestrator.run.launchTimeoutMs:600000}") long launchTimeoutMs) {
        this.pods = pods;
        this.runService = runService;
        this.regions = regions;
        this.lostAfterMs = lostAfterMs;
        this.launchTimeoutMs = launchTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${globalOrchestrator.pod.sweepIntervalMs:30000}",
               initialDelayString = "${globalOrchestrator.pod.sweepInitialDelayMs:30000}")
    public void sweep() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofMillis(lostAfterMs));
            // Dynamic workers in routed regions never heartbeat — the
            // WorkerLivenessProbe judges them from the Pod list.
            int n = pods.markLostBefore(cutoff, regions.routedIds());
            if (n > 0) {
                LOG.info("PodSweeper marked {} pod(s) LOST (heartbeat older than {} ms)", n, lostAfterMs);
            }
            // Reliability — cascade pod-LOST onto the runFleetMember rows. A
            // killed worker's pod flips to LOST above, but nothing else
            // transitions its member, so the run would otherwise show RUNNING
            // forever. Run every tick (idempotent set-based UPDATE) so a member
            // a prior tick missed self-heals; in steady state it matches no rows.
            runService.reapLostWorkerMembers(
                    "worker lost: no heartbeat within " + lostAfterMs + " ms");
            // An async launch whose provisioning task died with a restart
            // would otherwise sit in PREPARING forever.
            int stale = runService.failStalePreparingRuns(Duration.ofMillis(launchTimeoutMs));
            if (stale > 0) {
                LOG.warn("PodSweeper failed {} PREPARING run(s) older than {} ms", stale, launchTimeoutMs);
            }
        } catch (Exception e) {
            // Don't kill the scheduler — log and try again next tick.
            LOG.warn("PodSweeper sweep failed: {}", e.toString());
        }
    }
}
