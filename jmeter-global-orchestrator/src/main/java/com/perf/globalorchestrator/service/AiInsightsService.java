package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.CompareInsights;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunInsights;
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
 * AI-1 / AI-2 — composes prompts, calls {@link AiClient},
 * and serves the durable {@code aiResponse} cache for single-run insights and
 * two-run comparison narratives.
 *
 * <p>Inputs reuse the existing (already terminal-cached) surfaces:
 * {@link CachingMetricsService#timeseries} and {@link CachingMetricsService#rollupByLabel}.
 *
 * <p>Cache discipline matches the rest of the platform: only TERMINAL runs are
 * persisted (an active run's inputs are still moving, so its summary would go
 * stale). A cache hit costs no quota and no Claude bill; {@code fresh=true}
 * bypasses the cache to re-bill (for a second angle, or after a prompt tweak).
 */
@Service
public class AiInsightsService {

    private static final Logger LOG = LoggerFactory.getLogger(AiInsightsService.class);

    // v2 (2026-05-31): inputs became a COMPACT DIGEST (aggregates + downsample +
    // top-N labels) instead of the full per-second timeseries — ~56k → ~1-5k
    // input tokens. v3 (2026-05-31): prompt forbids exposing "bucket" indices in
    // the output (it's just the input encoding) and tells the model to phrase
    // timing as elapsed wall time via bucketSec. Each bump invalidates the prior
    // cache so summaries regenerate with the new behaviour.
    static final String PROMPT_VERSION = "v3";
    static final String KIND_RUN = "runInsights";
    static final String KIND_COMPARE = "compareInsights";

    // Shape resolution scales with run duration (then clamps), so a short run
    // stays cheap and a long run gets fine-grained buckets — the sweet spot the
    // operator asked for (~5-8k input tokens for an 8 h test; short runs far
    // less). One bucket per ~30 s, floored at 30 and capped at 720:
    //   8 h  → 28800/30 = 960 → capped 720 (~40 s resolution) ≈ ~4-5k tokens.
    //   1 h  → 120 buckets (~30 s resolution).
    //   5 m  → floored to 30 buckets (~10 s resolution).
    private static final int MIN_SHAPE_BUCKETS = 30;
    private static final int MAX_SHAPE_BUCKETS = 720;
    private static final int TARGET_BUCKET_SEC = 30;
    /** Top labels by throughput included in the digest (most plans have fewer than this). */
    private static final int TOP_LABELS = 25;

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
        this.runSystemPrompt = loadPrompt("prompts/runInsights.v3.txt");
        this.compareSystemPrompt = loadPrompt("prompts/compareInsights.v3.txt");
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
        boolean terminal = run.state() != null && run.state().isTerminal();

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
                metrics.rollupByLabel(runId, run.state(), null, null, MetricsTimeseriesRepository.LABELS_ALL));
        quota.acquire();
        AiResult result = ai.complete(runSystemPrompt, userPrompt);

        ParsedRun parsed = parseRunResponse(result.text());
        String storedJson = writeStored(parsed.summary(), parsed.findings());
        if (terminal) {
            cache.upsert(KIND_RUN, runId, PROMPT_VERSION, storedJson,
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
        String userPrompt = buildCompareUserPrompt(
                runA, metrics.timeseries(idA, runA.state(), false, null),
                runB, metrics.timeseries(idB, runB.state(), false, null));
        quota.acquire();
        AiResult result = ai.complete(compareSystemPrompt, userPrompt);

        ParsedCompare parsed = parseCompareResponse(result.text());
        String storedJson = writeStored(parsed.summary(), parsed.findings());
        if (bothTerminal) {
            cache.upsert(KIND_COMPARE, cacheKey, PROMPT_VERSION, storedJson,
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

    private String buildRunUserPrompt(Run run, MetricsTimeseries ts, List<Map<String, Object>> rollup) {
        Map<String, Object> digest = seriesDigest(run, ts);
        digest.put("statusTotals", statusTotals(ts.series().statusCodes()));
        digest.put("perLabel", topLabels(rollup));
        return "Run digest (JSON):\n\n" + toJson(digest)
                + "\n\nRespond with the JSON object as instructed.";
    }

    private String buildCompareUserPrompt(Run runA, MetricsTimeseries tsA,
                                          Run runB, MetricsTimeseries tsB) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runA", seriesDigest(runA, tsA));
        payload.put("runB", seriesDigest(runB, tsB));
        return "Digests for the two runs (JSON):\n\n" + toJson(payload)
                + "\n\nRespond with the JSON object as instructed.";
    }

    /**
     * Compact, token-cheap digest of a run's headline metrics: a few aggregates
     * plus a duration-scaled downsample of each series (finer for long runs,
     * coarse for short ones) so Claude can read the shape without us shipping
     * every per-second point. The raw timeseries is ~20-80× larger and adds no
     * analytical value at summary altitude.
     */
    private Map<String, Object> seriesDigest(Run run, MetricsTimeseries ts) {
        MetricsTimeseries.Series s = ts.series();
        List<MetricsTimeseries.TimeseriesPoint> tps = s.tps();
        List<MetricsTimeseries.TimeseriesPoint> rt = s.avgRtMs();
        List<MetricsTimeseries.TimeseriesPoint> err = s.errorPct();
        long durationSec = durationSeconds(ts);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", run.runId());
        meta.put("application", run.application());
        meta.put("state", run.state() == null ? null : run.state().name());
        meta.put("durationSec", durationSec);
        meta.put("fleetSize", run.fleetMembers() == null ? 0 : run.fleetMembers().size());

        int buckets = shapeBuckets(durationSec, tps.size());
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("buckets", buckets);
        shape.put("bucketSec", buckets > 0 ? Math.max(1, durationSec / buckets) : durationSec);
        shape.put("tps", downsample(tps, buckets, 1));
        shape.put("avgRtMs", downsample(rt, buckets, 0));
        shape.put("errorPct", downsample(err, buckets, 2));

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("run", meta);
        d.put("avgTps", round(avg(tps), 1));
        d.put("peakTps", round(max(tps), 1));
        d.put("avgRtMs", round(avg(rt), 0));
        d.put("avgErrorPct", round(avg(err), 2));
        d.put("peakErrorPct", round(max(err), 2));
        d.put("shape", shape);
        return d;
    }

    /** Sum each status code's per-second counts into one small totals map. */
    private Map<String, Long> statusTotals(Map<String, List<MetricsTimeseries.TimeseriesPoint>> codes) {
        Map<String, Long> totals = new LinkedHashMap<>();
        if (codes == null) return totals;
        for (var e : codes.entrySet()) {
            long sum = 0;
            for (var p : e.getValue()) sum += Math.round(p.v());
            totals.put(e.getKey(), sum);
        }
        return totals;
    }

    /** Top {@value #TOP_LABELS} labels by throughput, rounded — the prompt notes this is not exhaustive. */
    private List<Map<String, Object>> topLabels(List<Map<String, Object>> rollup) {
        if (rollup == null) return List.of();
        return rollup.stream()
                .sorted((a, b) -> Long.compare(asLong(b.get("totalThroughput")), asLong(a.get("totalThroughput"))))
                .limit(TOP_LABELS)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", r.get("label"));
                    m.put("throughput", asLong(r.get("totalThroughput")));
                    m.put("errorPct", round(asDouble(r.get("errorRate")) * 100.0, 2));
                    m.put("p50", round(asDouble(r.get("avgP50Ms")), 0));
                    m.put("p95", round(asDouble(r.get("avgP95Ms")), 0));
                    m.put("p99", round(asDouble(r.get("avgP99Ms")), 0));
                    m.put("maxMs", asLong(r.get("maxMs")));
                    m.put("maxThreads", asLong(r.get("maxActiveThreads")));
                    return m;
                })
                .toList();
    }

    // ── digest math ────────────────────────────────────────────────────────

    /**
     * Bucket count for the shape arrays: ~1 per {@value #TARGET_BUCKET_SEC}s of
     * run, clamped to [{@value #MIN_SHAPE_BUCKETS}, {@value #MAX_SHAPE_BUCKETS}]
     * and never more than the points we actually have (no upsampling).
     */
    private static int shapeBuckets(long durationSec, int availablePoints) {
        if (availablePoints <= 0) return 0;
        long target = Math.round(durationSec / (double) TARGET_BUCKET_SEC);
        int clamped = (int) Math.max(MIN_SHAPE_BUCKETS, Math.min(MAX_SHAPE_BUCKETS, target));
        return Math.min(clamped, availablePoints);
    }

    private static long durationSeconds(MetricsTimeseries ts) {
        Long from = ts.fromSecond(), to = ts.toSecond();
        if (from != null && to != null && to >= from) return to - from + 1;
        return (long) ts.series().tps().size() * Math.max(1, ts.bucketSize());
    }

    /** Even-width downsample to {@code buckets} points, averaging each slice, rounded. */
    private static List<Number> downsample(List<MetricsTimeseries.TimeseriesPoint> pts, int buckets, int decimals) {
        List<Number> out = new ArrayList<>(Math.max(0, buckets));
        int n = pts.size();
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

    private static double avg(List<MetricsTimeseries.TimeseriesPoint> pts) {
        if (pts.isEmpty()) return 0;
        double sum = 0;
        for (var p : pts) sum += p.v();
        return sum / pts.size();
    }

    private static double max(List<MetricsTimeseries.TimeseriesPoint> pts) {
        double m = 0;
        for (var p : pts) if (p.v() > m) m = p.v();
        return m;
    }

    /** Round to {@code decimals}; 0 decimals returns a Long so the JSON has no ".0" noise. */
    private static Number round(double v, int decimals) {
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

    // ── response parsing (tolerant) ─────────────────────────────────────────

    private ParsedRun parseRunResponse(String text) {
        JsonNode root = tryParseJsonObject(text);
        if (root == null) {
            // Model didn't return parseable JSON — surface its text as the
            // summary rather than wasting the paid call.
            return new ParsedRun(text.strip(), List.of());
        }
        String summary = root.path("summary").asText("");
        List<RunInsights.Finding> findings = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            findings.add(new RunInsights.Finding(
                    normalizeSeverity(f.path("severity").asText("info")),
                    f.path("title").asText(""),
                    f.path("detail").asText("")));
        }
        return new ParsedRun(summary.isBlank() ? text.strip() : summary, findings);
    }

    private ParsedCompare parseCompareResponse(String text) {
        JsonNode root = tryParseJsonObject(text);
        if (root == null) {
            return new ParsedCompare(text.strip(), List.of());
        }
        String summary = root.path("summary").asText("");
        List<CompareInsights.CompareFinding> findings = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            findings.add(new CompareInsights.CompareFinding(
                    f.path("metric").asText(""),
                    f.path("verdict").asText(""),
                    f.path("delta").asText("")));
        }
        return new ParsedCompare(summary.isBlank() ? text.strip() : summary, findings);
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
                    f.path("detail").asText("")));
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
                    f.path("delta").asText("")));
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

    private record ParsedRun(String summary, List<RunInsights.Finding> findings) { }

    private record ParsedCompare(String summary, List<CompareInsights.CompareFinding> findings) { }
}
