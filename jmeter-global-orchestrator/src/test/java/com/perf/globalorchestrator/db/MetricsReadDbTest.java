package com.perf.globalorchestrator.db;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.MetricsPurgeRepository;
import com.perf.globalorchestrator.repo.MetricsTarget;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository.Query;
import com.perf.globalorchestrator.repo.RunMetricsRepository;
import com.perf.globalorchestrator.repo.RunWindow;
import com.perf.globalorchestrator.service.MetricsGroupResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read path against the real group tables on Oracle Free: rows written the
 * consumer's way into {@code CPS_METRICS} are resolved through the control
 * plane's application → group → registry chain and come back bucketed,
 * throughput-weighted, split by region and application, folded per label, and
 * purged bounded by the run's window.
 */
@SpringBootTest(properties = {
        "globalOrchestrator.pod.sweepInitialDelayMs=3600000",
        "globalOrchestrator.pod.lostAfterMs=3600000"
})
@DisplayName("metrics read path on Oracle — group tables → timeseries, rollup, purge")
class MetricsReadDbTest extends OracleDbTestSupport {

    @Autowired ApplicationGroupRepository groups;
    @Autowired ApplicationRepository applications;
    @Autowired MetricsGroupResolver resolver;
    @Autowired MetricsTimeseriesRepository timeseries;
    @Autowired RunMetricsRepository runMetrics;
    @Autowired MetricsPurgeRepository purge;

    private final JdbcTemplate metrics = owner();

    private static final long T0 = 1_780_000_020L - (1_780_000_020L % 15);   // a 15 s-aligned window start

    private long dim(String sql, Object... args) {
        metrics.update(sql, args);
        return metrics.queryForObject("SELECT MAX(" + (sql.contains("INTO RUN ") ? "RUN_ID" : sql.contains("INTO WORKER") ? "WORKER_ID" : "LABEL_ID")
                + ") FROM " + (sql.contains("INTO RUN ") ? "RUN" : sql.contains("INTO WORKER") ? "WORKER" : "LABEL"), Long.class);
    }

    private void fact(long runId, long workerId, long labelId, long window, long tp, long err, double avg, double p95, long h2, long h5) {
        metrics.update("INSERT INTO CPS_METRICS (RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND, THROUGHPUT, ERROR_COUNT, AVG_MS, "
                + "P50_MS, P90_MS, P95_MS, P99_MS, MIN_MS, MAX_MS, BYTES_RECV, BYTES_SENT, HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX, HTTP_OTHER, ACTIVE_THREADS) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, workerId, labelId, window, tp, err, avg, avg * 0.8, avg * 1.5, p95, p95 * 2, 5.0, 900.0, 100L, 50L, h2, 0L, 0L, h5, 0L, 10L);
    }

    @Test
    void timeseries_rollup_and_purge_read_the_runs_group_table() {
        // Control plane: group cps, app cps-pci in it, a COMPLETED run tagged with the app.
        if (groups.findById("cps").isEmpty()) {
            groups.insert(new ApplicationGroup("cps", "Servicing MQ", null, Instant.now(), null));
        }
        applications.insert(new Application("app-read", "cps-read", null, null, List.of(), Instant.now(), null, null, null,
                "cps", "CPS-PCI"));
        Instant start = Instant.ofEpochSecond(T0);
        Run run = new Run("01J0READRUNAAAAAAAAAAAAAAA", "na-east", "b", null, "cps-read", "t", RunState.COMPLETED, null,
                start, start, start.plusSeconds(120), false, null);

        // Metrics side, written the consumer's way: dims + 15 s rows for 2 workers × 2 labels × 4 windows.
        long runId = dim("INSERT INTO RUN (GROUP_ID, RUN_KEY, FIRST_SEEN) VALUES ('CPS', ?, ?)", run.runId(), T0);
        long east = dim("INSERT INTO WORKER (RUN_ID, GROUP_ID, WORKER_KEY, REGION, JOINED_AT_SECOND) VALUES (?, 'CPS', 'w-east', 'na-east', 0)", runId);
        long west = dim("INSERT INTO WORKER (RUN_ID, GROUP_ID, WORKER_KEY, REGION, JOINED_AT_SECOND) VALUES (?, 'CPS', 'w-west', 'na-west', 0)", runId);
        long login = dim("INSERT INTO LABEL (GROUP_ID, LABEL_KEY, APPLICATION, FIRST_SEEN) VALUES ('CPS', 'TG1 login read', 'CPS', ?)", T0);
        long pay = dim("INSERT INTO LABEL (GROUP_ID, LABEL_KEY, APPLICATION, FIRST_SEEN) VALUES ('CPS', 'TG5 pay read', 'CPS-PCI', ?)", T0);
        for (int i = 0; i < 4; i++) {
            long w = T0 + 15L * i;
            fact(runId, east, login, w, 150, 3, 100.0, 300.0, 147, 3);
            fact(runId, east, pay,   w,  50, 1, 200.0, 500.0,  49, 1);
            fact(runId, west, login, w, 100, 0, 120.0, 320.0, 100, 0);
            fact(runId, west, pay,   w,  30, 0, 220.0, 520.0,  30, 0);
        }

        Optional<MetricsTarget> target = resolver.resolve(run);
        assertThat(target).isPresent();
        assertThat(target.get()).isEqualTo(new MetricsTarget("cps", "CPS", "CPS_METRICS", "CPS_METRICS_H", runId));
        RunWindow window = RunWindow.of(run);

        // 15 s buckets: 4 points; per bucket 330 samples over 15 s = 22 TPS; weighted mean = (150·100+50·200+100·120+30·220)/330.
        MetricsTimeseries ts = timeseries.timeseries(run.runId(), target.get(), window, Query.AGGREGATE, 0);
        assertThat(ts.bucketSize()).isEqualTo(15);
        assertThat(ts.series().tps()).hasSize(4);
        assertThat(ts.series().tps().get(0).sec()).isEqualTo(T0);
        assertThat(ts.series().tps().get(0).v()).isEqualTo(22.0);
        assertThat(ts.series().avgRtMs().get(0).v()).isCloseTo(43_600.0 / 330, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ts.series().errorPct().get(0).v()).isCloseTo(100.0 * 4 / 330, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ts.series().p95Ms().get(0).v()).isCloseTo((150 * 300.0 + 50 * 500 + 100 * 320 + 30 * 520) / 330, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ts.series().statusCodes().keySet()).containsExactly("2xx", "5xx");
        assertThat(ts.series().statusCodes().get("5xx").get(0).v()).isCloseTo(4.0 / 15, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ts.fromSecond()).isEqualTo(T0);
        assertThat(ts.toSecond()).isEqualTo(T0 + 45);

        // 60 s granularity folds the four windows into one point; a 30 s window + settle trims the tail.
        MetricsTimeseries coarse = timeseries.timeseries(run.runId(), target.get(), window, new Query(false, false, 60, null), 0);
        assertThat(coarse.bucketSize()).isEqualTo(60);
        assertThat(coarse.series().tps()).hasSize(1);
        assertThat(coarse.series().tps().get(0).v()).isEqualTo(330.0 * 4 / 60);
        MetricsTimeseries tail = timeseries.timeseries(run.runId(), target.get(), window, new Query(false, false, null, 30L), 15);
        assertThat(tail.series().tps()).extracting(MetricsTimeseries.TimeseriesPoint::sec).containsExactly(T0 + 15, T0 + 30);

        // Splits: by region from WORKER, by application from LABEL.
        MetricsTimeseries split = timeseries.timeseries(run.runId(), target.get(), window, new Query(true, true, 15, null), 0);
        assertThat(split.regions().keySet()).containsExactly("na-east", "na-west");
        assertThat(split.regions().get("na-east").tps().get(0).v()).isEqualTo(200.0 / 15);
        assertThat(split.applications().keySet()).containsExactly("CPS", "CPS-PCI");
        assertThat(split.applications().get("CPS-PCI").tps().get(0).v()).isEqualTo(80.0 / 15);

        // The per-label split: every label, then an exact prefix (bound as an escaped LIKE pattern).
        MetricsTimeseries labels = timeseries.timeseries(run.runId(), target.get(), window, new Query(false, false, true, null, 15, null), 0);
        assertThat(labels.labels().keySet()).containsExactly("TG1 login read", "TG5 pay read");
        assertThat(labels.labelsTotal()).isEqualTo(2);
        assertThat(labels.labels().get("TG5 pay read").tps().get(0).v()).isEqualTo(80.0 / 15);
        MetricsTimeseries prefixed = timeseries.timeseries(run.runId(), target.get(), window, new Query(false, false, true, "TG5", 15, null), 0);
        assertThat(prefixed.labels().keySet()).containsExactly("TG5 pay read");
        assertThat(prefixed.labelsTotal()).isEqualTo(1);
        assertThat(timeseries.timeseries(run.runId(), target.get(), window, new Query(false, false, true, "TG_", 15, null), 0).labels()).isEmpty();

        // The summary: the total and one row per application from one ROLLUP statement; tps over the rows' span.
        RunSummary summary = runMetrics.summary(run.runId(), target.get(), window);
        assertThat(summary.total().samples()).isEqualTo(1320);
        assertThat(summary.total().errors()).isEqualTo(16);   // HTTP 5xx: (3 + 1) × 4 windows
        assertThat(summary.total().errorPct()).isCloseTo(100.0 * 16 / 1320, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(summary.total().tps()).isEqualTo(1320.0 / 60);
        assertThat(summary.total().avgMs()).isCloseTo(174_400.0 / 1320, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(summary.total().p90Ms()).isCloseTo(1.5 * 174_400.0 / 1320, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(summary.total().maxMs()).isEqualTo(900.0);
        assertThat(summary.total().maxActiveThreads()).isEqualTo(10);
        assertThat(summary.fromSecond()).isEqualTo(T0);
        assertThat(summary.toSecond()).isEqualTo(T0 + 45);
        assertThat(summary.byApplication()).extracting(RunSummary.Stats::application).containsExactly("CPS", "CPS-PCI");
        assertThat(summary.byApplication().get(0).samples()).isEqualTo(1000);
        assertThat(summary.byApplication().get(1).tps()).isEqualTo(320.0 / 60);

        // Per-label rollup with the keys the UI's aggregate report and the AI digest read; busiest first.
        List<Map<String, Object>> rollup = runMetrics.rollupByLabel(target.get(), window);
        assertThat(rollup).hasSize(2);
        Map<String, Object> first = rollup.get(0);
        assertThat(first.get("label")).isEqualTo("TG1 login read");
        assertThat(first.get("application")).isEqualTo("CPS");
        assertThat(((Number) first.get("totalThroughput")).longValue()).isEqualTo(1000);
        assertThat(((Number) first.get("totalErrors")).longValue()).isEqualTo(12);
        assertThat(((Number) first.get("httpErrors")).longValue()).isEqualTo(12);
        assertThat(((Number) first.get("httpErrorRate")).doubleValue()).isCloseTo(12.0 / 1000, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) first.get("avgP95Ms")).doubleValue()).isCloseTo((600 * 300.0 + 400 * 320) / 1000, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(((Number) first.get("avgP90Ms")).doubleValue()).isCloseTo(1.5 * (600 * 100.0 + 400 * 120) / 1000, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(((Number) first.get("throughputRps")).doubleValue()).isEqualTo(1000.0 / 60);
        assertThat(((Number) first.get("rowCount")).longValue()).isEqualTo(8);
        assertThat(runMetrics.rollupByLabel(target.get(), window, MetricsTimeseriesRepository.likePrefix("TG5")))
                .extracting(m -> m.get("label")).containsExactly("TG5 pay read");
        assertThat(runMetrics.rollupByLabel(target.get(), window, null, 1))
                .extracting(m -> m.get("label")).containsExactly("TG1 login read");   // the busiest, FETCH FIRST 1
        RunMetricsRepository.RunAggregate agg = runMetrics.runAggregate(target.get(), window);
        assertThat(agg.rowCount()).isEqualTo(16);
        assertThat(agg.errorRate()).isCloseTo(16.0 / 1320, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(agg.throughputRps()).isEqualTo(1320.0 / 60);

        // Purge: facts and the run-scoped dimensions go, the shared labels stay.
        assertThat(purge.deleteRun(target.get(), window)).isEqualTo(16);
        assertThat(metrics.queryForObject("SELECT COUNT(*) FROM CPS_METRICS WHERE RUN_ID = ?", Integer.class, runId)).isZero();
        assertThat(metrics.queryForObject("SELECT COUNT(*) FROM WORKER WHERE RUN_ID = ?", Integer.class, runId)).isZero();
        assertThat(metrics.queryForObject("SELECT COUNT(*) FROM RUN WHERE RUN_ID = ?", Integer.class, runId)).isZero();
        assertThat(metrics.queryForObject("SELECT COUNT(*) FROM LABEL WHERE LABEL_ID IN (?, ?)", Integer.class, login, pay)).isEqualTo(2);
        resolver.forgetRun(run.runId());
        assertThat(resolver.resolve(run)).isEmpty();
    }
}
