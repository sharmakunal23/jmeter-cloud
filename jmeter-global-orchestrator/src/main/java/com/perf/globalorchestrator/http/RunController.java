package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.globalorchestrator.client.WorkerRef;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseriesBatch;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunEvent;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import com.perf.globalorchestrator.service.CachingLogTailService;
import com.perf.globalorchestrator.service.CachingMetricsService;
import com.perf.globalorchestrator.service.RunPurgeService;
import com.perf.globalorchestrator.service.RunPurgeService.PurgeResult;
import com.perf.globalorchestrator.service.RunPurgeService.RunNotPurgeableException;
import com.perf.globalorchestrator.service.RunService;
import com.perf.globalorchestrator.service.RunService.FleetSizeExceededException;
import com.perf.globalorchestrator.service.RunService.InsufficientCapacityException;
import com.perf.globalorchestrator.service.RunService.RegionShortfall;
import com.perf.globalorchestrator.service.RunService.RunNotAbortableException;
import com.perf.globalorchestrator.service.RunService.RunNotDeletableException;
import com.perf.globalorchestrator.service.RunService.RunNotFoundException;
import com.perf.globalorchestrator.service.RunService.RunNotInScalableStateException;
import com.perf.globalorchestrator.service.RunService.RunNotScalableNoApplicationException;
import com.perf.globalorchestrator.service.RunService.UnknownRegionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import com.perf.globalorchestrator.observability.MdcEnrichmentFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for run management. camelCase routes per the platform-wide
 * convention. JSON request/response shapes live in {@code api/openapi.yaml}.
 */
@RestController
@RequestMapping("/api/v1")
public class RunController {

    private static final Logger LOG = LoggerFactory.getLogger(RunController.class);

    private final RunService runs;
    private final RunPurgeService runPurge;
    private final CachingMetricsService metrics;
    private final CachingLogTailService logTail;

    public RunController(RunService runs,
                         RunPurgeService runPurge,
                         CachingMetricsService metrics,
                         CachingLogTailService logTail) {
        this.runs = runs;
        this.runPurge = runPurge;
        this.metrics = metrics;
        this.logTail = logTail;
    }

    @PostMapping("/runs")
    public ResponseEntity<Run> startRun(
            @RequestBody StartRunRequest request,
            @RequestParam(name = "bestEffort", required = false, defaultValue = "false") boolean bestEffort,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        Run run = runs.startRun(request, bestEffort, Actor.fromHeader(actorHeader));
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    /**
     * Adds workers to a RUNNING run. The
     * original run's testPlan + dataFiles blob IDs are sourced from the
     * persisted run row, so the request body only carries the per-region
     * allocation (and optional perNodeProperties for the new workers).
     *
     * <p>Returns the post-scale {@link Run} (with all members — original +
     * newly added) plus a granted/requested ledger so the UI can render
     * "added 2 of the 3 you asked for" for {@code bestEffort=true} partial
     * fulfilments.
     */
    @PostMapping("/runs/{runId:" + Ulid.PATTERN + "}/scaleUp")
    public ResponseEntity<ScaleUpRunResponse> scaleUpRun(
            @PathVariable String runId,
            @RequestBody ScaleUpRunRequest request,
            @RequestParam(name = "bestEffort", required = false, defaultValue = "false") boolean bestEffort,
            @RequestParam(name = "spinShortfall", required = false, defaultValue = "false") boolean spinShortfall,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        ScaleUpRunResponse response =
                runs.scaleUpRun(runId, request, bestEffort, spinShortfall, Actor.fromHeader(actorHeader));
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Drains workers from a RUNNING run via
     * graceful drain (JMeter's TCP shutdown port → in-flight samplers
     * complete → DRAINED). Body supplies exactly one of:
     * <ul>
     *   <li>{@code workerIds} — explicit list of pods to drain.</li>
     *   <li>{@code allocations} — per-region count; service picks the
     *       N most-recently-created RUNNING members in that region.</li>
     * </ul>
     *
     * <p>Returns the post-scale {@link Run} (drained members in DRAINING /
     * DRAINED) + a {@code drained} workerId list + a {@code skipped}
     * list (already-terminal, already-DRAINING, RPC failures).
     */
    @PostMapping("/runs/{runId:" + Ulid.PATTERN + "}/scaleDown")
    public ResponseEntity<ScaleDownRunResponse> scaleDownRun(
            @PathVariable String runId,
            @RequestBody ScaleDownRunRequest request,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        ScaleDownRunResponse response = runs.scaleDownRun(runId, request, Actor.fromHeader(actorHeader));
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * UX-DYNAMICS T5 — pushes runtime JMeter property values to the selected
     * (default: all) ACCEPTED/RUNNING workers of a RUNNING run in one shot,
     * via each worker's BeanShell server. Only plan values read through
     * {@code ${__P(name)}} observe the update, at their next evaluation.
     * A partial failure is a 200 — the per-worker rows carry the truth.
     */
    @PostMapping("/runs/{runId:" + Ulid.PATTERN + "}/properties")
    public ResponseEntity<UpdateRunPropertiesResponse> updateRunProperties(
            @PathVariable String runId,
            @RequestBody UpdateRunPropertiesRequest request,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        return ResponseEntity.ok(runs.updateRunProperties(runId, request, Actor.fromHeader(actorHeader)));
    }

    /**
     * Force-terminates a run (the run-abort / zombie-run cleanup primitive).
     * Unlike {@link #scaleDownRun}'s graceful drain, abort rolls the WHOLE run
     * to ABORTED, best-effort hard-kills each live worker
     * ({@code POST /api/v1/test/abort}), and releases every fleet-member
     * binding — freeing the run's pods for re-claim / drain. This is the clean
     * way to clear a run whose workers have died but whose row is stuck
     * non-terminal (which otherwise pins its pods forever).
     *
     * <p>Body is optional; the {@code X-Actor} header attributes the action and
     * an optional {@code reason} note rides onto the ABORT audit event. Returns
     * the post-abort {@link Run} (state ABORTED). 404 {@code RUN_NOT_FOUND} for
     * an unknown run; 409 {@code RUN_NOT_ABORTABLE} if it is already terminal.
     */
    @PostMapping("/runs/{runId:" + Ulid.PATTERN + "}/abort")
    public ResponseEntity<Run> abortRun(
            @PathVariable String runId,
            @RequestBody(required = false) AbortRunRequest request,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        String reason = request == null ? null : request.reason();
        Run run = runs.abortRun(runId, Actor.fromHeader(actorHeader), reason);
        return ResponseEntity.ok(run);
    }

    /**
     * Soft-deletes ("hides") a TERMINAL run so it drops out of the default
     * {@code GET /runs} listing — the declutter primitive so operators see only
     * the runs that matter. Reversible: the row, fleet members, audit trail, and
     * any saved results are retained (a hidden run is still reachable by id and
     * via {@code GET /runs?includeHidden=true}). The {@code X-Actor} header
     * attributes the action and an optional {@code reason} note rides onto the
     * DELETE audit event. Returns the (now-hidden) run. 404 {@code RUN_NOT_FOUND}
     * for an unknown run; 409 {@code RUN_NOT_DELETABLE} if it is still active
     * (only terminal runs can be hidden).
     */
    @DeleteMapping("/runs/{runId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Run> deleteRun(
            @PathVariable String runId,
            @RequestBody(required = false) DeleteRunRequest request,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        String reason = request == null ? null : request.reason();
        Run run = runs.deleteRun(runId, Actor.fromHeader(actorHeader), reason);
        return ResponseEntity.ok(run);
    }

    /**
     * HARD-DELETE / purge — the irreversible second tier of the two-tier delete
     * model. PERMANENTLY removes a run: its result blobs (+ unshared
     * testPlan/dataFiles blobs), its metric rows, its AI-cache + trend
     * rows, and the run row itself (cascading fleet members + audit events). A
     * {@code purgeAudit} tombstone records who/what/when + what was reclaimed.
     *
     * <p>Precondition: the run must already be HIDDEN (via the soft-delete DELETE
     * above) and terminal — "trash, then empty trash." 404 {@code RUN_NOT_FOUND}
     * for an unknown run; 409 {@code RUN_NOT_PURGEABLE} if it is active or has not
     * been hidden first. {@code X-Actor} attributes the action; the optional
     * {@code reason} rides onto the tombstone. Returns a summary of what was
     * reclaimed (incl. {@code blobStepComplete=false} when document-service was
     * unreachable and result blobs were left for a retention sweep).
     */
    @PostMapping("/runs/{runId:" + Ulid.PATTERN + "}/purge")
    public ResponseEntity<Map<String, Object>> purgeRun(
            @PathVariable String runId,
            @RequestBody(required = false) DeleteRunRequest request,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        String reason = request == null ? null : request.reason();
        PurgeResult result = runPurge.purgeRun(runId, Actor.fromHeader(actorHeader), reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId",             result.runId());
        body.put("metricRowsDeleted", result.metricRowsDeleted());
        body.put("blobsDeleted",      result.blobsDeleted());
        body.put("blobStepComplete",  result.blobStepComplete());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/runs")
    public ResponseEntity<List<Run>> listRuns(
            @RequestParam(name = "state",         required = false) String state,
            @RequestParam(name = "application",   required = false) String application,
            @RequestParam(name = "includeHidden", required = false, defaultValue = "false") boolean includeHidden,
            @RequestParam(name = "hidden",        required = false, defaultValue = "false") boolean hidden,
            @RequestParam(name = "offset",        required = false, defaultValue = "0")  int offset,
            @RequestParam(name = "limit",         required = false, defaultValue = "50") int limit) {
        boolean activeOnly = "active".equalsIgnoreCase(state);
        // UI-D3 — paginated + application-filtered. Total count rides on
        // the X-Total-Count response header so the response body stays
        // a flat List<Run> (existing clients keep working unchanged).
        // Soft-delete visibility: hidden=true is the "Archived" view (ONLY hidden
        // runs — the hard-delete/purge surface); otherwise hidden runs are
        // excluded unless includeHidden=true.
        var page = runs.listRuns(activeOnly, application, includeHidden, hidden, offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(page.total()))
                .body(page.runs());
    }

    @GetMapping("/runs/{runId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Run> getRun(@PathVariable String runId) {
        return ResponseEntity.ok(runs.getRun(runId));
    }

    /**
     * The run's audit timeline (who started / scaled / drained,
     * when, and with what result), newest first. Paginated since a
     * long-running test can accumulate many events: {@code ?offset=N&limit=25}
     * with the total count on the {@code X-Total-Count} header (body stays a
     * flat array, mirroring {@code GET /runs}). 404 if the run is unknown.
     */
    @GetMapping("/runs/{runId:" + Ulid.PATTERN + "}/events")
    public ResponseEntity<List<RunEvent>> getRunEvents(
            @PathVariable String runId,
            @RequestParam(name = "offset", required = false, defaultValue = "0")  int offset,
            @RequestParam(name = "limit",  required = false, defaultValue = "25") int limit) {
        var page = runs.getRunEvents(runId, offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(page.total()))
                .body(page.events());
    }

    @GetMapping("/runs/{runId:" + Ulid.PATTERN + "}/status")
    public ResponseEntity<Map<String, Object>> getRunStatus(@PathVariable String runId) {
        Run run = runs.refreshAndGet(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.runId());
        body.put("state", run.state().name());
        body.put("stateReason", run.stateReason());
        body.put("startedAt", run.startedAt());
        body.put("completedAt", run.completedAt());
        body.put("members", run.fleetMembers());
        return ResponseEntity.ok(body);
    }

    /**
     * Step 19 — proxies the per-pod log tail through the global so the
     * UI only ever talks to one origin. Looks up the pod's
     * {@code podBaseUrl} from {@code runFleetMember}, calls the local
     * orchestrator's {@code GET /api/v1/logs?stream=&tail=N}, and
     * forwards the raw text/plain body.
     *
     * <p>UI-1 (2026-05-10) wired through the {@code stream} selector:
     * {@code console} (default) tails the orchestrator's in-memory ring
     * of the JMeter child's stdout/stderr; {@code jmeter} tails
     * jmeter.log on disk. Anything else: the local orchestrator returns
     * 400 BAD_REQUEST and we forward both the status and its diagnostic
     * message so the operator sees the cause.
     */
    @GetMapping(path = "/runs/{runId:" + Ulid.PATTERN + "}/members/{workerId}/logs",
                produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> getMemberLogs(
            @PathVariable String runId,
            @PathVariable String workerId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "console") String stream,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "200") int tail) {
        Run run = runs.getRun(runId);  // 404s if unknown
        RunFleetMember member = run.fleetMembers().stream()
                .filter(m -> workerId.equals(m.workerId()))
                .findFirst()
                .orElseThrow(() -> new RunService.RunNotFoundException(
                        "member " + workerId + " not found in run " + runId));
        if (member.podBaseUrl() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("podBaseUrl not recorded for member " + workerId);
        }
        int safeTail = Math.max(1, Math.min(tail, 10_000));
        // Cached when the member is terminal (its log buffer is
        // frozen), otherwise re-fetched live. Keyed on (runId, workerId,
        // stream, tail) so a reused pod's later run can't collide.
        boolean memberTerminal = member.state() != null && member.state().isTerminal();
        LogsResult result = logTail.getLogs(runId, workerId, WorkerRef.of(member), stream, safeTail, memberTerminal);
        if (result.statusCode() == 0) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("pod " + workerId + " unreachable at " + member.podBaseUrl());
        }
        if (result.statusCode() == 404) {
            // No log buffer yet — surface as 200 + empty body so the UI
            // renders "no logs yet" without an error banner.
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("");
        }
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(result.body() == null ? "" : result.body());
    }

    /**
     * The aggregate report: one row per label over the run (or its trailing
     * {@code window}), optionally narrowed to labels starting with
     * {@code labelPrefix}. Cached when the run is terminal, straight to SQL otherwise.
     */
    @GetMapping("/runs/{runId:" + Ulid.PATTERN + "}/metrics")
    public ResponseEntity<Map<String, Object>> getRunMetrics(
            @PathVariable String runId,
            @RequestParam(name = "window", defaultValue = "all") String window,
            @RequestParam(name = "labelPrefix", required = false) String labelPrefix,
            @RequestParam(name = "labelLimit", required = false) String labelLimit) {
        Run run = runs.getRun(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.runId());
        body.put("state", run.state().name());
        body.put("byLabel", metrics.rollupByLabel(runId, run.state(), parseWindowSeconds(window),
                parseLabelPrefix(labelPrefix), parseLabelLimit(labelLimit)));
        return ResponseEntity.ok(body);
    }

    /**
     * The Metrics tab's headline numbers — the hosted dashboard's "Key Metrics"
     * stat row and "Summary by Application" table — over the run or its
     * trailing {@code window}. Zeros (200, not 404) while the run has no rows.
     */
    @GetMapping("/runs/{runId:" + Ulid.PATTERN + "}/summary")
    public ResponseEntity<RunSummary> getRunSummary(
            @PathVariable String runId,
            @RequestParam(name = "window", defaultValue = "all") String window) {
        Run run = runs.getRun(runId);
        return ResponseEntity.ok(metrics.summary(runId, run.state(), parseWindowSeconds(window)));
    }

    /**
     * Bucketed timeseries for the run-detail Metrics tab's charts, with the
     * splits the tab can ask for. Empty arrays during PREPARING (rather than
     * 404) so the polling UI doesn't flash a red error before the consumer has
     * written its first row.
     */
    @GetMapping("/runs/{runId:" + Ulid.PATTERN + "}/timeseries")
    public ResponseEntity<MetricsTimeseries> getRunTimeseries(
            @PathVariable String runId,
            @RequestParam(name = "byRegion", defaultValue = "false") boolean byRegion,
            @RequestParam(name = "byApplication", defaultValue = "false") boolean byApplication,
            @RequestParam(name = "byLabel", defaultValue = "false") boolean byLabel,
            @RequestParam(name = "labelPrefix", required = false) String labelPrefix,
            @RequestParam(name = "labelLimit", required = false) String labelLimit,
            @RequestParam(name = "granularity", required = false) Integer granularity,
            @RequestParam(name = "window", defaultValue = "all") String window) {
        // getRun throws RunNotFoundException on miss → 404, the same shape as
        // /metrics and /summary so the UI's error path is consistent.
        Run run = runs.getRun(runId);
        Long windowSeconds = parseWindowSeconds(window);
        if (granularity != null && granularity != 15 && granularity != 30 && granularity != 60) {
            throw new IllegalArgumentException("granularity must be 15, 30 or 60 seconds; got " + granularity);
        }
        return ResponseEntity.ok(metrics.timeseries(runId, run.state(), byRegion, byApplication, byLabel,
                parseLabelPrefix(labelPrefix), parseLabelLimit(labelLimit), granularity, windowSeconds));
    }

    /**
     * {@code labelLimit}: absent = the 10 busiest labels, {@code all} = every
     * label, else 1..50 (400 otherwise).
     */
    private static int parseLabelLimit(String labelLimit) {
        if (labelLimit == null || labelLimit.isBlank()) {
            return MetricsTimeseriesRepository.LABELS_SHOWN;
        }
        if (labelLimit.trim().equalsIgnoreCase("all")) {
            return MetricsTimeseriesRepository.LABELS_ALL;
        }
        int n;
        try {
            n = Integer.parseInt(labelLimit.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("labelLimit must be 'all' or a number between 1 and " + MetricsTimeseriesRepository.LABELS_MAX);
        }
        if (n < 1 || n > MetricsTimeseriesRepository.LABELS_MAX) {
            throw new IllegalArgumentException("labelLimit must be 'all' or a number between 1 and " + MetricsTimeseriesRepository.LABELS_MAX);
        }
        return n;
    }

    /** A label prefix is plain text of at most {@value #LABEL_PREFIX_MAX} chars; blank = none. */
    private static final int LABEL_PREFIX_MAX = 100;

    private static String parseLabelPrefix(String labelPrefix) {
        if (labelPrefix == null || labelPrefix.isBlank()) {
            return null;
        }
        String trimmed = labelPrefix.trim();
        if (trimmed.length() > LABEL_PREFIX_MAX) {
            throw new IllegalArgumentException("labelPrefix must be at most " + LABEL_PREFIX_MAX + " characters");
        }
        return trimmed;
    }

    /**
     * Maps the {@code window} query param to a number of seconds (or {@code null}
     * for the whole test). Restricted to the fixed set the UI selector offers so
     * each value caches predictably; anything else is a 400 (handled by
     * {@link #handleBadRequest}).
     */
    private static Long parseWindowSeconds(String window) {
        if (window == null || window.isBlank() || window.equalsIgnoreCase("all")) {
            return null;
        }
        return switch (window) {
            case "5m"  -> 5L * 60;
            case "15m" -> 15L * 60;
            case "10m" -> 10L * 60;
            case "30m" -> 30L * 60;
            case "1h"  -> 60L * 60;
            case "2h"  -> 2L * 60 * 60;
            case "4h"  -> 4L * 60 * 60;
            default -> throw new IllegalArgumentException(
                    "unknown window '" + window + "'; allowed: 5m, 10m, 15m, 30m, 1h, 2h, 4h, all");
        };
    }

    /**
     * HM-5 — batched timeseries for the side-by-side comparison view.
     * The UI compares exactly two runs (decision logged 2026-05-10), so
     * we enforce {@code ids.size() == 2}: a single id should hit the
     * per-run endpoint above; three or more is out of scope.
     *
     * <p>Partial-200 shape — if one of the two runs has been purged
     * (or never existed) we still return the other's payload, with the
     * missing id called out in {@link MetricsTimeseriesBatch#missing()}.
     * Operators comparing runs are usually doing post-hoc analysis;
     * making them re-issue the request to find out *which* run is gone
     * would be a worse experience than telling them upfront.
     */
    @GetMapping("/runs/timeseries")
    public ResponseEntity<MetricsTimeseriesBatch> getRunTimeseriesBatch(
            @RequestParam(name = "ids") String idsParam) {
        if (idsParam == null || idsParam.isBlank()) {
            throw new IllegalArgumentException("ids query parameter is required");
        }
        // Distinct-order-preserving split. LinkedHashMap-style dedupe
        // keeps the response runs map iterating in the order the operator
        // submitted (matches the page's left/right column expectation).
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String raw : idsParam.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) ids.add(id);
        }
        if (ids.size() != 2) {
            throw new IllegalArgumentException(
                    "ids must contain exactly 2 distinct run ids; got "
                            + ids.size() + " (the comparison view supports two runs)");
        }
        Map<String, MetricsTimeseries> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            try {
                Run run = runs.getRun(id);  // throws RunNotFoundException on miss
                // Per-id terminal gating: comparing two finished
                // runs hits zero SQL after the first request; a still-active
                // run in the pair always re-queries. The compare view is
                // aggregate-only, whole-test — no region split, no window.
                resolved.put(id, metrics.timeseries(id, run.state(), false, null));
            } catch (RunNotFoundException e) {
                missing.add(id);
            }
        }
        return ResponseEntity.ok(new MetricsTimeseriesBatch(resolved, missing));
    }

    // ── error handling ──────────────────────────────────────────────────

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(RunNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code",    "RUN_NOT_FOUND",
                "message", e.getMessage()));
    }

    @ExceptionHandler(RunService.RunPropertiesNotUpdatableException.class)
    ResponseEntity<Map<String, Object>> handlePropertiesNotUpdatable(
            RunService.RunPropertiesNotUpdatableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",    "RUN_NOT_RUNNING",
                "message", e.getMessage()));
    }

    @ExceptionHandler(InsufficientCapacityException.class)
    ResponseEntity<Map<String, Object>> handleInsufficientCapacity(InsufficientCapacityException e) {
        // 503 — client should retry once a pod becomes IDLE again, or
        // resubmit with ?bestEffort=true to accept the partial claim.
        // The structured shortfall lets the UI highlight which regions
        // fell short instead of parsing the freeform message.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",    "INSUFFICIENT_CAPACITY");
        body.put("message", e.getMessage());
        if (!e.shortfall().isEmpty()) {
            List<Map<String, Object>> shortfallList = new ArrayList<>(e.shortfall().size());
            for (Map.Entry<String, RegionShortfall> en : e.shortfall().entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("region",    en.getKey());
                row.put("requested", en.getValue().requested());
                row.put("claimed",   en.getValue().claimed());
                shortfallList.add(row);
            }
            body.put("shortfall", shortfallList);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(UnknownRegionException.class)
    ResponseEntity<Map<String, Object>> handleUnknownRegion(UnknownRegionException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",    "UNKNOWN_REGION");
        body.put("message", e.getMessage());
        body.put("regions", e.regions());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(FleetSizeExceededException.class)
    ResponseEntity<Map<String, Object>> handleFleetSizeExceeded(FleetSizeExceededException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",      "FLEET_SIZE_EXCEEDED");
        body.put("message",   e.getMessage());
        body.put("requested", e.requested());
        body.put("max",       e.max());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(com.perf.globalorchestrator.service.RunService.GroupCapacityExceededException.class)
    ResponseEntity<Map<String, Object>> handleGroupCapacity(
            com.perf.globalorchestrator.service.RunService.GroupCapacityExceededException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",        "APPLICATION_CAPACITY_EXCEEDED");
        body.put("message",     e.getMessage());
        body.put("group",       e.group());
        body.put("region",      e.region());
        body.put("max",         e.max());
        body.put("active",      e.active());
        body.put("requested",   e.requested());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RunNotInScalableStateException.class)
    ResponseEntity<Map<String, Object>> handleRunNotScalable(RunNotInScalableStateException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",         "RUN_NOT_SCALABLE");
        body.put("message",      e.getMessage());
        body.put("runId",        e.runId());
        body.put("currentState", e.currentState().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RunNotAbortableException.class)
    ResponseEntity<Map<String, Object>> handleRunNotAbortable(RunNotAbortableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",         "RUN_NOT_ABORTABLE");
        body.put("message",      e.getMessage());
        body.put("runId",        e.runId());
        body.put("currentState", e.currentState().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RunNotDeletableException.class)
    ResponseEntity<Map<String, Object>> handleRunNotDeletable(RunNotDeletableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",         "RUN_NOT_DELETABLE");
        body.put("message",      e.getMessage());
        body.put("runId",        e.runId());
        body.put("currentState", e.currentState().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RunNotPurgeableException.class)
    ResponseEntity<Map<String, Object>> handleRunNotPurgeable(RunNotPurgeableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",    "RUN_NOT_PURGEABLE");
        body.put("message", e.getMessage());
        body.put("runId",   e.runId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RunNotScalableNoApplicationException.class)
    ResponseEntity<Map<String, Object>> handleRunNoApp(RunNotScalableNoApplicationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",    "RUN_NOT_SCALABLE_NO_APPLICATION");
        body.put("message", e.getMessage());
        body.put("runId",   e.runId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code",    "INVALID_REQUEST",
                "message", e.getMessage()));
    }

    /**
     * Spring's default mapping for a missing required {@code @RequestParam}
     * is 400, but our catch-all {@code Exception} handler below would
     * otherwise swallow it into a 500. Map it explicitly so HM-5's
     * batch endpoint surfaces the right status when {@code ?ids=…} is
     * absent.
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code",    "INVALID_REQUEST",
                "message", e.getMessage()));
    }

    // This controller's catch-all would otherwise swallow these before the
    // RegionExceptionHandler advice sees them — same bodies, one source.
    @ExceptionHandler(com.perf.globalorchestrator.region.RegionUnavailableException.class)
    ResponseEntity<Map<String, String>> handleRegionUnavailable(com.perf.globalorchestrator.region.RegionUnavailableException e) {
        return RegionExceptionHandler.responseFor(e);
    }

    @ExceptionHandler(com.perf.globalorchestrator.region.RegionalCallException.class)
    ResponseEntity<Map<String, String>> handleRegionalCall(com.perf.globalorchestrator.region.RegionalCallException e) {
        return RegionExceptionHandler.responseFor(e);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        LOG.error("Unexpected RunController failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code",    "INTERNAL_ERROR",
                "message", "Unexpected failure — see service logs."));
    }
}
