package com.perf.k8sorchestrator.config;

import com.perf.k8sorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.k8sorchestrator.domain.ApplicationCapacity;
import com.perf.k8sorchestrator.domain.MemberState;
import com.perf.k8sorchestrator.domain.MetricsTimeseries;
import com.perf.k8sorchestrator.domain.Run;
import com.perf.k8sorchestrator.domain.RunFleetMember;
import com.perf.k8sorchestrator.domain.RunState;
import com.perf.k8sorchestrator.domain.MetricsTimeseries.Series;
import com.perf.k8sorchestrator.domain.MetricsTimeseries.TimeseriesPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the JSON serializer used by the Redis cache
 * ({@link CacheConfig#jsonSerializer()}) round-trips the exact value shapes the
 * terminal-run metrics cache stores. This is the highest-risk part of the
 * "Redis everywhere" decision: the cached DTOs are Java <b>records</b> (final
 * classes, not {@code Serializable}), so the serializer must write the
 * {@code @class} type tag even for final types — done via
 * {@code DefaultTyping.EVERYTHING}. Without it, deserialization can't resolve
 * the concrete type and the cache hit would blow up at runtime (a failure mode
 * the {@code simple}-cache behaviour tests can't catch, since they don't
 * serialize).
 *
 * <p>Deterministic + container-free — exercises the serializer directly rather
 * than through a live Redis, so it runs in the unit phase.
 */
@DisplayName("Redis cache serializer — record / rollup round-trip (CACHE C-0)")
class CacheSerializationTest {

    private final GenericJackson2JsonRedisSerializer serializer = CacheConfig.jsonSerializer();

    @Test
    @DisplayName("MetricsTimeseries (nested records + status-code map) round-trips byte-for-byte")
    void timeseriesRoundTrips() {
        MetricsTimeseries original = new MetricsTimeseries(
                "01J0000000000000000000CACHE", 5, 1000L, 1300L,
                new Series(
                        List.of(new TimeseriesPoint(1000, 12.5), new TimeseriesPoint(1005, 9.0)),
                        List.of(new TimeseriesPoint(1000, 42.0)),
                        List.of(new TimeseriesPoint(1000, 0.0), new TimeseriesPoint(1005, 1.5)),
                        new LinkedHashMap<>(Map.of(
                                "200", List.of(new TimeseriesPoint(1000, 10.0)),
                                "500", List.of(new TimeseriesPoint(1005, 1.0))))));

        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back)
                .as("must deserialize to the concrete record type, not a LinkedHashMap")
                .isInstanceOf(MetricsTimeseries.class)
                .isEqualTo(original);
        MetricsTimeseries restored = (MetricsTimeseries) back;
        assertThat(restored.series().statusCodes().get("200").get(0).v()).isEqualTo(10.0);
        assertThat(restored.series().tps()).hasSize(2);
    }

    @Test
    @DisplayName("rollup List<Map<String,Object>> with mixed JDBC value types round-trips")
    void rollupRoundTrips() {
        // Mirrors MetricsRollupRepository.rollupByLabel output: a list of
        // column->value maps where values are the JDBC-mapped types Postgres
        // hands back (BigDecimal sums, Double averages, Long counts, String).
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", "GET /checkout");
        row.put("totalThroughput", new BigDecimal("1234"));
        row.put("errorRate", 0.0125d);
        row.put("avgP95Ms", 187.5d);
        row.put("rowCount", 42L);
        List<Map<String, Object>> original = List.of(row);

        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isInstanceOf(List.class).isEqualTo(original);
    }

    @Test
    @DisplayName("capacity grid: grouped Map<String,List<ApplicationCapacity>> (Instant fields) round-trips")
    void capacityGridRoundTrips() {
        // Shape stored by ApplicationCapacityRepository.findAllGroupedByApp() —
        // exercises Instant fields (JavaTimeModule) inside a nested
        // Map → List → record graph.
        Map<String, List<ApplicationCapacity>> original = new LinkedHashMap<>();
        original.put("01JAPP000000000000000000AA", List.of(
                new ApplicationCapacity("01JAPP000000000000000000AA", "us-east", 5,
                        Instant.parse("2026-05-26T10:00:00Z"), Instant.parse("2026-05-26T11:00:00Z")),
                new ApplicationCapacity("01JAPP000000000000000000AA", "us-west", 0, null, null)));

        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isInstanceOf(Map.class).isEqualTo(original);
    }

    @Test
    @DisplayName("Run metadata (enums + Instants + member with IMMUTABLE properties map) round-trips")
    void runMetadataRoundTrips() {
        // C-2 target. The load-bearing risk: RunFleetMember.properties is an
        // IMMUTABLE Map.copyOf(...), and default-typing + immutable collections
        // is a classic Jackson round-trip pitfall — a failure here would be a
        // runtime-only cache bug the `simple`-cache tests can't see.
        RunFleetMember member = new RunFleetMember(
                "01JRUN0000000000000000000A", "wkr-1", "us-east", MemberState.RUNNING,
                null, 202, "http://wkr-1:8080",
                Instant.parse("2026-05-26T10:00:00Z"), Instant.parse("2026-05-26T10:00:01Z"), null,
                Map.of("threads", "10", "rampUp", "30"), 1716717600L, 3L);
        Run original = new Run(
                "01JRUN0000000000000000000A", "us-east", "plan-blob", "data-blob",
                "checkout-svc", "erin", RunState.COMPLETED, "all members drained",
                Instant.parse("2026-05-26T09:59:00Z"), Instant.parse("2026-05-26T10:00:00Z"),
                Instant.parse("2026-05-26T10:05:00Z"), true, List.of(member));

        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isInstanceOf(Run.class).isEqualTo(original);
        Run restored = (Run) back;
        assertThat(restored.fleetMembers()).singleElement()
                .satisfies(m -> assertThat(m.properties()).containsEntry("threads", "10"));
    }

    @Test
    @DisplayName("LogsResult (terminal-member log tail) round-trips")
    void logsResultRoundTrips() {
        LogsResult original = new LogsResult(200, "2026-05-26 INFO line one\n2026-05-26 INFO line two\n");
        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);
        assertThat(back).isInstanceOf(LogsResult.class).isEqualTo(original);
    }
}
