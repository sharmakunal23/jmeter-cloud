package com.perf.metricsconsumer.jdbc;

import com.perf.metricsconsumer.model.WireBounds;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The writer's pure half: status folding, the probe filter, the bind order, bounds and clamps. */
class WorkerMetricWriterTest {

    private static WorkerMetricEntry entry(String label, long throughput, long errors, Map<String, Long> codes) {
        return new WorkerMetricEntry(label, throughput, errors, 0.0, 95.4, throughput * 100L,
                80.0, 120.0, 150.5, 220.0, 10.0, 500.0, 1201L, 1024L, 512L, codes, 50L);
    }

    @Test
    void status_codes_fold_into_the_five_buckets_exactly_as_hosted() {
        Map<String, Long> codes = new LinkedHashMap<>();
        codes.put("200", 98L);
        codes.put("302", 1L);
        codes.put("500", 2L);
        codes.put("abc", 3L);        // not numeric
        codes.put("99", 1L);         // not 3 chars
        codes.put("0200", 4L);       // not 3 chars
        codes.put("199", 5L);        // 1xx is "other"
        codes.put("Non HTTP response code: java.net.SocketTimeoutException", 6L);
        codes.put("404", null);      // null count = 0
        codes.put(null, 7L);
        HttpBuckets h = HttpBuckets.fold(codes);
        assertThat(h).isEqualTo(new HttpBuckets(98, 1, 0, 2, 3 + 1 + 4 + 5 + 6 + 7));
        assertThat(HttpBuckets.fold(null)).isEqualTo(HttpBuckets.NONE);
        assertThat(HttpBuckets.fold(Map.of())).isEqualTo(HttpBuckets.NONE);
    }

    @Test
    void the_probe_filter_keeps_only_labels_that_have_not_landed() {
        List<WorkerMetricWriter.Row> rows = List.of(
                new WorkerMetricWriter.Row(1, entry("a", 1, 0, Map.of())),
                new WorkerMetricWriter.Row(2, entry("b", 1, 0, Map.of())),
                new WorkerMetricWriter.Row(3, entry("c", 1, 0, Map.of())));
        assertThat(WorkerMetricWriter.rowsToInsert(rows, Set.of())).hasSize(3);
        assertThat(WorkerMetricWriter.rowsToInsert(rows, Set.of(2L))).extracting(WorkerMetricWriter.Row::labelId).containsExactly(1L, 3L);
        assertThat(WorkerMetricWriter.rowsToInsert(rows, Set.of(1L, 2L, 3L))).isEmpty();
    }

    @Test
    void rows_inserted_counts_success_no_info_as_one_and_suppressed_duplicates_as_zero() {
        assertThat(WorkerMetricWriter.countInserted(new int[] {1, 0, Statement.SUCCESS_NO_INFO, 1})).isEqualTo(3);
        assertThat(WorkerMetricWriter.countInserted(new int[] {})).isZero();
    }

    @Test
    void the_fact_table_name_is_validated_before_it_is_spliced() {
        assertThat(WorkerMetricWriter.requireIdentifier("CPS_METRICS")).isEqualTo("CPS_METRICS");
        for (String bad : List.of("CPS.METRICS", "\"cps\"", "1CPS", "CPS_METRICS; DROP", "", "A".repeat(129))) {
            assertThatThrownBy(() -> WorkerMetricWriter.requireIdentifier(bad)).isInstanceOf(IllegalStateException.class);
        }
        WorkerMetricWriter w = new WorkerMetricWriter(null, null, 5000);
        assertThat(w.insertSqlFor("CPS_METRICS"))
                .startsWith("INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(CPS_METRICS(RUN_ID,WORKER_ID,LABEL_ID,WINDOW_SECOND)) */ INTO CPS_METRICS (")
                .endsWith("VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        assertThat(w.probeSqlFor("CPS_METRICS"))
                .isEqualTo("SELECT LABEL_ID FROM CPS_METRICS WHERE RUN_ID = ? AND WORKER_ID = ? AND WINDOW_SECOND = ?");
        assertThatThrownBy(() -> new WorkerMetricWriter(null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labels_are_truncated_to_the_column_bytes_not_rejected() {
        assertThat(WorkerMetricWriter.boundedLabel("short")).isEqualTo("short");
        String ascii = "L".repeat(WireBounds.LABEL_BYTES + 50);
        assertThat(WorkerMetricWriter.boundedLabel(ascii)).hasSize(WireBounds.LABEL_BYTES);
        String multibyte = "é".repeat(600);   // 1200 bytes
        String cut = WorkerMetricWriter.boundedLabel(multibyte);
        assertThat(cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(WireBounds.LABEL_BYTES);
        assertThat(cut).hasSize(500);
    }

    @Test
    void binds_the_hosted_21_positions_with_the_exact_mean_and_maximum() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        WorkerMetricEntry e = entry("GET /api/foo", 105, 2, Map.of("200", 98L, "302", 1L, "500", 2L, "abc", 3L, "99", 1L));
        WorkerMetricWriter.bindRow(ps, 4711L, 88L, 1778457600L, new WorkerMetricWriter.Row(9001L, e));
        verify(ps).setLong(1, 4711L);
        verify(ps).setLong(2, 88L);
        verify(ps).setLong(3, 9001L);
        verify(ps).setLong(4, 1778457600L);
        verify(ps).setLong(5, 105L);
        verify(ps).setLong(6, 2L);
        verify(ps).setDouble(7, 100.0);        // sumElapsedMs / throughput, not the wire's avg
        verify(ps).setDouble(8, 80.0);
        verify(ps).setDouble(11, 220.0);
        verify(ps).setDouble(12, 10.0);
        verify(ps).setDouble(13, 1201.0);      // rawMaxMs — the exact maximum
        verify(ps).setLong(14, 1024L);
        verify(ps).setLong(15, 512L);
        verify(ps).setLong(16, 98L);
        verify(ps).setLong(17, 1L);
        verify(ps).setLong(18, 0L);
        verify(ps).setLong(19, 2L);
        verify(ps).setLong(20, 4L);
        verify(ps).setLong(21, 50L);
        ArgumentCaptor<Integer> positions = ArgumentCaptor.forClass(Integer.class);
        verify(ps, org.mockito.Mockito.atLeast(14)).setLong(positions.capture(), org.mockito.ArgumentMatchers.anyLong());
        verify(ps, org.mockito.Mockito.never()).setLong(eq(22), org.mockito.ArgumentMatchers.anyLong());
        verify(ps, org.mockito.Mockito.never()).setDouble(eq(22), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void a_producer_without_sum_or_raw_max_falls_back_to_the_wire_values() {
        WorkerMetricEntry legacy = new WorkerMetricEntry("x", 10, 0, 0.0, 42.5, null,
                1, 1, 1, 1, 1, 777.0, 0L, 0, 0, Map.of(), 1);
        assertThat(WorkerMetricWriter.averageMs(legacy)).isEqualTo(42.5);
        assertThat(WorkerMetricWriter.maxMs(legacy)).isEqualTo(777.0);
        WorkerMetricEntry zero = new WorkerMetricEntry("x", 0, 0, 0.0, 0.0, 0L, 0, 0, 0, 0, 0, 0, 0L, 0, 0, Map.of(), 0);
        assertThat(WorkerMetricWriter.averageMs(zero)).isZero();
    }

    @Test
    void out_of_range_values_are_clamped_not_rejected() {
        assertThat(WorkerMetricWriter.clampCount(WorkerMetricWriter.MAX_COUNT + 1, "THROUGHPUT")).isEqualTo(WorkerMetricWriter.MAX_COUNT);
        assertThat(WorkerMetricWriter.clampCount(-5, "THROUGHPUT")).isZero();
        assertThat(WorkerMetricWriter.clampLatency(1e12, "P99_MS")).isEqualTo(WorkerMetricWriter.MAX_LATENCY_MS);
        assertThat(WorkerMetricWriter.clampLatency(Double.NaN, "P99_MS")).isZero();
        assertThat(WorkerMetricWriter.clampLatency(-1, "P99_MS")).isZero();
        assertThat(WorkerMetricWriter.clampLatency(12.3, "P99_MS")).isEqualTo(12.3);
    }
}
