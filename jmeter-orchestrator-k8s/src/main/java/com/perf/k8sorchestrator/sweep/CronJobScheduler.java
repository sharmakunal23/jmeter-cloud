package com.perf.k8sorchestrator.sweep;

import com.perf.k8sorchestrator.domain.Actor;
import com.perf.k8sorchestrator.domain.CronJob;
import com.perf.k8sorchestrator.service.CronFireService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The DB-claim scheduler. A single {@code @Scheduled} tick claims
 * the due schedules (HA-safe via {@code FOR UPDATE SKIP LOCKED} in
 * {@link CronFireService#claimDue()}) and fires each.
 *
 * <p>Why a poller instead of Quartz: this is the exact idiom {@link PodSweeper} and
 * {@link ApplicationHealthPoller} already use, and the row-claim gives HA for
 * free — run N global-orchestrator replicas (e.g. on EKS) and each due
 * schedule fires exactly once, no leader election, no Quartz cluster tables,
 * no double-fire config landmine. {@code nextFireAt} lives in Postgres, so a
 * fire missed during a restart is caught on the next tick and advanced to the
 * next future slot (catch-up-once).
 *
 * <p>Resilience: the claim is wrapped in try/catch so a transient DB error
 * just skips a tick (retried next interval); {@link CronFireService#fire} never
 * throws (every failure becomes a recorded outcome). The loop is unkillable by
 * a single bad schedule — the same discipline as
 * {@link ApplicationHealthPoller#pollAll()}.
 */
@Component
public class CronJobScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CronJobScheduler.class);

    /** The platform identity attributed to automatically-fired runs. */
    private static final Actor SCHEDULER_ACTOR = Actor.system("scheduler");

    private final CronFireService fireService;

    public CronJobScheduler(CronFireService fireService) {
        this.fireService = fireService;
    }

    @Scheduled(
            fixedDelayString = "${k8sOrchestrator.automation.sweepIntervalMs:30000}",
            initialDelayString = "${k8sOrchestrator.automation.sweepInitialDelayMs:15000}")
    public void sweep() {
        List<CronJob> claimed;
        try {
            claimed = fireService.claimDue();
        } catch (Exception e) {
            // Transient DB / lock error — log and retry on the next tick.
            LOG.warn("CronJobScheduler: claimDue failed; skipping this tick", e);
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }
        LOG.info("CronJobScheduler: firing {} due schedule(s)", claimed.size());
        for (CronJob job : claimed) {
            // fire() is self-contained and never throws; the per-row guard is
            // belt-and-suspenders so one surprise can't strand the rest.
            try {
                fireService.fire(job, SCHEDULER_ACTOR);
            } catch (Exception e) {
                LOG.error("CronJobScheduler: firing schedule {} ({}) threw unexpectedly",
                        job.cronJobId(), job.name(), e);
            }
        }
    }
}
