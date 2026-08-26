package com.perf.k8sorchestrator.sweep;

import com.perf.k8sorchestrator.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Save Results — background reconciliation of the per-worker {@code
 * RESULTS_SAVED} audit event.
 *
 * <p>A worker's JTL upload finishes <b>after</b> the run goes terminal: the
 * local-orchestrator flips its test {@code state} to COMPLETED and only then
 * runs the upload, climbing {@code uploadState} PENDING → UPLOADING → UPLOADED
 * a few seconds later. The global-orchestrator records {@code RESULTS_SAVED}
 * only when it polls {@code GET /status} and observes {@code uploadState=
 * UPLOADED} ({@link RunService#refreshAndGet}). But the run-detail page stops
 * polling the instant the run is terminal — so on its own nothing observes the
 * upload, and the event (which the events timeline shows) is never written even
 * though the run's Download-results button is already lit (it's gated only on
 * {@code saveResults} + COMPLETED).
 *
 * <p>This sweeper closes that gap: every {@code sweepIntervalMs} it drives
 * {@code refreshAndGet} on COMPLETED, {@code saveResults} runs that still have
 * a worker missing its event (within a bounded look-back), so the upload is
 * observed regardless of whether any UI is open. The work is idempotent and
 * durably de-duplicated, so it's safe across restarts and multiple ticks.
 *
 * <p>Runs every {@code globalOrchestrator.run.resultsSavedSweepIntervalMs}
 * (default 15 s — short, because the upload lands within seconds of completion
 * and the pod may be reused for the next run before long). Look-back defaults
 * to one hour so a run whose upload never lands stops being polled.
 */
@Component
public class ResultsSavedSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ResultsSavedSweeper.class);

    private final RunService runService;
    private final Duration lookback;

    public ResultsSavedSweeper(
            RunService runService,
            @Value("${k8sOrchestrator.run.resultsSavedLookbackMs:3600000}") long lookbackMs) {
        this.runService = runService;
        this.lookback = Duration.ofMillis(lookbackMs);
    }

    @Scheduled(fixedDelayString = "${k8sOrchestrator.run.resultsSavedSweepIntervalMs:15000}",
               initialDelayString = "${k8sOrchestrator.run.resultsSavedSweepInitialDelayMs:20000}")
    public void sweep() {
        try {
            int n = runService.reconcileResultsSaved(lookback);
            if (n > 0) {
                LOG.debug("ResultsSavedSweeper reconciled {} run(s) awaiting RESULTS_SAVED", n);
            }
        } catch (Exception e) {
            // Never kill the scheduler — log and try again next tick.
            LOG.warn("ResultsSavedSweeper sweep failed: {}", e.toString());
        }
    }
}
