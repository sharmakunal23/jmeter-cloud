package com.perf.orchestrator.statemachine;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.aggregator.TumblingWindowAggregator;
import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.io.FilePoller;
import com.perf.orchestrator.io.PollResult;
import com.perf.orchestrator.io.SentinelWatcher;
import com.perf.orchestrator.io.JtlOffsetStore;
import com.perf.orchestrator.model.JtlRow;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives the JTL tailing lifecycle through its four states. {@link #run()} is
 * the only entry point, must be called from a single thread, and blocks until
 * the machine reaches {@link TailerState#DONE}.
 *
 * <p>A SIGTERM sets the shutdown flag, which RUNNING checks each iteration
 * exactly as it checks the sentinel file — so a SIGTERM gets the same graceful
 * drain-then-flush as normal completion, rather than losing the tail. The
 * shutdown hook only writes a {@code volatile boolean}, so no synchronisation
 * is needed.
 *
 * <p>An {@link IOException} from the poller is fatal: log, go straight to DONE,
 * and let the {@code finally} block clean up. Other exceptions are caught for
 * the same reason — cleanup must always run.
 */
public final class TailerStateMachine {

    private static final Logger LOG = Logger.getLogger(TailerStateMachine.class.getName());

    private final OrchestratorConfig          config;
    private final JtlOffsetStore      stateStore;
    private final SentinelWatcher        sentinel;
    private final TumblingWindowAggregator aggregator;
    private final MetricsDispatcher      dispatcher;

    private TailerState state                = TailerState.WAITING_FOR_FILE;
    private FilePoller  poller               = null;
    private int         consecutiveEmptyPolls = 0;

    /** Set to {@code true} by the shutdown hook when SIGTERM is received. */
    private volatile boolean shutdownRequested = false;

    /** Retained so it can be deregistered after {@link #run()} completes normally. */
    private Thread shutdownHook = null;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public TailerStateMachine(OrchestratorConfig          config,
                               JtlOffsetStore      stateStore,
                               SentinelWatcher        sentinel,
                               TumblingWindowAggregator aggregator,
                               MetricsDispatcher      dispatcher) {
        this.config     = Objects.requireNonNull(config,     "config cannot be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore cannot be null");
        this.sentinel   = Objects.requireNonNull(sentinel,   "sentinel cannot be null");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator cannot be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Runs the state machine to completion, blocking the calling thread.
     *
     * <p>Returns normally when {@link TailerState#DONE} is reached.
     * The dispatch queue is drained in all exit paths, including errors.
     */
    public void run() {
        installShutdownHook();
        LOG.info("TailerStateMachine starting in state: " + state);

        try {
            while (state != TailerState.DONE) {
                state = switch (state) {
                    case WAITING_FOR_FILE -> stepWaiting();
                    case RUNNING          -> stepRunning();
                    case DRAINING         -> stepDraining();
                    case DONE             -> TailerState.DONE;
                };
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Fatal I/O error in state " + state, e);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unexpected error in state " + state, e);
        } finally {
            deregisterShutdownHook();
            closePollerSafely();
            // Per-run end: drain the dispatch queue so every envelope offered
            // during this run reaches the buffer (and ideally the consumer's
            // /ingest). Envelopes the consumer hasn't accepted yet stay on
            // disk — the dispatcher's retry sweeper keeps re-POSTing them
            // after the run completes, so COMPLETED never loses data that
            // made it into the buffer. Do NOT close the dispatcher — it is a
            // process-wide singleton owned by the orchestrator's shutdown hook.
            try {
                boolean drained = dispatcher.awaitQueueDrain(Duration.ofSeconds(10));
                if (!drained) {
                    LOG.warning("Dispatcher queue did not drain within 10s — some envelopes may be retried later");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("Interrupted awaiting dispatcher drain at end of run");
            }
            LOG.info(() -> String.format(
                    "TailerStateMachine shut down. published=%d failed=%d",
                    dispatcher.publishedCount(), dispatcher.failedCount()));
        }
    }

    // -----------------------------------------------------------------------
    // State handlers
    // -----------------------------------------------------------------------

    /**
     * Tries to open the JTL file. Stays in WAITING if file/header not ready.
     * Transitions to RUNNING once {@link FilePoller#tryOpen} succeeds.
     */
    private TailerState stepWaiting() throws IOException {
        if (shutdownRequested) {
            LOG.warning("SIGTERM received while waiting for file — exiting without reading");
            return TailerState.DONE;
        }

        Optional<FilePoller> fp = FilePoller.tryOpen(config, stateStore);
        if (fp.isPresent()) {
            poller = fp.get();
            LOG.info("JTL file ready — transitioning to RUNNING");
            return TailerState.RUNNING;
        }

        sleep(config.getFileWaitPollIntervalMs());
        return TailerState.WAITING_FOR_FILE;
    }

    /**
     * Hot path. Reads new bytes, routes rows through the aggregator, publishes
     * closed windows. Checks the sentinel and SIGTERM flag on every iteration.
     */
    private TailerState stepRunning() throws IOException {
        if (sentinel.isDone() || shutdownRequested) {
            String reason = shutdownRequested ? "SIGTERM" : "sentinel file";
            LOG.info("Test complete (" + reason + ") — transitioning to DRAINING");
            return TailerState.DRAINING;
        }

        PollResult result = poller.poll();
        processRows(result.rows());
        publishCloseable();

        if (!result.hadNewData()) {
            sleep(config.getPollIntervalMs());
        }

        return TailerState.RUNNING;
    }

    /**
     * Drains remaining bytes after test completion. Counts consecutive empty
     * polls; when the threshold is reached, performs a final flush and transitions
     * to DONE.
     */
    private TailerState stepDraining() throws IOException {
        PollResult result = poller.poll();
        processRows(result.rows());
        publishCloseable();

        if (!result.hadNewData()) {
            consecutiveEmptyPolls++;
            LOG.fine(() -> "Drain: empty poll " + consecutiveEmptyPolls +
                    "/" + config.getDrainEmptyPollsThreshold());

            if (consecutiveEmptyPolls >= config.getDrainEmptyPollsThreshold()) {
                performFinalFlush();
                return TailerState.DONE;
            }
            sleep(config.getPollIntervalMs());
        } else {
            // New data resets the counter — the file is still being written
            consecutiveEmptyPolls = 0;
        }

        return TailerState.DRAINING;
    }

    // -----------------------------------------------------------------------
    // Final flush
    // -----------------------------------------------------------------------

    /**
     * Performs the definitive end-of-test flush:
     * <ol>
     *   <li>One last read + LineBuffer flush (captures the final partial line
     *       that may have no trailing newline)</li>
     *   <li>Routes any remaining rows through the aggregator</li>
     *   <li>Drains ALL open windows — the grace period no longer applies</li>
     *   <li>Publishes the final batch</li>
     * </ol>
     */
    private void performFinalFlush() throws IOException {
        LOG.info("Performing final flush — draining all remaining rows and windows");

        List<JtlRow> finalRows = poller.pollFinal();
        processRows(finalRows);

        List<WorkerMetricBatch> finalEnvelopes = aggregator.drainAll();
        if (!finalEnvelopes.isEmpty()) {
            int accepted = dispatcher.offerAll(finalEnvelopes, config.getMetricsGroupId());
            if (accepted < finalEnvelopes.size()) {
                LOG.warning(() -> String.format(
                        "Final flush: dispatcher refused %d/%d envelopes due to backpressure",
                        finalEnvelopes.size() - accepted, finalEnvelopes.size()));
            }
        }

        LOG.info(() -> String.format(
                "Final flush complete. rows=%d envelopes=%d",
                finalRows.size(), finalEnvelopes.size()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void processRows(List<JtlRow> rows) {
        for (JtlRow row : rows) {
            aggregator.record(row);
        }
    }

    private void publishCloseable() {
        List<WorkerMetricBatch> closed = aggregator.drainCloseable();
        if (!closed.isEmpty()) {
            int accepted = dispatcher.offerAll(closed, config.getMetricsGroupId());
            if (accepted < closed.size()) {
                LOG.warning(() -> String.format(
                        "Dispatcher refused %d/%d envelopes due to backpressure — see metricsDispatch.dropsForBackpressure",
                        closed.size() - accepted, closed.size()));
            }
        }
    }

    private void closePollerSafely() {
        if (poller != null) {
            try {
                poller.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Error closing FilePoller", e);
            }
        }
    }

    /**
     * Sleeps for the specified duration. Handles {@link InterruptedException}
     * by restoring the interrupt flag and transitioning toward shutdown —
     * an interrupt signals an external request to stop.
     */
    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownRequested = true;
        }
    }

    /**
     * Installs a JVM shutdown hook that sets {@link #shutdownRequested}.
     * The hook writes only a volatile field — it does no I/O and holds no
     * locks — so it is safe to execute on the JVM shutdown thread.
     *
     * <p>The hook thread is stored in {@link #shutdownHook} and removed via
     * {@link Runtime#removeShutdownHook} in the {@code finally} block of
     * {@link #run()} so that tests that run multiple machines do not accumulate
     * hooks indefinitely.
     */
    private void installShutdownHook() {
        shutdownHook = new Thread(() -> {
            LOG.info("Shutdown hook triggered (SIGTERM) — requesting graceful drain");
            shutdownRequested = true;
        }, "jtl-stream-shutdown-hook");
        shutdownHook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Removes the shutdown hook installed by {@link #installShutdownHook}.
     * Silently ignores {@link IllegalStateException} which is thrown if the JVM
     * is already shutting down — in that case the hook is executing and removal
     * is a no-op.
     */
    private void deregisterShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is shutting down — hook is already executing, removal not needed
            }
        }
    }
}
