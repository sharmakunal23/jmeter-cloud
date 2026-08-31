package com.perf.globalorchestrator.sweep;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerRef;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.repo.PodRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reverse liveness for operator-declared workers: this control plane probes
 * them, instead of waiting for a heartbeat they may never send.
 *
 * <p>It exists because {@code PodSweeper} flips a pod to LOST after
 * {@code globalOrchestrator.pod.lostAfterMs} without a heartbeat, and the claim
 * SQL only takes IDLE rows. A worker the operator deployed need not know this
 * control plane exists, so the entire declared fleet would sweep to LOST within
 * 90 s of being declared and become permanently unclaimable. A successful probe
 * refreshes {@code lastHeartbeat} — the same evidence a heartbeat gives — so
 * the claim path, sweeper, capacity views and drain guards need no changes.
 *
 * <p><b>STATIC rows only.</b> A DYNAMIC row's liveness is the kubelet's,
 * through {@link WorkerLivenessProbe} — probing it here would put two judges
 * on one worker.
 *
 * <p><b>Liveness, not readiness.</b> It hits {@code /actuator/health}, not the
 * worker's {@code /api/v1/ready}, because readiness folds in signals like ingest
 * reachability — and a worker that is alive but temporarily cannot reach the
 * metrics-consumer must not be swept LOST. LOST means gone.
 *
 * <p><b>Deliberately unlocked</b>, unlike the platform's other schedulers: if a
 * single lock-holding replica hung, nothing would probe and the whole static
 * fleet would go LOST. N replicas cost N× the HTTP, but the work is read-only
 * and the write is an idempotent timestamp refresh, so concurrent probes cannot
 * corrupt anything.
 */
@Component
public class StaticPodProbe {

    private static final Logger LOG = LoggerFactory.getLogger(StaticPodProbe.class);

    private final PodRepository pods;
    private final LocalOrchestratorClient localOrchestrators;
    private final ExecutorService probePool;
    private final long tickBudgetMs;

    public StaticPodProbe(
            PodRepository pods,
            LocalOrchestratorClient localOrchestrators,
            // Bounded fan-out: each probe costs up to LocalOrchestratorClient's
            // 2 s connect/read timeout, so a fleet of unreachable workers probed
            // serially would run far past the tick. 8 in flight keeps a
            // few-hundred-worker fleet inside the budget below.
            @Value("${globalOrchestrator.staticPod.probeConcurrency:8}") int probeConcurrency,
            // Hard ceiling on one tick. Must stay well under pod.lostAfterMs
            // (90 s) or a slow tick would itself cause the sweep it exists to
            // prevent.
            @Value("${globalOrchestrator.staticPod.probeTickBudgetMs:20000}") long tickBudgetMs) {
        this.pods = pods;
        this.localOrchestrators = localOrchestrators;
        this.tickBudgetMs = tickBudgetMs;
        this.probePool = Executors.newFixedThreadPool(Math.max(1, probeConcurrency), r -> {
            Thread t = new Thread(r, "staticPodProbe");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Default cadence matches the worker heartbeat interval (30 s), leaving
     * the 90 s LOST window a 3× margin — one missed probe does not flap a
     * healthy worker. The initial delay is shorter than
     * {@code pod.sweepInitialDelayMs} (30 s) on purpose: after a restart the
     * fleet must be re-probed BEFORE the first sweep, or every declared
     * worker would be marked LOST for one window.
     */
    @Scheduled(fixedDelayString = "${globalOrchestrator.staticPod.probeIntervalMs:30000}",
               initialDelayString = "${globalOrchestrator.staticPod.probeInitialDelayMs:5000}")
    public void probe() {
        try {
            ProbeSummary summary = doProbe();
            if (summary.unreachable() > 0) {
                LOG.info("StaticPodProbe: {} reachable, {} unreachable of {} declared worker(s)",
                        summary.reachable(), summary.unreachable(), summary.total());
            } else {
                LOG.debug("StaticPodProbe: all {} declared worker(s) reachable", summary.total());
            }
        } catch (Exception e) {
            // Never kill the scheduler — a probe failure must not stop the
            // next tick, or the fleet silently rots to LOST.
            LOG.warn("StaticPodProbe tick failed: {}", e.toString());
        }
    }

    /** Test seam — the tick body, driven deterministically. */
    public ProbeSummary doProbe() {
        List<Pod> declared = pods.findBySource(PodSource.STATIC);
        if (declared.isEmpty()) {
            return new ProbeSummary(0, 0, 0);
        }
        List<Callable<Boolean>> probes = new ArrayList<>(declared.size());
        for (Pod pod : declared) {
            probes.add(() -> probeOne(pod));
        }

        List<Future<Boolean>> results;
        try {
            results = probePool.invokeAll(probes, tickBudgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("StaticPodProbe interrupted mid-tick — {} worker(s) unprobed this round",
                    declared.size());
            return new ProbeSummary(declared.size(), 0, 0);
        }

        int reachable = 0;
        for (Future<Boolean> f : results) {
            try {
                if (Boolean.TRUE.equals(f.get())) reachable++;
            } catch (Exception e) {
                // Cancelled by the tick budget, or the probe threw. Either way
                // this worker is not confirmed alive; leave its heartbeat
                // untouched and let PodSweeper decide on the usual window.
                LOG.debug("StaticPodProbe: probe did not complete: {}", e.toString());
            }
        }
        return new ProbeSummary(declared.size(), reachable, declared.size() - reachable);
    }

    /**
     * One worker. A successful probe refreshes {@code lastHeartbeat} — the
     * same effect the worker's own heartbeat would have had, including
     * bringing a previously-LOST worker back to IDLE. A failure writes
     * nothing: {@code PodSweeper} owns the LOST transition, so there is
     * exactly one staleness rule in the system rather than two that could
     * disagree.
     */
    private boolean probeOne(Pod pod) {
        if (pod.baseUrl() == null || pod.baseUrl().isBlank()) {
            LOG.warn("Declared worker {} has no baseUrl — cannot probe; re-declare it with a "
                    + "valid address.", pod.podId());
            return false;
        }
        boolean healthy = localOrchestrators.isHealthy(WorkerRef.of(pod));
        if (!healthy) {
            return false;
        }
        int refreshed = pods.heartbeat(pod.podId());
        if (refreshed == 0) {
            // Released concurrently — the row is gone. Not an error.
            LOG.debug("Declared worker {} answered but its row was released mid-probe", pod.podId());
            return false;
        }
        return true;
    }

    @PreDestroy
    void shutdown() {
        probePool.shutdownNow();
    }

    /** @param total declared workers seen this tick */
    public record ProbeSummary(int total, int reachable, int unreachable) {}
}
