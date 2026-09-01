package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobFire;
import com.perf.globalorchestrator.domain.CronJobKind;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.observability.MdcEnrichmentFilter;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.repo.CronJobFireHistoryRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
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
 * REST surface for CRON schedules. The contract (paths, field names,
 * {@code {items:[...]}} list shape) matches
 * {@code jmeter-cloud-ui/src/api/automation.ts} field for field.
 *
 * <p>Conventions mirror {@link ApplicationController}: inner exception classes
 * + {@code @ExceptionHandler}s returning {@code {code,message}}, the
 * {@code X-Actor} header read via {@link Actor#fromHeader}, and validation that
 * fails fast with a 400.
 *
 * <p><b>A schedule names an application group, never an application</b>
 * (AUTOMATION-3, 2026-08-31). What else it carries is decided by its
 * {@link CronJobKind}, and {@code ORCH_CRON_JOB_KIND_FIELDS_CHK} is the
 * database-side backstop for the validation here: a workflow must belong to the
 * group that schedules it, and a cluster must be one the group has reserved
 * capacity on — so a fire can never quietly act on something the operator did
 * not name.
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
    private final ApplicationGroupRepository groups;
    private final WorkflowRepository workflows;
    private final GroupCapacityRepository capacities;
    private final CronFireService fireService;

    public CronJobController(CronJobRepository cronJobs,
                             CronJobFireHistoryRepository fireHistory,
                             ApplicationGroupRepository groups,
                             WorkflowRepository workflows,
                             GroupCapacityRepository capacities,
                             CronFireService fireService) {
        this.cronJobs = cronJobs;
        this.fireHistory = fireHistory;
        this.groups = groups;
        this.workflows = workflows;
        this.capacities = capacities;
        this.fireService = fireService;
    }

    // ── Reads ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<CronJobListResponse> list(
            @RequestParam(value = "groupId", required = false) String groupId) {
        // Workflow names are hydrated here so the Automation page renders every
        // row from ONE request; a name that no longer resolves stays null and
        // the UI says the workflow was deleted.
        Map<String, String> workflowNames = workflows.namesById();
        List<CronJobSummary> items = cronJobs.findAll(groupId).stream()
                .map(job -> CronJobSummary.from(job, workflowNames.get(job.workflowId())))
                .toList();
        return ResponseEntity.ok(new CronJobListResponse(items));
    }

    @GetMapping("/{cronJobId:" + Ulid.PATTERN + "}")
    public ResponseEntity<CronJobSummary> get(@PathVariable String cronJobId) {
        return ResponseEntity.ok(hydrate(require(cronJobId)));
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
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        String name = validateName(req.name());
        String timeZone = normaliseTimeZone(req.timeZone());
        CronSchedule.validate(req.cronExpression(), timeZone);
        CronJobKind kind = resolveKind(req.kind());
        Resolved r = resolveForKind(kind, req);

        Instant now = Instant.now();
        CronJob job = new CronJob(
                Ulid.generate(), name, r.groupId(), r.workflowId(),
                req.cronExpression().trim(), timeZone, /* enabled */ true,
                Actor.fromHeader(actorHeader).name(), now,
                /* lastFiredAt */ null, /* lastFiredRunId */ null, /* lastFireStatus */ null,
                CronSchedule.nextFireAfter(req.cronExpression(), timeZone, now),
                /* claimedAt */ null,
                kind, r.region(), r.recipients(), r.customSubject(), r.customIntro());
        try {
            cronJobs.insert(job);
        } catch (DuplicateKeyException e) {
            throw new CronJobConflictException(r.groupId(), name);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(hydrate(job));
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
            cronJobs.update(cronJobId, name, r.groupId(), r.workflowId(),
                    req.cronExpression().trim(), timeZone, nextFireAt, kind, r.region(), r.recipients(),
                    r.customSubject(), r.customIntro());
        } catch (DuplicateKeyException e) {
            throw new CronJobConflictException(r.groupId(), name);
        }
        return ResponseEntity.ok(hydrate(require(cronJobId)));
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
        return ResponseEntity.ok(hydrate(require(cronJobId)));
    }

    @PostMapping("/{cronJobId:" + Ulid.PATTERN + "}/disable")
    public ResponseEntity<CronJobSummary> disable(@PathVariable String cronJobId) {
        require(cronJobId);
        cronJobs.setEnabled(cronJobId, false, null);
        return ResponseEntity.ok(hydrate(require(cronJobId)));
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
        return ResponseEntity.ok(hydrate(require(cronJobId)));
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
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        CronJob job = require(cronJobId);
        FireResult result = fireService.fire(job, Actor.fromHeader(actorHeader));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("outcome", result.outcome().name());
        body.put("executionId", result.executionId());
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

    /** The group a schedule is scoped to; 400 when absent or unregistered. */
    private ApplicationGroup validateGroupExists(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new CronJobValidationException("groupId is required for kind=" + "LAUNCH_WORKFLOW/SCALE_OUT/SCALE_IN");
        }
        String trimmed = groupId.trim();
        return groups.findById(trimmed)
                .orElseThrow(() -> new UnknownGroupException(trimmed));
    }

    private static String normaliseTimeZone(String tz) {
        return tz == null || tz.isBlank() ? "UTC" : tz.trim();
    }

    /** Parse the kind. There is no default: every caller states what it wants. */
    private static CronJobKind resolveKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CronJobValidationException("kind is required");
        }
        try {
            return CronJobKind.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new CronJobValidationException(
                    "kind must be one of LAUNCH_WORKFLOW, SCALE_OUT, SCALE_IN, "
                            + "INFRA_READINESS, DAILY_REPORT — got " + raw);
        }
    }

    /** The kind-dependent fields, after validation. */
    private record Resolved(String groupId, String workflowId, String region,
                            String recipients, String customSubject, String customIntro) {}

    /**
     * Validate + resolve the kind-dependent fields:
     * <ul>
     *   <li>report kinds — platform-wide: no group, workflow or region;
     *       recipients optional (env fallback at fire time).</li>
     *   <li>LAUNCH_WORKFLOW — the group must exist and the workflow must belong
     *       to <i>that</i> group.</li>
     *   <li>SCALE_OUT / SCALE_IN — the group must exist and hold a reservation
     *       on the named cluster.</li>
     * </ul>
     */
    private Resolved resolveForKind(CronJobKind kind, CronJobRequest req) {
        if (kind.isReport()) {
            return new Resolved(null, null, null, trimToNull(req.recipients()),
                    trimToNull(req.customSubject()), trimToNull(req.customIntro()));
        }
        ApplicationGroup group = validateGroupExists(req.groupId());
        if (kind.isScaling()) {
            String region = trimToNull(req.region());
            if (region == null) {
                throw new CronJobValidationException("region is required for kind=" + kind);
            }
            // A group can only scale where it holds a reservation; without this
            // the fire would SKIP every window with "no capacity configured"
            // and the operator would never learn why from the create call.
            if (capacities.find(group.groupId(), region).isEmpty()) {
                throw new RegionNotConfiguredException(group.groupId(), region);
            }
            return new Resolved(group.groupId(), null, region, null, null, null);
        }
        // LAUNCH_WORKFLOW
        String workflowId = trimToNull(req.workflowId());
        if (workflowId == null) {
            throw new CronJobValidationException("workflowId is required for kind=LAUNCH_WORKFLOW");
        }
        Workflow workflow = workflows.findById(workflowId)
                .orElseThrow(() -> new UnknownWorkflowException(workflowId));
        // Cross-group scheduling would let one team's cadence spend another
        // team's reservation.
        if (!workflow.groupId().equals(group.groupId())) {
            throw new CronJobValidationException(
                    "workflow '" + workflow.name() + "' belongs to group '" + workflow.groupId()
                            + "' but the schedule names '" + group.groupId() + "'");
        }
        return new Resolved(group.groupId(), workflow.workflowId(), null, null, null, null);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** One schedule with its workflow name filled in. */
    private CronJobSummary hydrate(CronJob job) {
        String workflowName = job.workflowId() == null ? null
                : workflows.findById(job.workflowId()).map(Workflow::name).orElse(null);
        return CronJobSummary.from(job, workflowName);
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    /** Wire response — mirrors {@code CronJobSummary} in automation.ts exactly
     *  (no internal {@code claimedAt}). {@code workflowName} is hydrated, not
     *  stored, so the Automation page renders a row without a second call; it
     *  is null when the workflow has been deleted. */
    public record CronJobSummary(
            String cronJobId,
            String name,
            CronJobKind kind,
            String groupId,
            String workflowId,
            String workflowName,
            String region,
            String cronExpression,
            String timeZone,
            boolean enabled,
            String createdBy,
            Instant createdAt,
            Instant lastFiredAt,
            String lastFiredExecutionId,
            String lastFireStatus,
            Instant nextFireAt,
            String recipients,
            String customSubject,
            String customIntro) {

        static CronJobSummary from(CronJob c, String workflowName) {
            return new CronJobSummary(
                    c.cronJobId(), c.name(), c.kind(), c.groupId(), c.workflowId(), workflowName,
                    c.region(), c.cronExpression(), c.timeZone(), c.enabled(), c.createdBy(),
                    c.createdAt(), c.lastFiredAt(), c.lastFiredExecutionId(), c.lastFireStatus(),
                    c.nextFireAt(), c.recipients(), c.customSubject(), c.customIntro());
        }
    }

    public record CronJobListResponse(List<CronJobSummary> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CronJobRequest(
            String name,
            /** LAUNCH_WORKFLOW | SCALE_OUT | SCALE_IN | INFRA_READINESS | DAILY_REPORT. Required. */
            String kind,
            /** The owning application group. Required for every kind but the reports. */
            String groupId,
            /** Required for kind=LAUNCH_WORKFLOW; must belong to {@code groupId}. */
            String workflowId,
            String cronExpression,
            String timeZone,
            /** Required for kind=SCALE_OUT / SCALE_IN; ignored otherwise. */
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
    static final class UnknownGroupException extends RuntimeException {
        UnknownGroupException(String groupId) {
            super("application group not registered: " + groupId);
        }
    }
    static final class UnknownWorkflowException extends RuntimeException {
        UnknownWorkflowException(String workflowId) {
            super("workflow not found: " + workflowId);
        }
    }
    static final class CronJobConflictException extends RuntimeException {
        CronJobConflictException(String groupId, String name) {
            super(groupId == null
                    ? "a platform schedule named '" + name + "' already exists"
                    : "a schedule named '" + name + "' already exists in group '" + groupId + "'");
        }
    }
    static final class RegionNotConfiguredException extends RuntimeException {
        RegionNotConfiguredException(String groupId, String region) {
            super("group '" + groupId + "' holds no reservation on cluster '" + region
                    + "' — reserve capacity there first");
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

    @ExceptionHandler(UnknownGroupException.class)
    public ResponseEntity<Map<String, String>> handleUnknownGroup(UnknownGroupException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "UNKNOWN_APPLICATION_GROUP", "message", e.getMessage()));
    }

    @ExceptionHandler(UnknownWorkflowException.class)
    public ResponseEntity<Map<String, String>> handleUnknownWorkflow(UnknownWorkflowException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "UNKNOWN_WORKFLOW", "message", e.getMessage()));
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
