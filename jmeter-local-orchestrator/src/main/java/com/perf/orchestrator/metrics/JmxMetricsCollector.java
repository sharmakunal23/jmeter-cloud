package com.perf.orchestrator.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Polls JMeter's JMX agent for the JVM metrics surfaced by
 * {@code GET /api/v1/metrics/jmeterJvm}.
 *
 * <h2>Lazy connect, sticky-disconnect</h2>
 * The connector is opened on first successful poll and reused. If a poll
 * fails (JMeter exited, network blip), the cached connector is closed
 * and the next request triggers a fresh connect. Operators who poll the
 * endpoint at 1 Hz get one connection for the duration of the test.
 *
 * <h2>Result cache</h2>
 * Successful snapshots are cached for {@value #CACHE_TTL_MS} ms so that
 * a UI dashboard polling alongside Prometheus doesn't hit the JMX agent
 * 30+ times/min — uncached, that load can dominate JMeter's CPU.
 *
 * <h2>503 contract</h2>
 * Returns {@link Optional#empty()} when JMeter isn't running or the JMX
 * agent isn't reachable. The controller maps that to
 * {@code 503 JMETER_NOT_RUNNING}.
 */
public class JmxMetricsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(JmxMetricsCollector.class);

    private static final long CACHE_TTL_MS = 1_000;

    private final int jmxPort;
    private final Clock clock;

    // -- All mutable state guarded by `this` ---------------------------------
    private JMXConnector connector;
    private MBeanServerConnection mbeanServer;
    private JmeterJvmSnapshot lastSnapshot;
    private long lastSnapshotEpochMs = Long.MIN_VALUE;
    /**
     * Epoch ms of the most recent poll ATTEMPT (success or failure). Drives a
     * negative cache: when {@link JmeterJvmMetrics} exposes the snapshot as
     * Prometheus gauges, one scrape evaluates ~11 {@code jmeter_jvm_*} gauges
     * near-simultaneously — without this, a down JMeter would be hit with 11
     * connect attempts per scrape. Caching the failed attempt for the TTL
     * collapses that to one.
     */
    private long lastAttemptEpochMs = Long.MIN_VALUE;

    public JmxMetricsCollector(int jmxPort) {
        this(jmxPort, Clock.systemUTC());
    }

    JmxMetricsCollector(int jmxPort, Clock clock) {
        this.jmxPort = jmxPort;
        this.clock = clock;
    }

    /** Returns a fresh-or-cached snapshot, or empty if the JMX agent isn't reachable. */
    public synchronized Optional<JmeterJvmSnapshot> snapshot() {
        long now = clock.millis();
        if (lastSnapshot != null && now - lastSnapshotEpochMs < CACHE_TTL_MS) {
            return Optional.of(lastSnapshot);
        }
        // Negative cache: a poll attempt (success OR failure) is honoured for
        // the TTL so a burst of callers in one window — e.g. the ~11
        // jmeter_jvm_* gauges Prometheus evaluates per scrape — triggers at
        // most one connect attempt against a down JMeter. The MIN_VALUE guard
        // avoids long-overflow on the first-ever call.
        if (lastAttemptEpochMs != Long.MIN_VALUE && now - lastAttemptEpochMs < CACHE_TTL_MS) {
            return lastSnapshot == null ? Optional.empty() : Optional.of(lastSnapshot);
        }
        lastAttemptEpochMs = now;
        try {
            ensureConnected();
            JmeterJvmSnapshot snap = readSnapshot(mbeanServer);
            lastSnapshot = snap;
            lastSnapshotEpochMs = now;
            return Optional.of(snap);
        } catch (IOException e) {
            LOG.debug("JMX poll failed, dropping connector: {}", e.toString());
            closeQuietly();
            // Invalidate the value cache; the negative cache (lastAttemptEpochMs)
            // still suppresses reconnect storms until the TTL lapses.
            lastSnapshot = null;
            return Optional.empty();
        }
    }

    /** Closes the underlying connector. Safe to call from the orchestrator shutdown hook. */
    public synchronized void close() {
        closeQuietly();
    }

    // -----------------------------------------------------------------------
    // Internals — separated so tests can drive readSnapshot() against the
    // local platform MBean server without going through the network layer.
    // -----------------------------------------------------------------------

    private void ensureConnected() throws IOException {
        if (mbeanServer != null) return;
        JMXServiceURL url = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://localhost:" + jmxPort + "/jmxrmi");
        connector = JMXConnectorFactory.connect(url);
        mbeanServer = connector.getMBeanServerConnection();
    }

    private void closeQuietly() {
        if (connector != null) {
            try { connector.close(); }
            catch (IOException io) { LOG.debug("Error closing JMX connector: {}", io.toString()); }
        }
        connector = null;
        mbeanServer = null;
    }

    /**
     * Reads the documented JVM metrics from a connected MBean server.
     * Package-private so tests can pass the local platform MBean server
     * (i.e. the running JVM's own) and exercise the bean-name + attribute
     * mapping without any RMI round-trip.
     */
    static JmeterJvmSnapshot readSnapshot(MBeanServerConnection mbs) throws IOException {
        try {
            // ---- Heap / non-heap ----
            CompositeData heap = (CompositeData) mbs.getAttribute(
                    new ObjectName("java.lang:type=Memory"), "HeapMemoryUsage");
            CompositeData nonHeap = (CompositeData) mbs.getAttribute(
                    new ObjectName("java.lang:type=Memory"), "NonHeapMemoryUsage");
            long heapUsed = (Long) heap.get("used");
            long heapMax  = (Long) heap.get("max");
            long nonHeapUsed = (Long) nonHeap.get("used");

            // ---- GC counters (sum over generations matching young / old) ----
            long youngCount = 0, youngPause = 0, oldCount = 0, oldPause = 0;
            Set<ObjectName> gcs = mbs.queryNames(new ObjectName("java.lang:type=GarbageCollector,*"), null);
            for (ObjectName gc : gcs) {
                String name = String.valueOf(mbs.getAttribute(gc, "Name")).toLowerCase();
                long count = ((Number) mbs.getAttribute(gc, "CollectionCount")).longValue();
                long time  = ((Number) mbs.getAttribute(gc, "CollectionTime")).longValue();
                // Heuristic name match — stable across G1/Parallel/CMS/ZGC/Shenandoah.
                if (name.contains("old") || name.contains("major") || name.contains("tenured")
                        || name.contains("zgc") || name.contains("shenandoah")) {
                    oldCount += count;
                    oldPause += time;
                } else {
                    youngCount += count;
                    youngPause += time;
                }
            }

            // ---- Threads / classes / uptime / cpu ----
            int threadCount = ((Number) mbs.getAttribute(
                    new ObjectName("java.lang:type=Threading"), "ThreadCount")).intValue();
            int loadedClasses = ((Number) mbs.getAttribute(
                    new ObjectName("java.lang:type=ClassLoading"), "LoadedClassCount")).intValue();
            long uptime = ((Number) mbs.getAttribute(
                    new ObjectName("java.lang:type=Runtime"), "Uptime")).longValue();
            double cpu = readCpuLoadPercent(mbs);

            return new JmeterJvmSnapshot(
                    heapUsed, heapMax, nonHeapUsed,
                    youngCount, youngPause, oldCount, oldPause,
                    threadCount, cpu, uptime, loadedClasses);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IOException("JMX read failed: " + e, e);
        }
    }

    /**
     * Reads {@code ProcessCpuLoad} from the OS bean. Returns {@code -1.0} if
     * the bean is unavailable (some JREs don't expose it). The value is
     * normalised to 0–100 — JMX reports 0.0–1.0.
     */
    private static double readCpuLoadPercent(MBeanServerConnection mbs) {
        try {
            Object v = mbs.getAttribute(new ObjectName("java.lang:type=OperatingSystem"), "ProcessCpuLoad");
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return d < 0 ? -1.0 : d * 100.0;
            }
        } catch (Exception ignored) {
            // Some JREs / sandboxes hide ProcessCpuLoad; -1 is the documented
            // sentinel for "not available".
        }
        return -1.0;
    }

    /** Convenience for the local-process JMX self-poll used by tests. */
    static MBeanServerConnection localPlatformServer() {
        return ManagementFactory.getPlatformMBeanServer();
    }

    /** Default cache TTL exposed for tests that want to reason about timing. */
    public static Duration cacheTtl() {
        return Duration.ofMillis(CACHE_TTL_MS);
    }
}
