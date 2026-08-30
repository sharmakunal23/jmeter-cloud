package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reader's pure half: bucket choice, the statement shapes, the fold from component sums to series, the run window. */
class MetricsTimeseriesRepositoryTest {

    private static final MetricsTarget CPS = new MetricsTarget("cps", "CPS", "CPS_METRICS", "CPS_METRICS_H", 4711L);
    private static final MetricsTarget NO_HISTORY = new MetricsTarget("demo", "DEMO", "DEMO_METRICS", null, 9L);

    @Test
    void bucket_is_the_smallest_grafana_granularity_under_the_point_cap() {
        assertThat(MetricsTimeseriesRepository.bucketSecondsFor(15 * 1500)).isEqualTo(15);       // 6.25 h at 15 s
        assertThat(MetricsTimeseriesRepository.bucketSecondsFor(15 * 1500 + 1)).isEqualTo(30);
        assertThat(MetricsTimeseriesRepository.bucketSecondsFor(30 * 1500 + 1)).isEqualTo(60);
        assertThat(MetricsTimeseriesRepository.bucketSecondsFor(48 * 3600)).isEqualTo(60);      // capped, points grow
        assertThat(MetricsTimeseriesRepository.bucketSecondsFor(1)).isEqualTo(15);
    }

    @Test
    void every_statement_carries_run_id_and_a_window_range_and_buckets_on_the_partition_key() {
        String agg = MetricsTimeseriesRepository.aggregateSql(CPS);
        assertThat(agg).contains("FROM CPS_METRICS WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?")
                .contains("UNION ALL").contains("FROM CPS_METRICS_H WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?")
                .contains("FLOOR(x.WINDOW_SECOND / ?) * ? AS sec").contains("GROUP BY b.sec")
                .doesNotContainIgnoringCase("UPPER(").doesNotContainIgnoringCase("LIKE");
        assertThat(MetricsTimeseriesRepository.aggregateSql(NO_HISTORY)).doesNotContain("UNION ALL");
        // The region split joins WORKER on hot rows only.
        String region = MetricsTimeseriesRepository.byRegionSql(CPS);
        assertThat(region).contains("JOIN WORKER w ON w.WORKER_ID = b.WORKER_ID").doesNotContain("UNION ALL");
        assertThat(MetricsTimeseriesRepository.byApplicationSql(CPS)).contains("JOIN LABEL l ON l.LABEL_ID = b.LABEL_ID").contains("UNION ALL");
        // Bind counts match the statement: 2 (bucket) + 3 (+3 history).
        assertThat(MetricsTimeseriesRepository.args(CPS, new RunWindow(10, 20), 15, true)).hasSize(8);
        assertThat(MetricsTimeseriesRepository.args(CPS, new RunWindow(10, 20), 15, false)).hasSize(5);
    }

    @Test
    void table_names_are_validated_before_they_are_spliced() {
        assertThatThrownBy(() -> new MetricsTarget("x", "X", "X_METRICS; DROP TABLE RUN", null, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MetricsTarget("x", "X", "X_METRICS", "\"x\"", 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new MetricsTarget("x", "X", "X_METRICS", " ", 1).historyTable()).isNull();
    }

    @Test
    void series_fold_component_sums_per_bucket_and_divide_at_the_end() {
        // One 60 s bucket: 4 windows × 2 workers folded upstream into sums.
        MetricsTimeseriesRepository.Row r = new MetricsTimeseriesRepository.Row(
                1_000_020L, null, 600, 6, 600 * 120.0, 600 * 300.0, 600 * 900.0, 590, 2, 5, 3, 0);
        Series s = MetricsTimeseriesRepository.series(List.of(r), 60);
        assertThat(s.tps()).containsExactly(new com.perf.globalorchestrator.domain.MetricsTimeseries.TimeseriesPoint(1_000_020L, 10.0));
        assertThat(s.avgRtMs().get(0).v()).isEqualTo(120.0);
        assertThat(s.p95Ms().get(0).v()).isEqualTo(300.0);
        assertThat(s.p99Ms().get(0).v()).isEqualTo(900.0);
        assertThat(s.errorPct().get(0).v()).isEqualTo(1.0);
        assertThat(s.statusCodes().keySet()).containsExactly("2xx", "3xx", "4xx", "5xx");   // "other" all-zero → dropped
        assertThat(s.statusCodes().get("4xx").get(0).v()).isCloseTo(5.0 / 60, org.assertj.core.data.Offset.offset(1e-9));
        // An empty bucket divides to zeros, never NaN.
        Series z = MetricsTimeseriesRepository.series(List.of(new MetricsTimeseriesRepository.Row(1, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), 15);
        assertThat(z.avgRtMs().get(0).v()).isZero();
        assertThat(z.statusCodes()).isEmpty();
    }

    @Test
    void split_groups_rows_by_region_or_application_in_sorted_order() {
        List<MetricsTimeseriesRepository.Row> rows = List.of(
                new MetricsTimeseriesRepository.Row(15, "na-west", 30, 0, 0, 0, 0, 30, 0, 0, 0, 0),
                new MetricsTimeseriesRepository.Row(15, "na-east", 60, 0, 0, 0, 0, 60, 0, 0, 0, 0),
                new MetricsTimeseriesRepository.Row(30, "na-east", 15, 0, 0, 0, 0, 15, 0, 0, 0, 0));
        Map<String, Series> by = MetricsTimeseriesRepository.split(rows, 15);
        assertThat(by.keySet()).containsExactly("na-east", "na-west");
        assertThat(by.get("na-east").tps()).hasSize(2);
        assertThat(by.get("na-east").tps().get(0).v()).isEqualTo(4.0);
    }

    @Test
    void the_run_window_brackets_the_run_with_a_minute_of_slack_and_never_inverts() {
        Instant start = Instant.parse("2026-08-29T10:00:00Z");
        Run live = new Run("r", "na-east", "b", null, "cps-pci", "t", RunState.RUNNING, null, start, start, null, false, null);
        RunWindow w = RunWindow.of(live, start.plusSeconds(600));
        assertThat(w.lo()).isEqualTo(start.getEpochSecond() - 60);
        assertThat(w.hi()).isEqualTo(start.getEpochSecond() + 600 + 60);
        Run done = new Run("r", "na-east", "b", null, "cps-pci", "t", RunState.COMPLETED, null, start, start, start.plusSeconds(120), false, null);
        assertThat(RunWindow.of(done).hi()).isEqualTo(start.getEpochSecond() + 120 + 60);
        assertThat(w.narrowTo(w.lo() + 30, w.hi() - 30)).isEqualTo(new RunWindow(w.lo() + 30, w.hi() - 30));
        assertThat(w.narrowTo(0, Long.MAX_VALUE)).isEqualTo(w);
    }
}
