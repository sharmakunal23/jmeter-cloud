package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.CompareInsights;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunInsights;
import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import com.perf.globalorchestrator.repo.AiResponseRepository;
import com.perf.globalorchestrator.repo.AiResponseRepository.CachedAiResponse;
import com.perf.globalorchestrator.service.AiClient.AiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Composes the run digest Claude reads, calls {@link AiClient}, and serves the
 * durable {@code aiResponse} cache for single-run insights and two-run
 * comparison narratives.
 *
 * <p>Two rules the digest exists to enforce. <b>Every aggregate comes from
 * {@link CachingMetricsService#summary}</b>, which is throughput-weighted in
 * SQL — averaging the bucket arrays here instead would weight a 5-sample bucket
 * like a 5,000-sample one and quietly misreport every ramping run. And
 * <b>{@code statusCodes} are per-second rates, not counts</b>
 * ({@code MetricsTimeseriesRepository.series}), so a total is
 * {@code Σ rate × bucketSize} — summing them raw under-reports by 15-60× and
 * rounding each point first drops any code below 0.5/s to zero.
 *
 * <p>Cache discipline matches the rest of the platform: only TERMINAL runs are
 * persisted (an active run's inputs are still moving, so its summary would go
 * stale), and only a response that actually parsed. A cache hit costs no quota
 * and no Claude bill; {@code fresh=true} bypasses the cache to re-bill.
 */
@Service
public class AiInsightsService {

    private static final Logger LOG = LoggerFactory.getLogger(AiInsightsService.class);

    /**
     * Bump on ANY change to the digest or the prompts — the cache key carries
     * this and nothing else about the inputs, so a digest fix that forgets it
     * ships to nobody: every terminal run keeps serving the summary generated
     * from the old, wrong numbers for the rest of the 30-day TTL.
     *
     * <p>v4 (2026-08-31): aggregates come from the throughput-weighted
     * {@code RunSummary} instead of unweighted bucket means; {@code statusTotals}
     * corrected from rates to counts; per-label carries both error meters; p95
     * added to the shape and p90/p95/p99 to the totals; peaks carry their
     * elapsed time so the model never does index arithmetic; comparison gained a
     * per-label delta table. v5 (2026-08-31): elapsed time is phrased in
     * minutes or hours past two minutes — a verified reply said "at ~870 s".
     * v6 (2026-08-31): {@code atSec} no longer inherits the bucket width's
     * rounding (a late peak on an 8 h run read ~12 minutes early), and a
     * transaction is identified by application AND label, so two applications
     * sharing a {@code checkout} can no longer be compared against each other.
     */
    static final String PROMPT_VERSION = "v6";
    static final String KIND_RUN = "runInsights";
    static final String KIND_COMPARE = "compareInsights";

    // Shape resolution scales with run duration (then clamps), so a short run
    // stays cheap and a long run keeps fine-grained buckets. One bucket per
    // ~30 s, floored at 30 and capped at 720:
    //   8 h  → 28800/30 = 960 → capped 720 (~40 s resolution).
    //   1 h  → 120 buckets (~30 s resolution).
    //   5 m  → floored to 30 buckets, then bounded by the points that exist.
    private static final int MIN_SHAPE_BUCKETS = 30;
    private static final int MAX_SHAPE_BUCKETS = 720;
    private static final int TARGET_BUCKET_SEC = 30;
    /** Labels included in the digest, busiest first; {@code perLabelTotal} tells the model what it did not see. */
    private static final int TOP_LABELS = 40;
    /** Labels in the comparison's per-label delta table — the join the model would otherwise do by hand. */
    private static final int TOP_LABEL_DELTAS = 20;
    /** Cap on the "only in one run" name lists, so a renamed plan cannot flood the prompt. */
    private static final int MAX_ONLY_IN = 20;
    /**
     * Label rows a comparison reads per run. Bounded — unlike the single-run
     * digest, which needs every row to report {@code perLabelTotal} honestly —
     * because the delta table keeps {@value #TOP_LABEL_DELTAS} and each
     * one-sided list {@value #MAX_ONLY_IN}: reading a 2,000-label plan twice to
     * discard all but 20 is the unbounded query the platform forbids.
     */
    private static final int COMPARE_LABELS = MetricsTimeseriesRepository.LABELS_MAX;

    /** A cache miss costs a Claude bill (not just a query), so the TTL is long. */
    static final Duration TTL = Duration.ofDays(30);

    private final RunService runs;
    private final CachingMetricsService metrics;
    private final AiClient ai;
    private final AiQuotaGuard quota;
    private final AiResponseRepository cache;
    private final ObjectMapper mapper;

    private final String runSystemPrompt;
    private final String compareSystemPrompt;

    public AiInsightsService(RunService runs,
                             CachingMetricsService metrics,
                             AiClient ai,
                             AiQuotaGuard quota,
                             AiResponseRepository cache,
                             ObjectMapper mapper) {
        this.runs = runs;
        this.metrics = metrics;
        this.ai = ai;
        this.quota = quota;
        this.cache = cache;
        this.mapper = mapper;
        this.runSystemPrompt = loadPrompt("prompts/runInsights." + PROMPT_VERSION + ".txt");
        this.compareSystemPrompt = loadPrompt("prompts/compareInsights." + PROMPT_VERSION + ".txt");
    }

    // ── AI-1: single-run insights ────────────────────────────────────────

    /**
     * Generate (or serve from cache) the insight for one run.
     *
     * @throws RunService.RunNotFoundException if the run is unknown (→ 404).
     * @throws AiClient.AiDisabledException     if no API key is configured (→ 503).
     */
    public RunInsights runInsights(String runId, boolean fresh) {
        Run run = runs.getRun(runId);   // 404s on unknown
        boolean terminal = isTerminal(run);

        if (terminal && !fresh) {
            Optional<CachedAiResponse> hit = cache.find(KIND_RUN, runId, PROMPT_VERSION, TTL);
            if (hit.isPresent()) {
                return toRunInsights(runId, parseStored(hit.get().responseJson()), hit.get(), true);
            }
        }

        // Cache miss / active run / forced refresh → call Claude.
        requireEnabled();
        String userPrompt = buildRunUserPrompt(run,
                metrics.timeseries(runId, run.state(), false, null),
                metrics.summary(runId, run.state(), null),
                metrics.rollupByLabel(runId, run.state(), null, null, MetricsTimeseriesRepository.LABELS_ALL));
        quota.acquire();
        AiResult result = ai.complete(runSystemPrompt, userPrompt, runResponseSchema());

        ParsedRun parsed = parseRunResponse(result.text());
        if (terminal && parsed.structured()) {
            cache.upsert(KIND_RUN, runId, PROMPT_VERSION, writeStored(parsed.summary(), parsed.findings()),
                    ai.model(), result.tokensIn(), result.tokensOut());
        }
        return new RunInsights(runId, ai.model(), PROMPT_VERSION,
                parsed.summary(), parsed.findings(),
                result.tokensIn(), result.tokensOut(), Instant.now(), false);
    }

    // ── AI-2: two-run comparison delta ───────────────────────────────────

    /**
     * Generate (or serve from cache) the comparison narrative for a run pair.
     * {@code idA} / {@code idB} preserve the operator's submitted order; the
     * cache key is the sorted pair so order does not split entries.
     *
     * @throws RunService.RunNotFoundException if either run is unknown (→ 404):
     *         we refuse to "compare" against a missing run.
     */
    public CompareInsights compareInsights(String idA, String idB, boolean fresh) {
        Run runA = runs.getRun(idA);   // 404s on unknown
        Run runB = runs.getRun(idB);
        boolean bothTerminal = isTerminal(runA) && isTerminal(runB);
        String cacheKey = sortedKey(idA, idB);

        if (bothTerminal && !fresh) {
            Optional<CachedAiResponse> hit = cache.find(KIND_COMPARE, cacheKey, PROMPT_VERSION, TTL);
            if (hit.isPresent()) {
                return toCompareInsights(idA, idB, parseStored(hit.get().responseJson()), hit.get(), true);
            }
        }

        requireEnabled();
        String userPrompt = buildCompareUserPrompt(loadInputs(runA), loadInputs(runB));
        quota.acquire();
        AiResult result = ai.complete(compareSystemPrompt, userPrompt, compareResponseSchema());

        ParsedCompare parsed = parseCompareResponse(result.text());
        if (bothTerminal && parsed.structured()) {
            cache.upsert(KIND_COMPARE, cacheKey, PROMPT_VERSION, writeStored(parsed.summary(), parsed.findings()),
                    ai.model(), result.tokensIn(), result.tokensOut());
        }
        return new CompareInsights(List.of(idA, idB), ai.model(), PROMPT_VERSION,
                parsed.summary(), parsed.findings(),
                result.tokensIn(), result.tokensOut(), Instant.now(), false);
    }

    // ── Housekeeping ──────────────────────────────────────────────────────

    /**
     * Daily prune of TTL-expired rows (read already ignores them; this reclaims
     * space). Runs 1 h after boot, then every 24 h. Tests never run long enough
     * to fire it.
     */
    @Scheduled(initialDelay = 3_600_000L, fixedDelay = 86_400_000L)
    public void pruneExpired() {
        try {
            int removed = cache.pruneOlderThan(TTL);
            if (removed > 0) {
                LOG.info("Pruned {} expired aiResponse row(s)", removed);
            }
        } catch (Exception e) {
            LOG.warn("aiResponse prune failed (non-fatal)", e);
        }
    }

    // ── prompt assembly ────────────────────────────────────────────────────

    String buildRunUserPrompt(Run run, MetricsTimeseries ts, RunSummary summary,
                              List<Map<String, Object>> rollup) {
        Map<String, Object> digest = runDigest(run, ts, summary);
        digest.put("statusTotals", statusTotals(ts.series().statusCodes(), ts.bucketSize()));
        digest.put("perLabelTotal", rollup == null ? 0 : rollup.size());
        digest.put("perLabelShown", Math.min(TOP_LABELS, rollup == null ? 0 : rollup.size()));
        digest.put("perLabel", topLabels(rollup));
        return "Run digest (JSON):\n\n" + toJson(digest)
                + "\n\nRespond with the JSON object as instructed.";
    }

    /** Everything one run contributes to a comparison, read once so the assembly below stays pure. */
    record DigestInputs(Run run, MetricsTimeseries ts, RunSummary summary, List<Map<String, Object>> rollup) { }

    private DigestInputs loadInputs(Run run) {
        return new DigestInputs(run,
                metrics.timeseries(run.runId(), run.state(), false, null),
                metrics.summary(run.runId(), run.state(), null),
                metrics.rollupByLabel(run.runId(), run.state(), null, null, COMPARE_LABELS));
    }

    String buildCompareUserPrompt(DigestInputs a, DigestInputs b) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runA", compareSide(a));
        payload.put("runB", compareSide(b));
        payload.putAll(labelDeltas(a.rollup(), b.rollup()));
        return "Digests for the two runs (JSON):\n\n" + toJson(payload)
                + "\n\nRespond with the JSON object as instructed.";
    }

    /** One run's half of a comparison: the same digest, plus its status totals. */
    private Map<String, Object> compareSide(DigestInputs in) {
        Map<String, Object> d = runDigest(in.run(), in.ts(), in.summary());
        d.put("statusTotals", statusTotals(in.ts().series().statusCodes(), in.ts().bucketSize()));
        return d;
    }

    /**
     * The run's headline numbers plus a duration-scaled downsample of each
     * series, so Claude can read the shape without every 15-second point.
     *
     * <p>{@code totals} is the throughput-weighted SQL aggregate — never a mean
     * of the arrays below it. {@code peaks} carry their elapsed offset
     * ({@code atSec}) because asking the model to multiply an array index by
     * {@code bucketSec} is the step it gets wrong.
     */
    private Map<String, Object> runDigest(Run run, MetricsTimeseries ts, RunSummary summary) {
        MetricsTimeseries.Series s = ts.series();
        long durationSec = durationSeconds(ts);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", run.runId());
        meta.put("application", run.application());
        meta.put("state", run.state() == null ? null : run.state().name());
        meta.put("durationSec", durationSec);
        meta.put("fleetSize", run.fleetMembers() == null ? 0 : run.fleetMembers().size());

        int buckets = shapeBuckets(durationSec, s.tps().size());
        // Rounded, not truncated: 28,799 s over 720 slices is 40 s each, not 39.
        long bucketSec = buckets > 0 ? Math.max(1, Math.round((double) durationSec / buckets)) : durationSec;
        List<Number> tps = downsample(s.tps(), buckets, 1);
        List<Number> rt = downsample(s.avgRtMs(), buckets, 0);
        List<Number> p95 = downsample(s.p95Ms(), buckets, 0);
        List<Number> err = downsample(s.errorPct(), buckets, 2);

        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("buckets", buckets);
        shape.put("bucketSec", bucketSec);
        shape.put("tps", tps);
        shape.put("avgRtMs", rt);
        shape.put("p95Ms", p95);
        shape.put("errorPct", err);

        Map<String, Object> peaks = new LinkedHashMap<>();
        putPeak(peaks, "tps", tps, durationSec, buckets);
        putPeak(peaks, "avgRtMs", rt, durationSec, buckets);
        putPeak(peaks, "p95Ms", p95, durationSec, buckets);
        putPeak(peaks, "errorPct", err, durationSec, buckets);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("run", meta);
        Map<String, Object> totals = totals(summary);
        if (!totals.isEmpty()) {
            d.put("totals", totals);
        }
        List<Map<String, Object>> byApp = byApplication(summary);
        if (!byApp.isEmpty()) {
            d.put("byApplication", byApp);
        }
        d.put("peaks", peaks);
        d.put("shape", shape);
        return d;
    }

    /**
     * The run's aggregate, straight from the {@code GROUP BY ROLLUP} statement
     * behind {@code GET /runs/{id}/summary}: throughput-weighted, so it is the
     * run's real mean rather than a mean of bucket means. Empty when no rows
     * landed — the prompt treats a missing {@code totals} as "no data".
     */
    private static Map<String, Object> totals(RunSummary summary) {
        if (summary == null || summary.total() == null || summary.total().samples() == 0) {
            return Map.of();
        }
        RunSummary.Stats t = summary.total();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", t.samples());
        m.put("httpErrors", t.errors());
        m.put("httpErrorPct", round(t.errorPct(), 2));
        m.put("tps", round(t.tps(), 1));
        m.put("avgMs", round(t.avgMs(), 0));
        m.put("p90Ms", round(t.p90Ms(), 0));
        m.put("p95Ms", round(t.p95Ms(), 0));
        m.put("p99Ms", round(t.p99Ms(), 0));
        m.put("maxMs", round(t.maxMs(), 0));
        m.put("maxThreads", t.maxActiveThreads());
        return m;
    }

    /** Per-application split, only when the run actually spans more than one. */
    private static List<Map<String, Object>> byApplication(RunSummary summary) {
        if (summary == null || summary.byApplication() == null || summary.byApplication().size() < 2) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunSummary.Stats a : summary.byApplication()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("application", a.application());
            m.put("samples", a.samples());
            m.put("httpErrorPct", round(a.errorPct(), 2));
            m.put("avgMs", round(a.avgMs(), 0));
            m.put("p95Ms", round(a.p95Ms(), 0));
            out.add(m);
        }
        return out;
    }

    /**
     * Total requests per HTTP class. The series carries per-second <b>rates</b>
     * ({@code count / bucketSize}), so the count is {@code Σ rate × bucketSize}
     * and the rounding happens once, at the end — rounding each point first
     * floors any class below 0.5/s to zero, which reads as "no 5xx" on a run
     * that had thousands.
     */
    static Map<String, Long> statusTotals(Map<String, List<MetricsTimeseries.TimeseriesPoint>> codes,
                                          int bucketSeconds) {
        Map<String, Long> totals = new LinkedHashMap<>();
        if (codes == null) return totals;
        int g = Math.max(1, bucketSeconds);
        for (var e : codes.entrySet()) {
            double sum = 0;
            for (var p : e.getValue()) sum += p.v();
            totals.put(e.getKey(), Math.round(sum * g));
        }
        return totals;
    }

    /**
     * The {@value #TOP_LABELS} busiest labels. Both error meters travel together
     * on purpose: {@code httpErrorPct} is 4xx+5xx (what every run-level figure
     * means) and {@code jmeterErrorPct} is JMeter's success flag, so a gap
     * between them is assertions failing on 2xx responses — invisible if only
     * one is sent, and actively misleading if the two are mixed under one name.
     */
    private static List<Map<String, Object>> topLabels(List<Map<String, Object>> rollup) {
        if (rollup == null) return List.of();
        return rollup.stream()
                .sorted((a, b) -> Long.compare(asLong(b.get("totalThroughput")), asLong(a.get("totalThroughput"))))
                .limit(TOP_LABELS)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", r.get("label"));
                    m.put("application", r.get("application"));
                    m.put("samples", asLong(r.get("totalThroughput")));
                    m.put("tps", round(asDouble(r.get("throughputRps")), 2));
                    m.put("httpErrorPct", round(asDouble(r.get("httpErrorRate")) * 100.0, 2));
                    m.put("jmeterErrorPct", round(asDouble(r.get("errorRate")) * 100.0, 2));
                    m.put("p50", round(asDouble(r.get("avgP50Ms")), 0));
                    m.put("p90", round(asDouble(r.get("avgP90Ms")), 0));
                    m.put("p95", round(asDouble(r.get("avgP95Ms")), 0));
                    m.put("p99", round(asDouble(r.get("avgP99Ms")), 0));
                    m.put("maxMs", asLong(r.get("maxMs")));
                    m.put("maxThreads", asLong(r.get("maxActiveThreads")));
                    return m;
                })
                .toList();
    }

    /**
     * Per-label A-vs-B rows for the comparison, plus the labels that exist on
     * only one side. Joining two 40-row label lists by name is mechanical work
     * the model gets wrong, so it is done here.
     */
    static Map<String, Object> labelDeltas(List<Map<String, Object>> rollupA, List<Map<String, Object>> rollupB) {
        Map<String, Map<String, Object>> byLabelA = byLabel(rollupA);
        Map<String, Map<String, Object>> byLabelB = byLabel(rollupB);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> onlyInA = new ArrayList<>();
        for (var e : byLabelA.entrySet()) {
            Map<String, Object> b = byLabelB.get(e.getKey());
            if (b == null) {
                if (onlyInA.size() < MAX_ONLY_IN) onlyInA.add(e.getKey());
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", e.getValue().get("label"));
            row.put("application", e.getValue().get("application"));
            row.put("a", side(e.getValue()));
            row.put("b", side(b));
            rows.add(row);
        }
        rows.sort((x, y) -> Long.compare(sideSamples(y), sideSamples(x)));
        List<String> onlyInB = byLabelB.keySet().stream()
                .filter(k -> !byLabelA.containsKey(k)).limit(MAX_ONLY_IN).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labelDeltas", rows.size() > TOP_LABEL_DELTAS ? rows.subList(0, TOP_LABEL_DELTAS) : rows);
        if (!onlyInA.isEmpty()) out.put("labelsOnlyInA", onlyInA);
        if (!onlyInB.isEmpty()) out.put("labelsOnlyInB", onlyInB);
        return out;
    }

    /**
     * Index by the rollup's real grain — it groups by {@code (LABEL_KEY,
     * APPLICATION)}, so two applications in one group may both expose
     * {@code checkout}. Keying on the label alone dropped one of them and could
     * pair run A's payments row against run B's search row and call the
     * difference a regression.
     */
    private static Map<String, Map<String, Object>> byLabel(List<Map<String, Object>> rollup) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        if (rollup == null) return m;
        for (Map<String, Object> r : rollup) {
            Object label = r.get("label");
            if (label != null) m.putIfAbsent(labelKey(r), r);
        }
        return m;
    }

    /** {@code label} when the run has one application, {@code application/label} otherwise. */
    private static String labelKey(Map<String, Object> r) {
        Object app = r.get("application");
        return app == null || String.valueOf(app).isBlank()
                ? String.valueOf(r.get("label"))
                : app + "/" + r.get("label");
    }

    private static Map<String, Object> side(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", asLong(r.get("totalThroughput")));
        m.put("tps", round(asDouble(r.get("throughputRps")), 2));
        m.put("httpErrorPct", round(asDouble(r.get("httpErrorRate")) * 100.0, 2));
        m.put("p95", round(asDouble(r.get("avgP95Ms")), 0));
        m.put("p99", round(asDouble(r.get("avgP99Ms")), 0));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static long sideSamples(Map<String, Object> row) {
        Object a = row.get("a");
        return a instanceof Map<?, ?> m ? asLong(((Map<String, Object>) m).get("samples")) : 0L;
    }

    // ── digest math ────────────────────────────────────────────────────────

    /**
     * Bucket count for the shape arrays: ~1 per {@value #TARGET_BUCKET_SEC}s of
     * run, clamped to [{@value #MIN_SHAPE_BUCKETS}, {@value #MAX_SHAPE_BUCKETS}]
     * and never more than the points we actually have (no upsampling).
     */
    static int shapeBuckets(long durationSec, int availablePoints) {
        if (availablePoints <= 0) return 0;
        long target = Math.round(durationSec / (double) TARGET_BUCKET_SEC);
        int clamped = (int) Math.max(MIN_SHAPE_BUCKETS, Math.min(MAX_SHAPE_BUCKETS, target));
        return Math.min(clamped, availablePoints);
    }

    /**
     * Wall-clock length of the range the series covers. {@code fromSecond} and
     * {@code toSecond} are bucket <b>starts</b>, so the last bucket contributes
     * its own width — {@code to - from} alone is short by one bucket, and that
     * error lands straight in {@code bucketSec}, which the prompt uses for every
     * elapsed-time statement.
     */
    static long durationSeconds(MetricsTimeseries ts) {
        int g = Math.max(1, ts.bucketSize());
        Long from = ts.fromSecond(), to = ts.toSecond();
        if (from != null && to != null && to >= from) return to - from + g;
        return (long) ts.series().tps().size() * g;
    }

    /** Even-width downsample to {@code buckets} points, averaging each slice, rounded. */
    static List<Number> downsample(List<MetricsTimeseries.TimeseriesPoint> pts, int buckets, int decimals) {
        List<Number> out = new ArrayList<>(Math.max(0, buckets));
        int n = pts == null ? 0 : pts.size();
        if (n == 0 || buckets <= 0) return out;
        for (int b = 0; b < buckets; b++) {
            int start = (int) ((long) b * n / buckets);
            int end = (int) ((long) (b + 1) * n / buckets);
            if (end <= start) end = Math.min(start + 1, n);
            double sum = 0;
            int c = 0;
            for (int i = start; i < end && i < n; i++) {
                sum += pts.get(i).v();
                c++;
            }
            out.add(round(c > 0 ? sum / c : 0, decimals));
        }
        return out;
    }

    /**
     * The series' maximum and the elapsed second it happened at. Read off the
     * <b>downsampled</b> arrays, not the raw 15-second windows: one thin window
     * with 2 samples and 1 error is a 50% spike that no chart shows and no
     * operator can act on.
     *
     * <p>{@code atSec} divides once, at the end. Scaling an index by an
     * already-rounded bucket width compounds that rounding across every slice —
     * on an 8 h run it puts a late peak ~12 minutes early — and the prompt reads
     * {@code atSec} out to the operator verbatim.
     */
    static void putPeak(Map<String, Object> into, String key, List<Number> series,
                        long durationSec, int buckets) {
        int idx = -1;
        double best = 0;
        for (int i = 0; i < series.size(); i++) {
            double v = series.get(i).doubleValue();
            if (idx < 0 || v > best) {
                best = v;
                idx = i;
            }
        }
        if (idx < 0) return;
        Map<String, Object> peak = new LinkedHashMap<>();
        peak.put("v", series.get(idx));
        peak.put("atSec", buckets > 0 ? (long) idx * durationSec / buckets : 0L);
        into.put(key, peak);
    }

    private static Number round(double v, int decimals) {
        if (!Double.isFinite(v)) return 0L;
        if (decimals <= 0) return Math.round(v);
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ── response schema (structured outputs) ────────────────────────────────

    // Every map here is insertion-ordered on purpose. `Map.of` salts its
    // iteration order once per JVM start, so the serialized schema — and with it
    // the whole tools/system prefix — would come out in a different byte order
    // after every restart, forfeiting the one prefix stable enough to cache.

    /** The reply shape for AI-1, sent as {@code output_config.format}. */
    static Map<String, Object> runResponseSchema() {
        return object(
                List.of("summary", "findings"),
                ordered("summary", type("string"),
                        "findings", ordered(
                                "type", "array",
                                "items", object(
                                        List.of("severity", "title", "detail", "evidence"),
                                        ordered("severity", enumOf("info", "warn", "crit"),
                                                "title", type("string"),
                                                "detail", type("string"),
                                                "evidence", type("string"))))));
    }

    /** The reply shape for AI-2, sent as {@code output_config.format}. */
    static Map<String, Object> compareResponseSchema() {
        return object(
                List.of("summary", "findings"),
                ordered("summary", type("string"),
                        "findings", ordered(
                                "type", "array",
                                "items", object(
                                        List.of("metric", "verdict", "delta", "detail", "evidence"),
                                        ordered("metric", type("string"),
                                                "verdict", enumOf("regression", "improvement",
                                                        "no significant change"),
                                                "delta", type("string"),
                                                "detail", type("string"),
                                                "evidence", type("string"))))));
    }

    /** An insertion-ordered map from alternating key/value pairs. */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> type(String jsonType) {
        return ordered("type", jsonType);
    }

    private static Map<String, Object> enumOf(String... values) {
        return ordered("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("additionalProperties", false);
        m.put("required", required);
        m.put("properties", properties);
        return m;
    }

    // ── response parsing (tolerant) ─────────────────────────────────────────

    /**
     * {@code structured} is false when the reply was not recoverable JSON and
     * the raw text became the summary — a fallback that must never reach the
     * cache, or one malformed answer is what the operator sees for 30 days.
     */
    ParsedRun parseRunResponse(String text) {
        JsonNode root = tryParseJsonObject(text);
        if (root == null) {
            return new ParsedRun(text == null ? "" : text.strip(), List.of(), false);
        }
        String summary = root.path("summary").asText("");
        List<RunInsights.Finding> findings = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            findings.add(new RunInsights.Finding(
                    normalizeSeverity(f.path("severity").asText("info")),
                    f.path("title").asText(""),
                    f.path("detail").asText(""),
                    f.path("evidence").asText("")));
        }
        return new ParsedRun(summary.isBlank() ? text.strip() : summary, findings, true);
    }

    ParsedCompare parseCompareResponse(String text) {
        JsonNode root = tryParseJsonObject(text);
        if (root == null) {
            return new ParsedCompare(text == null ? "" : text.strip(), List.of(), false);
        }
        String summary = root.path("summary").asText("");
        List<CompareInsights.CompareFinding> findings = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            findings.add(new CompareInsights.CompareFinding(
                    f.path("metric").asText(""),
                    f.path("verdict").asText(""),
                    f.path("delta").asText(""),
                    f.path("detail").asText(""),
                    f.path("evidence").asText("")));
        }
        return new ParsedCompare(summary.isBlank() ? text.strip() : summary, findings, true);
    }

    /**
     * Tolerant extraction: strips ```json fences and trims to the outermost
     * {@code { … }} so a stray sentence around the JSON doesn't break parsing.
     * Returns null when no JSON object can be recovered.
     */
    private JsonNode tryParseJsonObject(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.strip();
        }
        int open = t.indexOf('{');
        int close = t.lastIndexOf('}');
        if (open < 0 || close <= open) return null;
        String json = t.substring(open, close + 1);
        try {
            JsonNode node = mapper.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeSeverity(String s) {
        String v = s == null ? "" : s.trim().toLowerCase();
        return switch (v) {
            case "warn", "warning" -> "warn";
            case "crit", "critical", "error" -> "crit";
            default -> "info";
        };
    }

    // ── stored-JSON (de)serialization ───────────────────────────────────────

    /** Persist only the kind-specific payload; columns carry model + tokens + createdAt. */
    private String writeStored(String summary, List<?> findings) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("summary", summary);
        stored.put("findings", findings);
        return toJson(stored);
    }

    private JsonNode parseStored(String responseJson) {
        try {
            return mapper.readTree(responseJson);
        } catch (IOException e) {
            throw new UncheckedIOException("corrupt aiResponse JSON", e);
        }
    }

    private RunInsights toRunInsights(String runId, JsonNode stored, CachedAiResponse row, boolean fromCache) {
        List<RunInsights.Finding> findings = new ArrayList<>();
        for (JsonNode f : stored.path("findings")) {
            findings.add(new RunInsights.Finding(
                    f.path("severity").asText("info"),
                    f.path("title").asText(""),
                    f.path("detail").asText(""),
                    f.path("evidence").asText("")));
        }
        return new RunInsights(runId, row.model(), PROMPT_VERSION,
                stored.path("summary").asText(""), findings,
                row.tokensIn(), row.tokensOut(), row.createdAt(), fromCache);
    }

    private CompareInsights toCompareInsights(String idA, String idB, JsonNode stored,
                                              CachedAiResponse row, boolean fromCache) {
        List<CompareInsights.CompareFinding> findings = new ArrayList<>();
        for (JsonNode f : stored.path("findings")) {
            findings.add(new CompareInsights.CompareFinding(
                    f.path("metric").asText(""),
                    f.path("verdict").asText(""),
                    f.path("delta").asText(""),
                    f.path("detail").asText(""),
                    f.path("evidence").asText("")));
        }
        return new CompareInsights(List.of(idA, idB), row.model(), PROMPT_VERSION,
                stored.path("summary").asText(""), findings,
                row.tokensIn(), row.tokensOut(), row.createdAt(), fromCache);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void requireEnabled() {
        if (!ai.isEnabled()) {
            throw new AiClient.AiDisabledException("ANTHROPIC_API_KEY is not configured");
        }
    }

    private static boolean isTerminal(Run run) {
        return run.state() != null && run.state().isTerminal();
    }

    /** Order-independent cache key for a run pair. */
    static String sortedKey(String idA, String idB) {
        return idA.compareTo(idB) <= 0 ? idA + "|" + idB : idB + "|" + idA;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to serialize AI prompt payload", e);
        }
    }

    private static String loadPrompt(String classpath) {
        try {
            return new String(new ClassPathResource(classpath).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing AI prompt template: " + classpath, e);
        }
    }

    record ParsedRun(String summary, List<RunInsights.Finding> findings, boolean structured) { }

    record ParsedCompare(String summary, List<CompareInsights.CompareFinding> findings, boolean structured) { }
}
