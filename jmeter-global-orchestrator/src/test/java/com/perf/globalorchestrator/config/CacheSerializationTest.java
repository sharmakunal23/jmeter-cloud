package com.perf.globalorchestrator.config;

import com.perf.globalorchestrator.cache.CacheValueCodec;
import com.perf.globalorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.MetricsTimeseries.TimeseriesPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins that {@link CacheValueCodec} — what turns a cached value into the bytes
 * stored in {@code ORCH_CACHE.CACHE_VALUE} — round-trips the exact value shapes
 * the hub's caches hold. This is the highest-risk part of storing the cache
 * outside the process: the cached DTOs are Java <b>records</b> (final classes,
 * not {@code Serializable}), so the codec must write the {@code @class} type tag
 * even for final types — done via {@code DefaultTyping.EVERYTHING}. Without it,
 * decoding can't resolve the concrete type and the cache hit would blow up at
 * runtime (a failure mode the {@code simple}-cache behaviour tests can't catch,
 * since they don't serialize).
 *
 * <p>Deterministic + container-free — exercises the codec directly rather than
 * through a database, so it runs in the unit phase.
 */
@DisplayName("Cache value codec — record / rollup round-trip (CACHE-ORACLE)")
class CacheSerializationTest {

    private final CacheValueCodec codec = new CacheValueCodec();

    private byte[] serialize(Object value) { return codec.encode(value); }
    private Object deserialize(byte[] bytes) { return codec.decode(bytes); }

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

        byte[] bytes = serialize(original);
        Object back = deserialize(bytes);

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
        // column->value maps where values are the JDBC-mapped types the driver
        // hands back (BigDecimal sums, Double averages, Long counts, String).
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", "GET /checkout");
        row.put("totalThroughput", new BigDecimal("1234"));
        row.put("errorRate", 0.0125d);
        row.put("avgP95Ms", 187.5d);
        row.put("rowCount", 42L);
        List<Map<String, Object>> original = List.of(row);

        byte[] bytes = serialize(original);
        Object back = deserialize(bytes);

        assertThat(back).isInstanceOf(List.class).isEqualTo(original);
    }

    @Test
    @DisplayName("capacity grid: grouped Map<String,List<GroupCapacity>> (Instant fields) round-trips")
    void capacityGridRoundTrips() {
        // Shape stored by GroupCapacityRepository.findAllGroupedByGroup() —
        // exercises Instant fields (JavaTimeModule) inside a nested
        // Map → List → record graph.
        Map<String, List<GroupCapacity>> original = new LinkedHashMap<>();
        original.put("cps", List.of(
                new GroupCapacity("cps", "us-east", 5,
                        Instant.parse("2026-05-26T10:00:00Z"), Instant.parse("2026-05-26T11:00:00Z")),
                new GroupCapacity("cps", "us-west", 0, null, null)));

        byte[] bytes = serialize(original);
        Object back = deserialize(bytes);

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

        byte[] bytes = serialize(original);
        Object back = deserialize(bytes);

        assertThat(back).isInstanceOf(Run.class).isEqualTo(original);
        Run restored = (Run) back;
        assertThat(restored.fleetMembers()).singleElement()
                .satisfies(m -> assertThat(m.properties()).containsEntry("threads", "10"));
    }

    @Test
    @DisplayName("LogsResult (terminal-member log tail) round-trips")
    void logsResultRoundTrips() {
        LogsResult original = new LogsResult(200, "2026-05-26 INFO line one\n2026-05-26 INFO line two\n");
        byte[] bytes = serialize(original);
        Object back = deserialize(bytes);
        assertThat(back).isInstanceOf(LogsResult.class).isEqualTo(original);
    }

    @Test
    @DisplayName("RunSummary (nested Stats, a null application on the total) round-trips")
    void summaryRoundTrips() {
        RunSummary original = new RunSummary("01J0000000000000000000CACHE", 1000L, 1300L,
                new RunSummary.Stats(null, 1320, 16, 22.0, 1.2121, 132.1, 198.2, 320.5, 640.0, 900.0, 10),
                List.of(new RunSummary.Stats("CPS", 1000, 12, 16.6, 1.2, 108.0, 162.0, 308.0, 616.0, 900.0, 10)));
        Object back = deserialize(serialize(original));
        assertThat(back).isInstanceOf(RunSummary.class).isEqualTo(original);
    }

    @Test
    @DisplayName("the stored bytes are gzip, and a large timeseries compresses hard")
    void valuesAreGzipped() {
        List<MetricsTimeseries.TimeseriesPoint> tps = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) tps.add(new MetricsTimeseries.TimeseriesPoint(1000 + i * 15L, 12.5));
        MetricsTimeseries big = new MetricsTimeseries("01J0000000000000000000CACHE", 5, 1000L, 31000L,
                new Series(tps, List.of(), List.of(), new LinkedHashMap<>()));

        byte[] stored = serialize(big);

        // gzip magic — the column holds compressed bytes, not raw JSON.
        assertThat(stored[0] & 0xff).isEqualTo(0x1f);
        assertThat(stored[1] & 0xff).isEqualTo(0x8b);
        // The point of compressing at all: this is what keeps a cache entry a
        // small inline LOB instead of a separate segment read on every hit.
        int rawJsonBytes = gunzip(stored).length;
        assertThat(stored.length).isLessThan(rawJsonBytes / 5);
        assertThat(deserialize(stored)).isEqualTo(big);
    }

    private static byte[] gunzip(byte[] gz) {
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("a stored row naming a class outside the allow-list is refused, not instantiated")
    void refusesForeignTypeTag() {
        // What an attacker who can write one ORCH_CACHE row would try: name a
        // class the service never caches and let the decode construct it.
        byte[] forged = gzip(("{\"@class\":\"java.io.File\",\"path\":\"/etc/passwd\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> deserialize(forged))
                .hasStackTraceContaining("java.io.File")
                .hasStackTraceContaining("denied resolution");
    }

    private static byte[] gzip(byte[] raw) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("a corrupt stored value fails loudly rather than returning a half-read object")
    void corruptValueThrows() {
        assertThatThrownBy(() -> deserialize(new byte[] {1, 2, 3, 4}))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
