package com.perf.orchestrator.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JmeterJvmMetrics")
class JmeterJvmMetricsTest {

    // heapUsed, heapMax(2g), nonHeapUsed, gcYoungCount, gcYoungPauseMs,
    // gcOldCount, gcOldPauseMs, threadCount, cpu%, uptimeMs, loadedClasses
    private static final JmeterJvmSnapshot SNAP = new JmeterJvmSnapshot(
            512L * 1024 * 1024,
            2L * 1024 * 1024 * 1024,
            128L * 1024 * 1024,
            42, 1200, 3, 350,
            68, 73.5, 123_456L, 9001);

    @Test
    @DisplayName("registers jmeter_jvm_* gauges that report the live snapshot")
    void registers_and_reports_snapshot_values() {
        JmxMetricsCollector collector = mock(JmxMetricsCollector.class);
        when(collector.snapshot()).thenReturn(Optional.of(SNAP));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new JmeterJvmMetrics(collector).bindTo(registry);

        assertThat(gauge(registry, "jmeter.jvm.heap.used.bytes")).isEqualTo(512.0 * 1024 * 1024);
        assertThat(gauge(registry, "jmeter.jvm.heap.max.bytes")).isEqualTo(2.0 * 1024 * 1024 * 1024);
        assertThat(gauge(registry, "jmeter.jvm.nonheap.used.bytes")).isEqualTo(128.0 * 1024 * 1024);
        assertThat(gauge(registry, "jmeter.jvm.threads")).isEqualTo(68.0);
        assertThat(gauge(registry, "jmeter.jvm.gc.young.count")).isEqualTo(42.0);
        assertThat(gauge(registry, "jmeter.jvm.gc.old.pause.ms")).isEqualTo(350.0);
        assertThat(gauge(registry, "jmeter.jvm.cpu.percent")).isEqualTo(73.5);
        assertThat(gauge(registry, "jmeter.jvm.classes.loaded")).isEqualTo(9001.0);
        assertThat(gauge(registry, "jmeter.jvm.uptime.ms")).isEqualTo(123_456.0);
    }

    @Test
    @DisplayName("reports NaN for every gauge when JMeter is not running (empty snapshot)")
    void reports_nan_when_jmeter_not_running() {
        JmxMetricsCollector collector = mock(JmxMetricsCollector.class);
        when(collector.snapshot()).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new JmeterJvmMetrics(collector).bindTo(registry);

        assertThat(gauge(registry, "jmeter.jvm.heap.used.bytes")).isNaN();
        assertThat(gauge(registry, "jmeter.jvm.heap.max.bytes")).isNaN();
        assertThat(gauge(registry, "jmeter.jvm.threads")).isNaN();
        assertThat(gauge(registry, "jmeter.jvm.cpu.percent")).isNaN();
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        Gauge g = registry.find(name).gauge();
        assertThat(g).as("gauge %s should be registered", name).isNotNull();
        return g.value();
    }
}
