package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.service.ApplicationPurgeService;
import com.perf.globalorchestrator.service.ApplicationPurgeService.AppPurgeResult;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.observability.MdcEnrichmentFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * D-AppRegistry + D-Capacity v2 — REST surface for the registered-applications
 * registry and the per-region capacity matrix.
 *
 * <p>{@code metricsGroupId} must name an existing application group; it is
 * what the app's workers send as {@code ?groupId=} on metrics POSTs.
 * {@code metricsApplication} (the group classifier's value for this app's
 * labels) defaults to the upper-cased name when a group is set. Both are
 * replaced wholesale on PUT, like {@code sealId}.
 *
 * <p>Capacity in v2 is mandatory and per-region. {@code POST /applications}
 * accepts an optional {@code capacity[]} array; {@code PUT /applications/{id}}
 * replaces the whole capacity grid wholesale. Reads always include
 * {@code capacity[]} so the UI can render the matrix without a second
 * round-trip.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code name} required, 1-64 chars, DNS-friendly
 *       ({@code [a-z0-9]([-a-z0-9_]*[a-z0-9])?}).</li>
 *   <li>{@code sealId} optional, ≤ 128 chars.</li>
 *   <li>{@code description} optional, ≤ 512 chars.</li>
 *   <li>{@code healthEndpoints} optional, max 8 URLs, each http(s) ≤ 256 chars.</li>
 *   <li>{@code capacity[]}: each entry's {@code maxAvailable} must be 1..1000;
 *       {@code region} matches DNS-friendly chars; no duplicate regions.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9]([-a-z0-9_]{0,62}[a-z0-9])?$");
    private static final java.util.regex.Pattern REGION_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9]([-a-z0-9]{0,62}[a-z0-9])?$");
    private static final int MAX_HEALTH_ENDPOINTS = 8;
    private static final int MAX_HEALTH_ENDPOINT_LEN = 256;
    private static final int MAX_SEAL_ID_LEN = 128;
    private static final int MAX_DESCRIPTION_LEN = 512;
    private static final int MAX_POD_BUDGET = 1000;
    /** LABEL.APPLICATION is VARCHAR2(64) in the metrics schema; the classifier emits values like CPS-PCI. */
    private static final int MAX_METRICS_APPLICATION_LEN = 64;
    private static final java.util.regex.Pattern METRICS_APPLICATION_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /**
     * Canonical region set — the four AWS USA regions this platform deploys
     * to. The UI's region picker (Capacity tab) offers exactly these; the
     * backend keeps {@code capacity.region} free-form (so dummy/local data
     * and future regions still round-trip), but new apps seed from here.
     *
     * <ul>
     *   <li>{@code us-east-1} — N. Virginia</li>
     *   <li>{@code us-east-2} — Ohio</li>
     *   <li>{@code us-west-1} — N. California</li>
     *   <li>{@code us-west-2} — Oregon</li>
     * </ul>
     */
    static final List<String> USA_REGIONS =
            List.of("us-east-1", "us-east-2", "us-west-1", "us-west-2");

    /**
     * Fallback starter region for a deployment that declares no region
     * vocabulary of its own: a single primary region seeded at 0
     * ({@code us-east-1}); operators add the other USA regions (up to 4) via
     * the Capacity tab's region picker. Capacity ceilings still go through
     * {@code PUT /capacity/{region}} — seeding at 0 means "region exists, no
     * workers yet."
     *
     * <p>Superseded per-deployment by {@code REGIONS} — see
     * {@link #seedRegions()}.
     */
    private static final List<String> DEFAULT_SEEDED_REGIONS = List.of("us-east-1");

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

    private final ApplicationRepository repo;
    private final ApplicationGroupRepository groupRepo;
    private final RunRepository runRepo;
    private final ApplicationPurgeService purgeService;
    private final ProvisioningProperties provisioning;

    public ApplicationController(ApplicationRepository repo,
                                 ApplicationGroupRepository groupRepo,
                                 RunRepository runRepo,
                                 ApplicationPurgeService purgeService,
                                 ProvisioningProperties provisioning) {
        this.repo = repo;
        this.groupRepo = groupRepo;
        this.runRepo = runRepo;
        this.purgeService = purgeService;
        this.provisioning = provisioning;
    }

    /**
     * The regions a newly-registered group's pool starts with, seeded at 0
     * (used by {@link ApplicationGroupController#create}).
     *
     * <p>A deployment that declares its own region vocabulary
     * ({@code REGIONS} — the operator's data centers in static mode) seeds
     * exactly those, so the group opens showing the places workers can
     * actually be declared into. Otherwise the historical single primary
     * region stands.
     */
    static List<String> seedRegions(ProvisioningProperties provisioning) {
        List<String> declared = provisioning.regions();
        return declared.isEmpty() ? DEFAULT_SEEDED_REGIONS : declared;
    }

    @GetMapping
    public ResponseEntity<List<Application>> list(
            @org.springframework.web.bind.annotation.RequestParam(
                    name = "hidden", required = false, defaultValue = "false") boolean hidden) {
        // hidden=true is the "Archived applications" view (HARD-DELETE / purge
        // Phase 3): only soft-deleted apps, so the operator can permanently purge
        // them. Default lists only visible apps.
        return ResponseEntity.ok(hidden ? repo.findHidden() : repo.findAll());
    }

    @GetMapping("/{applicationId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Application> get(@PathVariable String applicationId) {
        Application app = repo.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        return ResponseEntity.ok(app);
    }

    @PostMapping
    public ResponseEntity<Application> create(@RequestBody CreateApplicationRequest req) {
        // Capacity and the recycle policy are the group's (GROUP-CAPACITY):
        // an application carries neither, and it must belong to a group.
        validate(req.name(), req.sealId(), req.description(), req.healthEndpoints());
        String metricsGroupId = resolveMetricsGroup(req.metricsGroupId());
        String metricsApplication = resolveMetricsApplication(
                metricsGroupId, req.metricsApplication(), req.name().trim());
        Application app = new Application(
                Ulid.generate(),
                req.name().trim(),
                trimOrNull(req.sealId()),
                trimOrNull(req.description()),
                req.healthEndpoints() == null ? List.of() : List.copyOf(req.healthEndpoints()),
                Instant.now(),
                null, null, null,
                metricsGroupId, metricsApplication);
        Application stored;
        try {
            stored = repo.insert(app);
        } catch (DuplicateKeyException e) {
            throw new ApplicationConflictException(req.name());
        }
        // Registration is a pure DB operation — workers POST metrics straight
        // to the metrics-consumer, and the pool is the group's.
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    @PutMapping("/{applicationId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Application> update(@PathVariable String applicationId,
                                              @RequestBody UpdateApplicationRequest req) {
        repo.findById(applicationId).orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        // App settings updates metadata only; the pool (capacity, policy) is the group's.
        validate(req.name(), req.sealId(), req.description(), req.healthEndpoints());
        String metricsGroupId = resolveMetricsGroup(req.metricsGroupId());
        String metricsApplication = resolveMetricsApplication(
                metricsGroupId, req.metricsApplication(), req.name().trim());
        try {
            Application updated = repo.update(
                    applicationId,
                    req.name().trim(),
                    trimOrNull(req.sealId()),
                    trimOrNull(req.description()),
                    req.healthEndpoints() == null ? List.of() : req.healthEndpoints(),
                    metricsGroupId,
                    metricsApplication);
            return ResponseEntity.ok(updated);
        } catch (DuplicateKeyException e) {
            throw new ApplicationConflictException(req.name());
        }
    }

    @DeleteMapping("/{applicationId:" + Ulid.PATTERN + "}")
    @Transactional("transactionManager")
    public ResponseEntity<Void> delete(@PathVariable String applicationId) {
        // Soft delete ("hide") — the registry row, this app's run history,
        // metrics, audit events, and uploaded blobs are all RETAINED (a future
        // purge job reclaims them). The pool is the group's and is untouched.
        //
        // The original name is FREED: we rename the hidden row to an archived
        // name (original + "__deleted__" + id) and re-tag its runs to match,
        // so the operator can register a fresh application with the original
        // name and that new app won't inherit this one's history. The rename
        // + run re-tag share one transaction so the hidden app always owns its
        // runs under the archived name.
        //
        // Idempotent — DELETE on an unknown OR already-hidden app returns 204.
        Application app = repo.findById(applicationId).orElse(null);
        if (app == null) {
            return ResponseEntity.noContent().build(); // never existed
        }
        // Guard: an app with live runs can't be hidden — those runs are
        // reached through the (about-to-disappear) application page, so hiding
        // would orphan them. Operator aborts or lets them finish first.
        int activeRuns = runRepo.countActiveByApplication(app.name());
        if (activeRuns > 0) {
            throw new ApplicationHasActiveRunsException(app.name(), activeRuns);
        }
        String archivedName = archivedName(app);
        boolean hidden = repo.softDelete(applicationId, archivedName);
        if (hidden) {
            // Re-tag this app's (terminal) runs to the archived name in the
            // same transaction.
            runRepo.reassignApplication(app.name(), archivedName);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * HARD-DELETE / purge Phase 2 — the irreversible second tier. PERMANENTLY
     * removes a HIDDEN application and its entire footprint: every one of its runs
     * (blobs + metrics + run-state), its pod registry rows, its health-transition
     * log, and the application row itself (capacity cascades). A single
     * {@code purgeAudit} tombstone records the sweep.
     *
     * <p>Precondition: the app must already be hidden (via {@code DELETE
     * /applications/{id}}) — "trash, then empty trash." 404 {@code
     * APPLICATION_NOT_FOUND} for an unknown id; 409 {@code APPLICATION_NOT_PURGEABLE}
     * when the app exists but hasn't been hidden first. {@code X-Actor} attributes
     * the action; the optional {@code reason} rides onto the tombstone. Returns a
     * summary of what was reclaimed.
     */
    @PostMapping("/{applicationId:" + Ulid.PATTERN + "}/purge")
    public ResponseEntity<Map<String, Object>> purge(
            @PathVariable String applicationId,
            @RequestBody(required = false) DeleteApplicationRequest request,
            @RequestHeader(value = MdcEnrichmentFilter.HEADER_ACTOR, required = false) String actorHeader) {
        String reason = request == null ? null : request.reason();
        AppPurgeResult result = purgeService.purgeApplication(
                applicationId, Actor.fromHeader(actorHeader), reason);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("applicationId",     result.applicationId());
        body.put("runsPurged",        result.runsPurged());
        body.put("metricRowsDeleted", result.metricRowsDeleted());
        body.put("blobsDeleted",      result.blobsDeleted());
        body.put("blobStepComplete",  result.blobStepComplete());
        return ResponseEntity.ok(body);
    }

    /**
     * Archived name for a soft-deleted app — keeps the original name readable
     * for archaeology while guaranteeing uniqueness via the (lowercased) ULID,
     * so the original name is freed and the archived row can't collide with
     * another hidden app. The {@code application.name} column is plain TEXT
     * (the DNS-friendly pattern is only enforced on operator input), so this
     * longer marker form is safe to store.
     */
    private static String archivedName(Application app) {
        return app.name() + "__deleted__" + app.applicationId().toLowerCase();
    }

    // The Phase 2 sponsor-gate stub (POST /capacity/{region}/requestIncrease)
    // was removed by Phase 3 of the capacity rework (2026-05-12). The
    // replacement is PUT /capacity/{region} on the new
    // CapacityController — it sets maxAvailable directly with no approval
    // workflow (per the locked-in design decision). If sponsor approval
    // comes back as a real feature later, it lives there.

    // ── Validation ─────────────────────────────────────────────────

    private static void validate(String name, String sealId, String description,
                                 List<String> healthEndpoints) {
        if (name == null || name.isBlank()) {
            throw new ApplicationValidationException("name is required");
        }
        String trimmed = name.trim();
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ApplicationValidationException(
                    "name must match [a-z0-9]([-a-z0-9_]{0,62}[a-z0-9])? — DNS-friendly, max 64 chars");
        }
        if (sealId != null && sealId.length() > MAX_SEAL_ID_LEN) {
            throw new ApplicationValidationException("sealId > " + MAX_SEAL_ID_LEN + " chars");
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LEN) {
            throw new ApplicationValidationException("description > " + MAX_DESCRIPTION_LEN + " chars");
        }
        if (healthEndpoints != null) {
            if (healthEndpoints.size() > MAX_HEALTH_ENDPOINTS) {
                throw new ApplicationValidationException(
                        "healthEndpoints > " + MAX_HEALTH_ENDPOINTS + " entries");
            }
            for (String url : healthEndpoints) {
                if (url == null || url.isBlank()) {
                    throw new ApplicationValidationException("healthEndpoints contains a blank URL");
                }
                if (url.length() > MAX_HEALTH_ENDPOINT_LEN) {
                    throw new ApplicationValidationException(
                            "healthEndpoint > " + MAX_HEALTH_ENDPOINT_LEN + " chars: " + url);
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw new ApplicationValidationException(
                            "healthEndpoint must start with http:// or https://: " + url);
                }
                try { new URI(url); } catch (URISyntaxException e) {
                    throw new ApplicationValidationException(
                            "healthEndpoint is not a valid URI: " + url);
                }
            }
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ── Metrics group + classifier value ──────────────────────────

    /** Blank → ungrouped; otherwise the group must exist (400 — the operator picks from the registry). */
    /** Required: an application always belongs to a group (its pool and its metrics tables). */
    private String resolveMetricsGroup(String raw) {
        String groupId = trimOrNull(raw);
        if (groupId == null) {
            throw new ApplicationValidationException("metricsGroupId is required — every application belongs to a group");
        }
        if (groupRepo.findById(groupId).isEmpty()) {
            throw new ApplicationValidationException("unknown application group: " + groupId);
        }
        return groupId;
    }

    /** With a group, a blank value defaults to the upper-cased name; a supplied value is validated. */
    private static String resolveMetricsApplication(String metricsGroupId, String raw, String name) {
        String value = trimOrNull(raw);
        if (value == null) {
            return metricsGroupId == null ? null : name.toUpperCase(Locale.ROOT);
        }
        if (value.length() > MAX_METRICS_APPLICATION_LEN
                || !METRICS_APPLICATION_PATTERN.matcher(value).matches()) {
            throw new ApplicationValidationException(
                    "metricsApplication must match [A-Za-z0-9][A-Za-z0-9._-]{0,63} — got " + value);
        }
        return value;
    }

    // ── Request bodies ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateApplicationRequest(
            String name, String sealId, String description, List<String> healthEndpoints,
            /** An existing application group's id — required: the pool and the metrics tables are the group's. */
            String metricsGroupId,
            /** The group classifier's value for this app's labels; null = upper-cased name. */
            String metricsApplication) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateApplicationRequest(
            String name, String sealId, String description, List<String> healthEndpoints,
            /** Replaced wholesale — required; an application always belongs to a group. */
            String metricsGroupId,
            /** Replaced wholesale: null = upper-cased name. */
            String metricsApplication) {}

    // ── Exceptions + handlers ──────────────────────────────────────

    static final class ApplicationNotFoundException extends RuntimeException {
        ApplicationNotFoundException(String id) { super("application not found: " + id); }
    }
    static final class ApplicationValidationException extends RuntimeException {
        ApplicationValidationException(String message) { super(message); }
    }
    static final class ApplicationConflictException extends RuntimeException {
        ApplicationConflictException(String name) {
            super("application name already exists: " + name);
        }
    }
    static final class ApplicationHasActiveRunsException extends RuntimeException {
        ApplicationHasActiveRunsException(String name, int activeRuns) {
            super("application '" + name + "' has " + activeRuns
                    + " active run" + (activeRuns == 1 ? "" : "s")
                    + " — abort or let them finish before hiding it");
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ApplicationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ApplicationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_NOT_FOUND", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ApplicationValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(ApplicationValidationException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ApplicationConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ApplicationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_NAME_TAKEN", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ApplicationHasActiveRunsException.class)
    public ResponseEntity<Map<String, String>> handleHasActiveRuns(ApplicationHasActiveRunsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_HAS_ACTIVE_RUNS", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            ApplicationPurgeService.UnknownApplicationException.class)
    public ResponseEntity<Map<String, String>> handlePurgeUnknown(
            ApplicationPurgeService.UnknownApplicationException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_NOT_FOUND", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            ApplicationPurgeService.ApplicationNotPurgeableException.class)
    public ResponseEntity<Map<String, String>> handleNotPurgeable(
            ApplicationPurgeService.ApplicationNotPurgeableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_NOT_PURGEABLE",
                        "message", e.getMessage(), "applicationId", e.applicationId()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> handleEmpty(EmptyResultDataAccessException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_NOT_FOUND", "message", e.getMessage()));
    }

}
