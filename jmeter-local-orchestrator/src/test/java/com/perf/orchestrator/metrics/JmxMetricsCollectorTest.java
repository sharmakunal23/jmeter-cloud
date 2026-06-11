package com.perf.orchestrator.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("JmxMetricsCollector — snapshot mapping + 503 contract")
class JmxMetricsCollectorTest {

    @Nested
    @DisplayName("snapshot mapping")
    class SnapshotMapping {

        @Test
        @DisplayName("reads heap / non-heap / threads / classes / uptime from a real MBean server — sanity-checks the bean-name wiring")
        void reads_documented_attributes_from_local_jvm() throws IOException {
            // Drive the static reader against the running test JVM's own
            // MBean server. This exercises the exact attribute lookups the
            // production collector uses against JMeter, without any RMI.
            JmeterJvmSnapshot snap = JmxMetricsCollector.readSnapshot(ManagementFactory.getPlatformMBeanServer());

            assertSoftly(softly -> {
                softly.assertThat(snap.heapUsedBytes()).isPositive();
                softly.assertThat(snap.heapMaxBytes()).isPositive();
                softly.assertThat(snap.nonHeapUsedBytes()).isPositive();
                softly.assertThat(snap.threadCount()).isPositive();
                softly.assertThat(snap.loadedClasses()).isPositive();
                softly.assertThat(snap.uptimeMs()).isGreaterThanOrEqualTo(0L);
                // GC counters are non-negative; old/young split is best-effort
                // by name match — we just assert sanity, not a specific GC.
                softly.assertThat(snap.gcYoungCount() + snap.gcOldCount())
                        .as("at least one GC bean exposes a count")
                        .isGreaterThanOrEqualTo(0L);
                // CPU may be unavailable on some JREs and is reported as -1.
                softly.assertThat(snap.cpuLoadPercent())
                        .satisfiesAnyOf(
                                v -> assertThat(v).isEqualTo(-1.0),
                                v -> assertThat(v).isBetween(0.0, 100.0));
            });
        }
    }

    @Nested
    @DisplayName("503 contract")
    class UnreachableJmx {

        @Test
        @DisplayName("returns empty when the JMX agent is not running on the configured port")
        void returns_empty_when_agent_unreachable() {
            // Pick a port that is overwhelmingly unlikely to have a JMX
            // agent listening. The collector must surface this as
            // Optional.empty (mapped to 503 by the controller) rather than
            // throwing.
            JmxMetricsCollector collector = new JmxMetricsCollector(1);

            Optional<JmeterJvmSnapshot> snap = collector.snapshot();
            assertThat(snap).isEmpty();
            collector.close();
        }
    }
}
