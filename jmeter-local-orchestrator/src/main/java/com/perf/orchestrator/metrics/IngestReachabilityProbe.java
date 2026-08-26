package com.perf.orchestrator.metrics;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.Objects;

/**
 * Background-polled metrics-consumer reachability probe.
 *
 * <p>One daemon thread runs forever, calling {@link IngestProbeClient#checkReachable}
 * every {@code INGEST_HEALTH_CHECK_INTERVAL_MS} and updating a {@code volatile}
 * {@link Snapshot}. Request-thread reads of {@link #snapshot()} are O(1) — no
 * network call, no I/O, no synchronisation overhead beyond the volatile read.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Constructed with an injected {@link IngestProbeClient}. Initial
 *       snapshot is {@code DOWN/startup_in_progress} — the probe has not
 *       talked to the consumer yet.</li>
 *   <li>{@link #start()} spawns the daemon thread that fires the first
 *       probe immediately and then on the configured interval.</li>
 *   <li>{@link #close()} interrupts the thread, joins for up to 2s, and
 *       closes the client. Idempotent.</li>
 * </ol>
 *
 * <h2>Failure semantics</h2>
 * Every {@code checkReachable} outcome — UP, timeout, exception — is captured
 * by the {@link IngestProbeClient.Result} contract. The probe loop never lets
 * an exception escape; if the underlying client throws despite the contract,
 * the loop catches and marks DOWN. That keeps the daemon thread alive across
 * transient consumer faults.
 *
 * <h2>Resource budget</h2>
 * <ul>
 *   <li>1 daemon thread (~512 KB stack)</li>
 *   <li>JDK HttpClient ≈ a few hundred KB heap</li>
 *   <li>Default 30s interval = one OPTIONS request (~200 B) per interval</li>
 * </ul>
 */
public final class IngestReachabilityProbe implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(IngestReachabilityProbe.class);

    /** Reason carried in the initial DOWN snapshot before the first probe completes. */
    private static final String STARTUP_REASON = "startup_in_progress";

    /** Reason returned when the probe client could not even be constructed. */
    private static final String INIT_FAILED_REASON = "ingest_probe_init_failed";

    /** Hard cap on join() during close so a stuck client thread cannot hang shutdown. */
    private static final Duration CLOSE_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final IngestProbeClient client;
    private final Duration pollInterval;
    private final Duration probeTimeout;
    private final String   threadName;

    private volatile Snapshot snapshot;
    private volatile boolean  running;
    private Thread worker;

    private IngestReachabilityProbe(IngestProbeClient client, Duration pollInterval,
                                    Duration probeTimeout, String threadName) {
        this.client       = client;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.probeTimeout = Objects.requireNonNull(probeTimeout, "probeTimeout");
        this.threadName   = Objects.requireNonNull(threadName,   "threadName");
        this.snapshot     = client == null
                ? Snapshot.down(INIT_FAILED_REASON)
                : Snapshot.down(STARTUP_REASON);
    }

    // -----------------------------------------------------------------------
    // Factories
    // -----------------------------------------------------------------------

    /**
     * Production factory. Constructs an {@link HttpIngestProbeClient} from the
     * orchestrator config; if construction fails the probe is still returned,
     * but it permanently reports DOWN with the {@code ingest_probe_init_failed}
     * reason so the misconfiguration is visible via {@code /ready}.
     */
    public static IngestReachabilityProbe create(OrchestratorConfig config) {
        Duration probeTimeout = Duration.ofMillis(config.getIngestHealthCheckTimeoutMs());
        Duration pollInterval = Duration.ofMillis(config.getIngestHealthCheckIntervalMs());
        IngestProbeClient client = HttpIngestProbeClient.tryCreate(
                config.getMetricsIngestUrl(), probeTimeout);
        return new IngestReachabilityProbe(client, pollInterval, probeTimeout, "ingest-probe");
    }

    /** Test factory — caller supplies the stub client + cadence. */
    public static IngestReachabilityProbe withClient(IngestProbeClient client,
                                                     Duration pollInterval,
                                                     Duration probeTimeout,
                                                     String threadName) {
        return new IngestReachabilityProbe(client, pollInterval, probeTimeout, threadName);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts the daemon poll loop. The first probe fires immediately so the
     * {@code STARTUP_REASON} window is bounded by the probe timeout, not by
     * the polling interval. Idempotent.
     */
    public synchronized void start() {
        if (running) return;
        if (client == null) {
            // Permanent DOWN — no thread to start. Snapshot already reflects this.
            running = true;
            return;
        }
        running = true;
        worker = new Thread(this::loop, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            probeOnce();
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void probeOnce() {
        try {
            IngestProbeClient.Result r = client.checkReachable(probeTimeout);
            snapshot = r.reachable() ? Snapshot.up() : Snapshot.down(r.reason());
        } catch (RuntimeException e) {
            // Defence in depth: IngestProbeClient implementations are
            // contractually required not to throw, but if a stub or future
            // production client misbehaves we must NOT let the daemon
            // thread die — that would silently freeze the readiness signal.
            LOG.warn("Ingest probe threw unexpectedly; treating as DOWN: {}", e.toString());
            snapshot = Snapshot.down("ingest_probe_threw");
        }
    }

    /** Returns the most recent cached snapshot. O(1). Never blocks. */
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public void close() {
        running = false;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            try {
                w.join(CLOSE_JOIN_TIMEOUT.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn("Failed to close ingest probe client: {}", e.toString());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    public record Snapshot(boolean reachable, String reason) {
        public static Snapshot up()                  { return new Snapshot(true, null); }
        public static Snapshot down(String reason)   { return new Snapshot(false, reason); }
    }
}
