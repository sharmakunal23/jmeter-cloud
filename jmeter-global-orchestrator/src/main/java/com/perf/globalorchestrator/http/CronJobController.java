package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.client.DocumentServiceClient.TemplateUnavailableException;
import com.perf.globalorchestrator.client.TemplateBody;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobFire;
import com.perf.globalorchestrator.domain.CronJobKind;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.observability.TracingFilter;
import com.perf.globalorchestrator.repo.ApplicationCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.CronJobFireHistoryRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.service.CronFireService;
import com.perf.globalorchestrator.service.CronFireService.FireResult;
import com.perf.globalorchestrator.service.CronSchedule;
import com.perf.globalorchestrator.service.CronSchedule.InvalidCronException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AUTOMATION — REST surface for CRON schedules. The contract (paths, field
 * names, {@code {items:[...]}} list shape) is taken verbatim from the UI stub
 * {@code jmeter-cloud-ui/src/api/automation.ts} so flipping that stub to live
 * data is the promised one-line change.
 *
 * <p>Conventions mirror {@link ApplicationController}: inner exception classes
 * + {@code @ExceptionHandler}s returning {@code {code,message}}, the
 * {@code X-Actor} header read via {@link Actor#fromHeader}, and validation that
 * fails fast with a 400.
 *
 * <p>A schedule pairs an application + a saved Template + a cron expression.
 * Create/update validate that the cron parses, the application is registered,
 * the template is fetchable from document-service, and the template's own
 * application agrees with the schedule's — so a scheduled run can never quietly
 * target a different application than the one the operator named.
 */
@RestController
@RequestMapping("/api/v1/cronJobs")
public class CronJobController {

    private static final Logger LOG = LoggerFactory.getLogger(CronJobController.class);
    private static final int MAX_NAME_LEN = 128;
    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;

    private final CronJobRepository cronJobs;
    private final CronJobFireHistoryRepository fireHistory;
    private final ApplicationRepository applications;
    private final ApplicationCapacityRepository capacities;
    private final DocumentServiceClient documentService;
    private final CronFireService fireService;

    public CronJobController(CronJobRepository cronJobs,
                             CronJobFireHistoryRepository fireHistory,
                             ApplicationRepository applications,
                             ApplicationCapacityRepository capacities,
                             DocumentServiceClient documentService,
                             CronFireService fireService) {
        this.cronJobs = cronJobs;
        this.fireHistory = fireHistory;
        this.applications = applications;
        this.capacities = capacities;
        this.documentService = documentService;
        this.fireService = fireService;
    }

    // ── Reads ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<CronJobListResponse> list(
            @RequestParam(value = "application", required = false) String application) {
        List<CronJobSummary> items = cronJobs.findAll(application).stream()
                .map(CronJobSummary::from)
                .toList();
        return ResponseEntity.ok(new CronJobListResponse(items));
    }

    @GetMapping("/{cronJobId:" + Ulid.PATTERN + "}")
    public ResponseEntity<CronJobSummary> get(@PathVariable String cronJobId) {
        return ResponseEntity.ok(CronJobSummary.from(require(cronJobId)));
    }

    @GetMapping("/{cronJobId:" + Ulid.PATTERN + "}/history")
    public ResponseEntity<Map<String, List<CronJobFire>>> history(
            @PathVariable String cronJobId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        require(cronJobId); // 404 if unknown
        int safeLimit = limit == null ? DEFAULT_HISTORY_LIMIT
                : Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        return ResponseEntity.ok(Map.of("items", fireHistory.findByCronJobId(cronJobId, safeLimit)));
    }

    // ── Create / update / delete ───────────────────────────────────────

    @PostMapping
    public ResponseEntity<CronJobSummary> create(
            @RequestBody CronJobRequest req,
            @RequestHeader(value = TracingFilter.HEADER_ACTOR, required = false) String actorHeader) {
        String name = validateName(req.name());
        String timeZone = normaliseTimeZone(req.timeZone());
        CronSchedule.validate(req.cronExpression(), timeZone);
        CronJobKind kind = resolveKind(req.kind());
        Resolved r = resolveForKind(kind, req);

        Instant now = Instant.now();
        CronJob job = new CronJob(
                Ulid.generate(), name, r.applicationName(), r.templateBlobId(),
                req.cronExpression().trim(), timeZone, /* enabled */ true,
                Actor.fromHeader(actorHeader).name(), now,
                /* lastFiredAt */ null, /* lastFiredRunId */ null, /* lastFireStatus */ null,
                CronSchedule.nextFireAfter(req.cronExpression(), timeZone, now),
                /* claimedAt */ null,
                kind, r.region(), r.recipients(), r.customSubject(), r.customIntro());
        try {
            cronJobs.insert(job);
        } catch (DuplicateKeyException e) {
            throw new CronJobConflictException(r.applicationName(), name);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(CronJobSummary.from(job));
    }

    @PutMapping("/{cronJobId:" + Ulid.PATTERN + "}")
    public ResponseEntity<CronJobSummary> update(
            @PathVariable String cronJobId,
            @RequestBody CronJobRequest req) {
        CronJob existing = require(cronJobId);
        String name = validateName(req.name());
        String timeZone = normaliseTimeZone(req.timeZone());
        CronSchedule.validate(req.cronExpression(), timeZone);
        CronJobKind kind = resolveKind(req.kind());
        Resolved r = resolveForKind(kind, req);

        // Recompute nextFireAt from the new expression — but only if the
        // schedule is enabled (a disabled schedule keeps nextFireAt null so the
        // sweep can't see it).
        Instant nextFireAt = existing.enabled()
                ? CronSchedule.nextFireAfter(req.cronExpression(), timeZone, Instant.now())
                : null;
        try {
            cronJobs.update(cronJobId, name, r.applicationName(), r.templateBlobId(),
                    req.cronExpression().trim(), timeZone, nextFireAt, kind, r.region(), r.recipients(),
                    r.customSubject(), r.customIntro());
        } catch (DuplicateKeyException e) {
            throw new CronJobConflictException(r.applicationName(), name);
        }
        return ResponseEntity.ok(CronJobSummary.from(require(cronJobId)));
    }

    @DeleteMapping("/{cronJobId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Void> delete(@PathVariable String cronJobId) {
        require(cronJobId); // 404 if unknown
        cronJobs.delete(cronJobId);
        return ResponseEntity.noContent().build();
    }

    // ── Lifecycle actions ──────────────────────────────────────────────

    @PostMapping("/{cronJobId:" + Ulid.PATTERN + "}/enable")
    public ResponseEntity<CronJobSummary> enable(@PathVariable String cronJobId) {
        CronJob job = require(cronJobId);
        Instant next = CronSchedule.nextFireAfter(job.cronExpression(), job.timeZone(), Instant.now());
        cronJobs.setEnabled(cronJobId, true, next);
        return ResponseEntity.ok(CronJobSummary.from(require(cronJobId)));
    }

    @PostMapping("/{cronJobId:" + Ulid.PATTERN + "}/disable")
    public ResponseEntity<CronJobSummary> disable(@PathVariable String cronJobId) {
        require(cronJobId);
        cronJobs.setEnabled(cronJobId, false, null);
        return ResponseEntity.ok(CronJobSummary.from(require(cronJobId)));
    }

    /**
     * Skip the next scheduled fire — advance {@code nextFireAt} to the slot
     * after the current one, without firing. Lets an operator say "not tonight"
     * for a one-off without disabling the whole schedule. 409 if the schedule is
     * disabled or has no upcoming fire (nothing to skip).
     */
    @PostMapping("/{cronJobId:" + Ulid.PATTERN + "}/skipNext")
    public ResponseEntity<CronJobSummary> skipNext(@PathVariable String cronJobId) {
        CronJob job = require(cronJobId);
        if (!job.enabled() || job.nextFireAt() == null) {
            throw new NothingToSkipException(job.name());
        }
        Instant after = CronSchedule.nextFireAfter(job.cronExpression(), job.timeZone(), job.nextFireAt());
        cronJobs.setNextFireAt(cronJobId, after);
        return ResponseEntity.ok(CronJobSummary.from(require(cronJobId)));
    }

    /**
     * Operator-triggered manual fire — same launch path as a scheduled fire,
     * attributed to the operator's {@code X-Actor} rather than the scheduler.
     * Does NOT touch the schedule's cadence ({@code nextFireAt} is unchanged).
     * Returns 202 with the outcome; an unknown schedule is 404.
     */
    @PostMapping("/{cronJobId:" + Ulid.PATTERN + "}/fireNow")
    public ResponseEntity<Map<String, Object>> fireNow(
            @PathVariable String cronJobId,
            @RequestHeader(value = TracingFilter.HEADER_ACTOR, required = false) String actorHeader) {
        CronJob job = require(cronJobId);
        FireResult result = fireService.fire(job, Actor.fromHeader(actorHeader));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("outcome", result.outcome().name());
        body.put("runId", result.runId());
        body.put("error", result.error());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    // ── Validation helpers ─────────────────────────────────────────────

    private CronJob require(String cronJobId) {
        return cronJobs.findById(cronJobId)
                .orElseThrow(() -> new CronJobNotFoundException(cronJobId));
    }

    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CronJobValidationException("name is required");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LEN) {
            throw new CronJobValidationException("name > " + MAX_NAME_LEN + " chars");
        }
        return trimmed;
    }

    /** Returns the registered {@link Application}; 400 if it isn't registered.
     *  Returning the full Application (not just the name) lets the per-kind
     *  region-configured check use {@code applicationId} without a second lookup. */
    private Application validateApplicationExists(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new CronJobValidationException("applicationName is required");
        }
        String trimmed = applicationName.trim();
        return applications.findByName(trimmed)
                .orElseThrow(() -> new UnknownApplicationException(trimmed));
    }

    private static String normaliseTimeZone(String tz) {
        return tz == null || tz.isBlank() ? "UTC" : tz.trim();
    }

    /** Parse the kind, defaulting null/blank to LAUNCH_RUN for backward compat. */
    private static CronJobKind resolveKind(String raw) {
        if (raw == null || raw.isBlank()) return CronJobKind.LAUNCH_RUN;
        try {
            return CronJobKind.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new CronJobValidationException(
                    "kind must be one of LAUNCH_RUN, DRAIN_REGION, PROVISION_REGION, "
                            + "INFRA_READINESS, DAILY_REPORT — got " + raw);
        }
    }

    /** Resolved persisted fields per kind — per-app kinds carry app/template/region;
     *  report kinds carry recipients + optional custom subject/intro and leave
     *  app/template/region null. */
    private record Resolved(String applicationName, String templateBlobId, String region,
                            String recipients, String customSubject, String customIntro) {}

    /**
     * Validate + resolve the kind-dependent fields:
     * <ul>
     *   <li>report kinds (INFRA_READINESS / DAILY_REPORT) — platform-wide: no
     *       application / template / region; recipients optional (env fallback
     *       at fire time).</li>
     *   <li>per-app kinds — application must exist + per-kind template/region
     *       (delegated to {@link #validateKindFields}).</li>
     * </ul>
     */
    private Resolved resolveForKind(CronJobKind kind, CronJobRequest req) {
        if (kind.isReport()) {
            return new Resolved(null, null, null, trimToNull(req.recipients()),
                    trimToNull(req.customSubject()), trimToNull(req.customIntro()));
        }
        Application app = validateApplicationExists(req.applicationName());
        Normalised norm = validateKindFields(kind, req.templateBlobId(), req.region(), app);
        return new Resolved(app.name(), norm.templateBlobId(), norm.region(), null, null, null);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Normalised templateBlobId + region after the per-kind validation. */
    private record Normalised(String templateBlobId, String region) {}

    /**
     * Per-kind cross-field validation:
     * <ul>
     *   <li>LAUNCH_RUN — templateBlobId required + fetchable + its own
     *       application matches the schedule's; region is dropped (null) so a
     *       LAUNCH row never carries a misleading region.</li>
     *   <li>DRAIN_REGION / PROVISION_REGION — region required + must be a
     *       configured row in applicationCapacity for this app
     *       (REGION_NOT_CONFIGURED otherwise); templateBlobId dropped.</li>
     * </ul>
     */
    private Normalised validateKindFields(CronJobKind kind, String templateBlobId,
                                          String region, Application app) {
        switch (kind) {
            case LAUNCH_RUN -> {
                if (templateBlobId == null || templateBlobId.isBlank()) {
                    throw new CronJobValidationException(
                            "templateBlobId is required for kind=LAUNCH_RUN");
                }
                TemplateBody tpl = documentService.fetchTemplate(templateBlobId.trim());
                String tplApp = tpl.application();
                if (tplApp != null && !tplApp.isBlank() && !tplApp.trim().equals(app.name())) {
                    throw new CronJobValidationException(
                            "template targets application '" + tplApp.trim()
                                    + "' but the schedule names '" + app.name() + "'");
                }
                return new Normalised(templateBlobId.trim(), null);
            }
            case DRAIN_REGION, PROVISION_REGION -> {
                if (region == null || region.isBlank()) {
                    throw new CronJobValidationException(
                            "region is required for kind=" + kind);
                }
                String trimmed = region.trim();
                if (capacities.find(app.applicationId(), trimmed).isEmpty()) {
                    throw new RegionNotConfiguredException(app.name(), trimmed);
                }
                return new Normalised(null, trimmed);
            }
        }
        // Unreachable — resolveKind validates the enum upstream.
        throw new IllegalStateException("unhandled kind " + kind);
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    /** Wire response — mirrors {@code CronJobSummary} in automation.ts exactly
     *  (no internal {@code claimedAt}). Phase C — kind + region; templateBlobId
     *  is nullable (DRAIN_REGION / PROVISION_REGION rows don't use one). */
    public record CronJobSummary(
            String cronJobId,
            String name,
            String applicationName,
            String templateBlobId,
            String cronExpression,
            String timeZone,
            boolean enabled,
            String createdBy,
            Instant createdAt,
            Instant lastFiredAt,
            String lastFiredRunId,
            String lastFireStatus,
            Instant nextFireAt,
            CronJobKind kind,
            String region,
            String recipients,
            String customSubject,
            String customIntro) {

        static CronJobSummary from(CronJob c) {
            return new CronJobSummary(
                    c.cronJobId(), c.name(), c.applicationName(), c.templateBlobId(),
                    c.cronExpression(), c.timeZone(), c.enabled(), c.createdBy(),
                    c.createdAt(), c.lastFiredAt(), c.lastFiredRunId(), c.lastFireStatus(),
                    c.nextFireAt(), c.kind(), c.region(), c.recipients(),
                    c.customSubject(), c.customIntro());
        }
    }

    public record CronJobListResponse(List<CronJobSummary> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CronJobRequest(
            String name,
            String applicationName,
            String templateBlobId,
            String cronExpression,
            String timeZone,
            /** AUTOMATION Phase C/E/D — LAUNCH_RUN (default) | DRAIN_REGION | PROVISION_REGION | INFRA_READINESS | DAILY_REPORT. */
            String kind,
            /** Required for DRAIN_REGION / PROVISION_REGION; ignored otherwise. */
            String region,
            /** AUTOMATION Phase E/D — comma-separated emails for report kinds (INFRA_READINESS / DAILY_REPORT); env fallback if blank. */
            String recipients,
            /** AUTOMATION — optional custom email subject for report kinds (V25); blank → composer default. */
            String customSubject,
            /** AUTOMATION — optional intro/note rendered above the report body for report kinds (V25). */
            String customIntro) {}

    // ── Exceptions + handlers ──────────────────────────────────────────

    static final class CronJobNotFoundException extends RuntimeException {
        CronJobNotFoundException(String id) { super("cron job not found: " + id); }
    }
    static final class CronJobValidationException extends RuntimeException {
        CronJobValidationException(String message) { super(message); }
    }
    static final class UnknownApplicationException extends RuntimeException {
        UnknownApplicationException(String name) {
            super("application not registered: " + name);
        }
    }
    static final class CronJobConflictException extends RuntimeException {
        CronJobConflictException(String application, String name) {
            super(application == null
                    ? "a platform schedule named '" + name + "' already exists"
                    : "a schedule named '" + name + "' already exists for application '" + application + "'");
        }
    }
    static final class RegionNotConfiguredException extends RuntimeException {
        RegionNotConfiguredException(String application, String region) {
            super("region '" + region + "' is not configured for application '" + application
                    + "' — add a capacity row first");
        }
    }
    static final class NothingToSkipException extends RuntimeException {
        NothingToSkipException(String name) {
            super("schedule '" + name + "' has no upcoming fire to skip (it is disabled)");
        }
    }

    @ExceptionHandler(CronJobNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(CronJobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "CRON_JOB_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(CronJobValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(CronJobValidationException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidCronException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCron(InvalidCronException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_CRON", "message", e.getMessage()));
    }

    @ExceptionHandler(UnknownApplicationException.class)
    public ResponseEntity<Map<String, String>> handleUnknownApp(UnknownApplicationException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "UNKNOWN_APPLICATION", "message", e.getMessage()));
    }

    @ExceptionHandler(TemplateUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleTemplate(TemplateUnavailableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "TEMPLATE_UNAVAILABLE", "message", e.getMessage()));
    }

    @ExceptionHandler(CronJobConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(CronJobConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CRON_JOB_CONFLICT", "message", e.getMessage()));
    }

    @ExceptionHandler(RegionNotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleRegionNotConfigured(RegionNotConfiguredException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "REGION_NOT_CONFIGURED", "message", e.getMessage()));
    }

    @ExceptionHandler(NothingToSkipException.class)
    public ResponseEntity<Map<String, String>> handleNothingToSkip(NothingToSkipException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "NOTHING_TO_SKIP", "message", e.getMessage()));
    }
}
