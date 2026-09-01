package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.MetricsTimeseries.TimeseriesPoint;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.repo.AiResponseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The digest math Claude's answer rests on. Every case here is a number the
 * model would otherwise be told wrongly — a scale error, an unweighted mean, or
 * a rounding step that erases a whole class of errors.
 */
@DisplayName("AiInsightsService — the digest handed to Claude")
class AiInsightsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiInsightsService service() {
        return new AiInsightsService(
                mock(RunService.class), mock(CachingMetricsService.class), mock(AiClient.class),
                new AiQuotaGuard(10), mock(AiResponseRepository.class), MAPPER);
    }

    // ── statusTotals ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("statusTotals — rates become counts")
    class StatusTotals {

        @Test
        @DisplayName("multiplies each per-second rate by the bucket width")
        void scalesByBucketWidth() {
            // 120 buckets of 15 s; 2xx runs at 133.3 req/s → 133.3 × 15 × 120 = 240,000.
            Map<String, List<TimeseriesPoint>> codes = Map.of("2xx", flat(120, 2000.0 / 15));
            assertThat(AiInsightsService.statusTotals(codes, 15))
                    .containsEntry("2xx", 240_000L);
        }

        @Test
        @DisplayName("a 60 s bucket gives the same count as a 15 s one for the same run")
        void independentOfGranularity() {
            // The same 1,800 requests, seen at two granularities: 30 buckets of 60 s
            // at 1/s, or 120 buckets of 15 s at 1/s. Both are 1,800 requests.
            assertThat(AiInsightsService.statusTotals(Map.of("5xx", flat(30, 1.0)), 60))
                    .containsEntry("5xx", 1_800L);
            assertThat(AiInsightsService.statusTotals(Map.of("5xx", flat(120, 1.0)), 15))
                    .containsEntry("5xx", 1_800L);
        }

        @Test
        @DisplayName("a sub-0.5/s class is counted, not floored to zero")
        void lowRateClassSurvives() {
            // 0.3 5xx/s over 240 buckets of 15 s = 1,080 server errors. Rounding
            // each point first reports ZERO, and the model then says the run had
            // no server errors while the error percentage says otherwise.
            Map<String, List<TimeseriesPoint>> codes = Map.of("5xx", flat(240, 0.3));
            assertThat(AiInsightsService.statusTotals(codes, 15))
                    .containsEntry("5xx", 1_080L);
        }

        @Test
        @DisplayName("null map and a zero bucket width are safe")
        void degenerateInputs() {
            assertThat(AiInsightsService.statusTotals(null, 15)).isEmpty();
            assertThat(AiInsightsService.statusTotals(Map.of("2xx", flat(10, 1.0)), 0))
                    .containsEntry("2xx", 10L);
        }
    }

    // ── duration + shape ──────────────────────────────────────────────────

    @Nested
    @DisplayName("durationSeconds — the last bucket has width too")
    class Duration {

        @Test
        @DisplayName("counts the final bucket's own width, not just the gap between starts")
        void includesTrailingBucket() {
            // Buckets start at 0 and end at 285, 15 s wide: the run spans 300 s.
            MetricsTimeseries ts = new MetricsTimeseries("r", 15, 0L, 285L, series(20));
            assertThat(AiInsightsService.durationSeconds(ts)).isEqualTo(300L);
        }

        @Test
        @DisplayName("falls back to point count × bucket width when the range is unset")
        void fallsBackToPointCount() {
            MetricsTimeseries ts = new MetricsTimeseries("r", 30, null, null, series(10));
            assertThat(AiInsightsService.durationSeconds(ts)).isEqualTo(300L);
        }
    }

    @Nested
    @DisplayName("shapeBuckets — clamped, never upsampled")
    class ShapeBuckets {

        @Test
        @DisplayName("scales with duration between the floor and the cap")
        void scalesThenClamps() {
            assertThat(AiInsightsService.shapeBuckets(3_600, 240)).isEqualTo(120);    // 1 h → 30 s each
            assertThat(AiInsightsService.shapeBuckets(28_800, 960)).isEqualTo(720);   // 8 h → capped
            assertThat(AiInsightsService.shapeBuckets(300, 1_000)).isEqualTo(30);     // 5 m → floored
        }

        @Test
        @DisplayName("never asks for more buckets than there are points")
        void neverUpsamples() {
            assertThat(AiInsightsService.shapeBuckets(3_600, 12)).isEqualTo(12);
            assertThat(AiInsightsService.shapeBuckets(3_600, 0)).isZero();
        }
    }

    @Nested
    @DisplayName("downsample + peaks")
    class Downsample {

        @Test
        @DisplayName("averages each slice and rounds to the requested precision")
        void averagesSlices() {
            List<TimeseriesPoint> pts = List.of(
                    new TimeseriesPoint(0, 10), new TimeseriesPoint(1, 20),
                    new TimeseriesPoint(2, 30), new TimeseriesPoint(3, 40));
            assertThat(AiInsightsService.downsample(pts, 2, 0))
                    .containsExactly(15L, 35L);
        }

        @Test
        @DisplayName("empty input yields an empty array, not a row of zeros")
        void emptyStaysEmpty() {
            assertThat(AiInsightsService.downsample(List.of(), 10, 0)).isEmpty();
            assertThat(AiInsightsService.downsample(null, 10, 0)).isEmpty();
        }

        @Test
        @DisplayName("a peak carries its elapsed offset so the model never does index maths")
        void peakCarriesElapsedTime() {
            Map<String, Object> peaks = new LinkedHashMap<>();
            AiInsightsService.putPeak(peaks, "tps", List.of(10L, 90L, 20L), 120, 3);
            @SuppressWarnings("unchecked")
            Map<String, Object> tps = (Map<String, Object>) peaks.get("tps");
            assertThat(tps).containsEntry("v", 90L).containsEntry("atSec", 40L);
        }

        @Test
        @DisplayName("atSec divides once — a pre-rounded bucket width drifts minutes late in a run")
        void atSecDoesNotCompoundRounding() {
            // 28,799 s over 720 slices is 39.998 s each. Scaling index 719 by a
            // truncated 39 puts the peak at 28,041 s — ~12 minutes early, and the
            // prompt reads atSec out to the operator verbatim.
            List<Number> series = new ArrayList<>();
            for (int i = 0; i < 720; i++) series.add(i == 719 ? 99L : 1L);
            Map<String, Object> peaks = new LinkedHashMap<>();
            AiInsightsService.putPeak(peaks, "tps", series, 28_799, 720);
            @SuppressWarnings("unchecked")
            Map<String, Object> tps = (Map<String, Object>) peaks.get("tps");
            assertThat((Long) tps.get("atSec")).isEqualTo(28_759L);
        }

        @Test
        @DisplayName("an all-zero series still reports a peak of zero, not nothing")
        void allZeroSeries() {
            Map<String, Object> peaks = new LinkedHashMap<>();
            AiInsightsService.putPeak(peaks, "errorPct", List.of(0L, 0L), 30, 2);
            assertThat(peaks).containsKey("errorPct");
            AiInsightsService.putPeak(peaks, "absent", List.of(), 30, 2);
            assertThat(peaks).doesNotContainKey("absent");
        }
    }

    // ── comparison label join ─────────────────────────────────────────────

    @Nested
    @DisplayName("labelDeltas — the A/B join done here, not by the model")
    class LabelDeltas {

        @Test
        @DisplayName("pairs shared labels busiest-first and names the one-sided ones")
        void pairsAndPartitions() {
            List<Map<String, Object>> a = List.of(
                    label("checkout", 100, 0.02, 500), label("browse", 9_000, 0.0, 120));
            List<Map<String, Object>> b = List.of(
                    label("checkout", 120, 0.05, 900), label("newSearch", 50, 0.0, 80));

            Map<String, Object> out = AiInsightsService.labelDeltas(a, b);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("labelDeltas");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("label", "checkout");
            // Written application/label — the grain the rollup actually groups by.
            assertThat(out).containsEntry("labelsOnlyInA", List.of("payments/browse"));
            assertThat(out).containsEntry("labelsOnlyInB", List.of("payments/newSearch"));
        }

        @Test
        @DisplayName("each side carries the HTTP error meter as a percentage")
        void sideCarriesHttpErrorPct() {
            Map<String, Object> out = AiInsightsService.labelDeltas(
                    List.of(label("checkout", 100, 0.02, 500)),
                    List.of(label("checkout", 100, 0.05, 900)));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("labelDeltas");
            @SuppressWarnings("unchecked")
            Map<String, Object> sideB = (Map<String, Object>) rows.get(0).get("b");
            assertThat(sideB).containsEntry("httpErrorPct", 5.0).containsEntry("p95", 900L);
        }

        @Test
        @DisplayName("two applications sharing a label stay separate rows, and never cross-pair")
        void labelIsScopedToItsApplication() {
            // rollupByLabel groups by (LABEL_KEY, APPLICATION), so one group can
            // return `checkout` twice. Keying on the label alone dropped one and
            // could compare payments' p95 against search's.
            Map<String, Object> aPayments = label("checkout", 9000, 0.01, 200);
            Map<String, Object> aSearch = label("checkout", 100, 0.02, 800);
            aSearch.put("application", "search");
            Map<String, Object> bSearch = label("checkout", 120, 0.02, 810);
            bSearch.put("application", "search");

            Map<String, Object> out = AiInsightsService.labelDeltas(
                    List.of(aPayments, aSearch), List.of(bSearch));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("labelDeltas");
            assertThat(rows).singleElement()
                    .satisfies(r -> {
                        assertThat(r).containsEntry("label", "checkout");
                        assertThat(r).containsEntry("application", "search");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> a = (Map<String, Object>) r.get("a");
                        // search's 800 ms row, never payments' 200 ms one.
                        assertThat(a).containsEntry("p95", 800L);
                    });
            // payments/checkout exists only in A and must be reported as such.
            assertThat(out).containsEntry("labelsOnlyInA", List.of("payments/checkout"));
        }

        @Test
        @DisplayName("no shared labels is an empty table, never an exception")
        void disjointPlans() {
            Map<String, Object> out = AiInsightsService.labelDeltas(
                    List.of(label("a", 1, 0, 1)), List.of(label("b", 1, 0, 1)));
            assertThat((List<?>) out.get("labelDeltas")).isEmpty();
        }
    }

    // ── the assembled prompt ──────────────────────────────────────────────

    @Nested
    @DisplayName("buildRunUserPrompt — what actually reaches Claude")
    class UserPrompt {

        @Test
        @DisplayName("totals come from the weighted summary, not from averaging the buckets")
        void totalsAreWeightedNotBucketMeans() throws Exception {
            // A ramp: two thin 10 ms buckets, then one 1,000-sample 500 ms bucket.
            // The mean of the bucket means is 173 ms; the run's real mean is 499 ms.
            Series s = new Series(
                    List.of(new TimeseriesPoint(0, 1), new TimeseriesPoint(15, 1), new TimeseriesPoint(30, 66)),
                    List.of(new TimeseriesPoint(0, 10), new TimeseriesPoint(15, 10), new TimeseriesPoint(30, 500)),
                    List.of(new TimeseriesPoint(0, 0), new TimeseriesPoint(15, 0), new TimeseriesPoint(30, 0)),
                    Map.of("2xx", flat(3, 10.0)),
                    List.of(new TimeseriesPoint(0, 12), new TimeseriesPoint(15, 12), new TimeseriesPoint(30, 900)),
                    List.of(new TimeseriesPoint(0, 14), new TimeseriesPoint(15, 14), new TimeseriesPoint(30, 1500)));
            MetricsTimeseries ts = new MetricsTimeseries("r", 15, 0L, 30L, s);
            RunSummary summary = new RunSummary("r", 0L, 30L,
                    new RunSummary.Stats(null, 1_002, 3, 22.3, 0.3, 499, 780, 900, 1_500, 2_100, 64),
                    List.of());

            JsonNode digest = digestOf(service().buildRunUserPrompt(run(), ts, summary, List.of()));

            assertThat(digest.path("totals").path("avgMs").asLong()).isEqualTo(499);
            assertThat(digest.path("totals").path("p95Ms").asLong()).isEqualTo(900);
            assertThat(digest.path("totals").path("samples").asLong()).isEqualTo(1_002);
            assertThat(digest.path("totals").path("httpErrorPct").asDouble()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("carries both error meters per label, and how many labels were withheld")
        void bothErrorMetersAndLabelCoverage() throws Exception {
            List<Map<String, Object>> rollup = new ArrayList<>();
            // 2% HTTP errors but 9% JMeter errors: assertions failing on 2xx.
            rollup.add(label("checkout", 500, 0.02, 400));
            rollup.get(0).put("errorRate", 0.09);
            for (int i = 0; i < 60; i++) rollup.add(label("filler" + i, 10, 0, 10));

            JsonNode digest = digestOf(service().buildRunUserPrompt(
                    run(), new MetricsTimeseries("r", 15, 0L, 15L, series(2)), null, rollup));

            JsonNode first = digest.path("perLabel").get(0);
            assertThat(first.path("httpErrorPct").asDouble()).isEqualTo(2.0);
            assertThat(first.path("jmeterErrorPct").asDouble()).isEqualTo(9.0);
            assertThat(digest.path("perLabelTotal").asInt()).isEqualTo(61);
            assertThat(digest.path("perLabelShown").asInt()).isEqualTo(40);
            assertThat(digest.path("perLabel")).hasSize(40);
        }

        @Test
        @DisplayName("the comparison digest carries both runs, corrected status counts and the label join")
        void compareDigestCarriesBothSides() throws Exception {
            MetricsTimeseries ts = new MetricsTimeseries("r", 15, 0L, 285L,
                    new Series(flat(20, 10), flat(20, 100), flat(20, 1),
                            Map.of("5xx", flat(20, 0.3)), flat(20, 150), flat(20, 200)));
            RunSummary sum = new RunSummary("r", 0L, 285L,
                    new RunSummary.Stats(null, 3000, 90, 10.0, 3.0, 100, 140, 150, 200, 900, 8),
                    List.of());
            var a = new AiInsightsService.DigestInputs(run(), ts, sum, List.of(label("checkout", 500, 0.02, 400)));
            var b = new AiInsightsService.DigestInputs(run(), ts, sum, List.of(label("checkout", 500, 0.09, 900)));

            JsonNode d = digestOf(service().buildCompareUserPrompt(a, b));

            assertThat(d.path("runA").path("totals").path("samples").asLong()).isEqualTo(3000);
            assertThat(d.path("runB").path("totals").path("p95Ms").asLong()).isEqualTo(150);
            // The rate-to-count fix must hold on the compare path too: 0.3/s over
            // 20 buckets of 15 s is 90 server errors, not 0 and not 6.
            assertThat(d.path("runA").path("statusTotals").path("5xx").asLong()).isEqualTo(90);
            assertThat(d.path("labelDeltas").get(0).path("b").path("httpErrorPct").asDouble()).isEqualTo(9.0);
        }

        @Test
        @DisplayName("omits totals entirely when no rows landed, rather than emitting zeros")
        void noDataOmitsTotals() throws Exception {
            JsonNode digest = digestOf(service().buildRunUserPrompt(
                    run(), new MetricsTimeseries("r", 15, null, null, Series.empty()), null, List.of()));
            assertThat(digest.has("totals")).isFalse();
            assertThat(digest.path("perLabelTotal").asInt()).isZero();
        }
    }

    // ── response parsing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("response parsing — a fallback must never reach the cache")
    class Parsing {

        @Test
        @DisplayName("reads evidence off each finding and reports the reply as structured")
        void readsEvidence() {
            var parsed = service().parseRunResponse("""
                    {"summary":"Held flat.","findings":[
                      {"severity":"WARNING","title":"Tail grew","detail":"p95 doubled.",
                       "evidence":"p95 604 → 1204 ms"}]}""");
            assertThat(parsed.structured()).isTrue();
            assertThat(parsed.summary()).isEqualTo("Held flat.");
            assertThat(parsed.findings()).singleElement()
                    .satisfies(f -> {
                        assertThat(f.severity()).isEqualTo("warn");
                        assertThat(f.evidence()).isEqualTo("p95 604 → 1204 ms");
                    });
        }

        @Test
        @DisplayName("recovers JSON from a fenced reply")
        void stripsFences() {
            var parsed = service().parseRunResponse("```json\n{\"summary\":\"ok\",\"findings\":[]}\n```");
            assertThat(parsed.structured()).isTrue();
            assertThat(parsed.summary()).isEqualTo("ok");
        }

        @Test
        @DisplayName("unparseable text is surfaced but flagged unstructured, so it is not cached")
        void garbageIsNotStructured() {
            var parsed = service().parseRunResponse("The run looked fine to me.");
            assertThat(parsed.structured()).isFalse();
            assertThat(parsed.summary()).isEqualTo("The run looked fine to me.");
            assertThat(parsed.findings()).isEmpty();
        }

        @Test
        @DisplayName("comparison findings carry verdict, delta and evidence")
        void compareFindings() {
            var parsed = service().parseCompareResponse("""
                    {"summary":"B is slower.","findings":[
                      {"metric":"p95Ms","verdict":"regression","delta":"+47%",
                       "detail":"Tail widened under the same load.","evidence":"604 → 891 ms"}]}""");
            assertThat(parsed.structured()).isTrue();
            assertThat(parsed.findings()).singleElement().satisfies(f -> {
                assertThat(f.metric()).isEqualTo("p95Ms");
                assertThat(f.verdict()).isEqualTo("regression");
                assertThat(f.evidence()).isEqualTo("604 → 891 ms");
            });
        }
    }

    @Nested
    @DisplayName("response schema — what the provider is asked to enforce")
    class Schema {

        @Test
        @DisplayName("run schema pins the severity enum and requires evidence")
        void runSchema() {
            JsonNode schema = MAPPER.valueToTree(AiInsightsService.runResponseSchema());
            JsonNode finding = schema.path("properties").path("findings").path("items");
            assertThat(finding.path("additionalProperties").asBoolean()).isFalse();
            assertThat(finding.path("required").toString()).contains("evidence");
            assertThat(finding.path("properties").path("severity").path("enum").toString())
                    .isEqualTo("[\"info\",\"warn\",\"crit\"]");
        }

        @Test
        @DisplayName("compare schema pins the verdict enum")
        void compareSchema() {
            JsonNode schema = MAPPER.valueToTree(AiInsightsService.compareResponseSchema());
            assertThat(schema.path("properties").path("findings").path("items")
                    .path("properties").path("verdict").path("enum").toString())
                    .contains("no significant change");
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static JsonNode digestOf(String userPrompt) throws Exception {
        int open = userPrompt.indexOf('{');
        int close = userPrompt.lastIndexOf('}');
        return MAPPER.readTree(userPrompt.substring(open, close + 1));
    }

    private static Run run() {
        return new Run("01ARZ3NDEKTSV4RRFFQ69G5FAV", "na-east", "plan", null, "payments", "tester",
                RunState.COMPLETED, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, false, List.of());
    }

    private static List<TimeseriesPoint> flat(int n, double v) {
        List<TimeseriesPoint> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pts.add(new TimeseriesPoint(i * 15L, v));
        return pts;
    }

    private static Series series(int n) {
        return new Series(flat(n, 1), flat(n, 100), flat(n, 0), Map.of(), flat(n, 150), flat(n, 200));
    }

    /** One {@code rollupByLabel} row, in the camelCase keys the repository returns. */
    private static Map<String, Object> label(String name, long samples, double httpErrorRate, long p95) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", name);
        m.put("application", "payments");
        m.put("totalThroughput", samples);
        m.put("throughputRps", samples / 60.0);
        m.put("httpErrorRate", httpErrorRate);
        m.put("errorRate", httpErrorRate);
        m.put("avgP50Ms", p95 / 4.0);
        m.put("avgP90Ms", p95 * 0.9);
        m.put("avgP95Ms", (double) p95);
        m.put("avgP99Ms", p95 * 1.5);
        m.put("maxMs", p95 * 3);
        m.put("maxActiveThreads", 64L);
        return m;
    }
}
