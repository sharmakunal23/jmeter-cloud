package com.perf.orchestrator.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Exposes the JMeter child process's JVM health — polled over JMX by
 * {@link JmxMetricsCollector} — as Micrometer gauges, so it lands on the same
 * {@code /actuator/prometheus} endpoint the orchestrator's own JVM metrics use.
 * This lets the "Worker Pod JVM" Grafana dashboard chart the orchestrator
 * process and the JMeter child <em>side by side</em>, so an operator can tell
 * at a glance which JVM is under pressure.
 *
 * <h2>Naming</h2>
 * Every series carries the {@code jmeter_jvm_*} prefix (vs the orchestrator's
 * Spring Boot actuator {@code jvm_memory_*} / {@code jvm_threads_*}), so the two
 * never collide on one scrape. Prometheus relabeling (see {@code grafana/prometheus.yml})
 * stamps the same {@code role="localOrchestrator"} + {@code instance} labels on
 * these as on the orchestrator's own metrics, because both are scraped from the
 * worker pod's single actuator endpoint — so the dashboard's {@code $pod} filter
 * selects both.
 *
 * <h2>No-test behaviour</h2>
 * Between runs (or before the first run) the JMeter child isn't running and
 * {@link JmxMetricsCollector#snapshot()} is empty; every gauge then reports
 * {@code NaN}, which Prometheus records as an absent sample — the panels simply
 * show a gap until the next JMeter child starts.
 *
 * <h2>Cost</h2>
 * A scrape evaluates all gauges near-simultaneously; the collector's 1 s
 * positive+negative result cache collapses them to a single JMX round-trip
 * (or a single suppressed connect attempt when JMeter is down) per scrape.
 */
public final class JmeterJvmMetrics implements MeterBinder {

    private final JmxMetricsCollector collector;

    public JmeterJvmMetrics(JmxMetricsCollector collector) {
        this.collector = collector;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "jmeter.jvm.heap.used.bytes",
                "JMeter child JVM heap used (bytes).", JmeterJvmSnapshot::heapUsedBytes);
        gauge(registry, "jmeter.jvm.heap.max.bytes",
                "JMeter child JVM max heap (-Xmx, bytes).", JmeterJvmSnapshot::heapMaxBytes);
        gauge(registry, "jmeter.jvm.nonheap.used.bytes",
                "JMeter child JVM non-heap used (bytes).", JmeterJvmSnapshot::nonHeapUsedBytes);
        gauge(registry, "jmeter.jvm.threads",
                "JMeter child JVM live thread count.", JmeterJvmSnapshot::threadCount);
        gauge(registry, "jmeter.jvm.gc.young.count",
                "JMeter child young-GC collection count (cumulative).", JmeterJvmSnapshot::gcYoungCount);
        gauge(registry, "jmeter.jvm.gc.young.pause.ms",
                "JMeter child young-GC total pause (ms, cumulative).", JmeterJvmSnapshot::gcYoungPauseMs);
        gauge(registry, "jmeter.jvm.gc.old.count",
                "JMeter child old-GC collection count (cumulative).", JmeterJvmSnapshot::gcOldCount);
        gauge(registry, "jmeter.jvm.gc.old.pause.ms",
                "JMeter child old-GC total pause (ms, cumulative).", JmeterJvmSnapshot::gcOldPauseMs);
        gauge(registry, "jmeter.jvm.cpu.percent",
                "JMeter child process CPU load (0-100; -1 when unavailable).", JmeterJvmSnapshot::cpuLoadPercent);
        gauge(registry, "jmeter.jvm.classes.loaded",
                "JMeter child loaded class count.", JmeterJvmSnapshot::loadedClasses);
        gauge(registry, "jmeter.jvm.uptime.ms",
                "JMeter child JVM uptime (ms).", JmeterJvmSnapshot::uptimeMs);
    }

    private void gauge(MeterRegistry registry, String name, String help,
                       ToDoubleFunction<JmeterJvmSnapshot> field) {
        Gauge.builder(name, collector, c -> {
                    Optional<JmeterJvmSnapshot> snap = c.snapshot();
                    return snap.isPresent() ? field.applyAsDouble(snap.get()) : Double.NaN;
                })
                .description(help)
                .register(registry);
    }
}
