package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationCapacity;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.repo.ApplicationCapacityRepository;
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
import org.springframework.cache.annotation.CacheEvict;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D-AppRegistry + D-Capacity v2 — REST surface for the registered-applications
 * registry and the per-region capacity matrix.
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
     * <p>Superseded per-deployment by {@code PROVISIONING_REGIONS} — see
     * {@link #seedRegions()}.
     */
    private static final List<String> DEFAULT_SEEDED_REGIONS = List.of("us-east-1");

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

    private final ApplicationRepository repo;
    private final ApplicationCapacityRepository capacityRepo;
    private final RunRepository runRepo;
    private final ApplicationPurgeService purgeService;
    private final ProvisioningProperties provisioning;

    public ApplicationController(ApplicationRepository repo,
                                 ApplicationCapacityRepository capacityRepo,
                                 RunRepository runRepo,
                                 ApplicationPurgeService purgeService,
                                 ProvisioningProperties provisioning) {
        this.repo = repo;
        this.capacityRepo = capacityRepo;
        this.runRepo = runRepo;
        this.purgeService = purgeService;
        this.provisioning = provisioning;
    }

    /**
     * The regions a newly-registered application starts with, seeded at 0.
     *
     * <p>A deployment that declares its own region vocabulary
     * ({@code PROVISIONING_REGIONS} — the operator's data centers in static
     * mode) seeds exactly those, so the app opens showing the places workers
     * can actually be declared into. Otherwise the historical single primary
     * region stands.
     *
     * <p>Registration goes through this controller rather than
     * {@code CapacityController}, so without this the seed was always
     * {@code us-east-1} — which in a private cloud that has never heard of
     * AWS regions renders as an empty data center that does not exist.
     */
    private List<String> seedRegions() {
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
        List<Application> apps = hidden ? repo.findHidden() : repo.findAll();
        Map<String, List<ApplicationCapacity>> capacityByApp = capacityRepo.findAllGroupedByApp();
        List<Application> hydrated = new ArrayList<>(apps.size());
        for (Application a : apps) {
            hydrated.add(withCapacity(a, capacityByApp.getOrDefault(a.applicationId(), List.of())));
        }
        return ResponseEntity.ok(hydrated);
    }

    @GetMapping("/{applicationId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Application> get(@PathVariable String applicationId) {
        Application app = repo.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        return ResponseEntity.ok(withCapacity(app, capacityRepo.findByApplicationId(applicationId)));
    }

    @PostMapping
    public ResponseEntity<Application> create(@RequestBody CreateApplicationRequest req) {
        // D-Capacity v2 polish — operator-supplied capacity is IGNORED.
        // Capacity is sponsor-controlled; new apps always land at 0 for
        // the default seeded regions. The "Request more capacity"
        // workflow is the only path to a non-zero ceiling.
        validate(req.name(), req.sealId(), req.description(), req.healthEndpoints(),
                /* capacity */ null);
        // RecyclePolicy default is REUSE (zero
        // behavior change for new apps); operators opt-in to recycle by
        // setting it on POST or via a subsequent PUT.
        RecyclePolicy policy = resolveRecyclePolicy(req.recyclePolicy());
        validateRecyclePolicy(policy, req.maxRunsPerPod(), req.podMaxAgeHours());
        Application app = new Application(
                Ulid.generate(),
                req.name().trim(),
                trimOrNull(req.sealId()),
                trimOrNull(req.description()),
                req.healthEndpoints() == null ? List.of() : List.copyOf(req.healthEndpoints()),
                null,
                Instant.now(),
                null, null, null,
                policy, req.maxRunsPerPod(), req.podMaxAgeHours(),
                /* alwaysOn — AUTOMATION Phase C; null defaults to false. */
                Boolean.TRUE.equals(req.alwaysOn()));
        Application stored;
        try {
            stored = repo.insert(app);
        } catch (DuplicateKeyException e) {
            throw new ApplicationConflictException(req.name());
        }
        // Auto-seed capacity rows at 0 for this deployment's starter regions.
        List<ApplicationCapacity> seeded = seedRegions().stream()
                .map(r -> new ApplicationCapacity(stored.applicationId(), r, 0, null, null))
                .toList();
        capacityRepo.replaceAll(stored.applicationId(), seeded);
        // Registration is a pure DB operation —
        // workers POST metrics straight to the metrics-consumer, so app
        // registration is a pure DB operation.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(withCapacity(stored, capacityRepo.findByApplicationId(stored.applicationId())));
    }

    @PutMapping("/{applicationId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Application> update(@PathVariable String applicationId,
                                              @RequestBody UpdateApplicationRequest req) {
        repo.findById(applicationId).orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        // D-Capacity v2 polish — capacity in the body is IGNORED on PUT
        // for the same reason as POST: operator can never directly set
        // it. App settings updates metadata only; capacity changes go
        // through the "Request more capacity" sponsor workflow.
        validate(req.name(), req.sealId(), req.description(), req.healthEndpoints(), /* capacity */ null);
        // PUT body's recyclePolicy may be null
        // (operator updated other metadata only). Repo treats null as
        // "no change"; thresholds are only validated when policy is
        // supplied.
        RecyclePolicy policy = req.recyclePolicy() == null ? null : resolveRecyclePolicy(req.recyclePolicy());
        if (policy != null) {
            validateRecyclePolicy(policy, req.maxRunsPerPod(), req.podMaxAgeHours());
        } else if (req.maxRunsPerPod() != null || req.podMaxAgeHours() != null) {
            throw new ApplicationValidationException(
                    "maxRunsPerPod / podMaxAgeHours can only be set together with recyclePolicy");
        }
        // alwaysOn — AUTOMATION Phase C. PUT body omitting it preserves the
        // current value (no surprise flips for callers that don't know about it).
        Application existing = repo.findById(applicationId).orElseThrow(
                () -> new ApplicationNotFoundException(applicationId));
        boolean alwaysOn = req.alwaysOn() == null ? existing.alwaysOn() : req.alwaysOn();
        try {
            Application updated = repo.update(
                    applicationId,
                    req.name().trim(),
                    trimOrNull(req.sealId()),
                    trimOrNull(req.description()),
                    req.healthEndpoints() == null ? List.of() : req.healthEndpoints(),
                    policy,
                    req.maxRunsPerPod(),
                    req.podMaxAgeHours(),
                    alwaysOn);
            return ResponseEntity.ok(withCapacity(updated, capacityRepo.findByApplicationId(applicationId)));
        } catch (DuplicateKeyException e) {
            throw new ApplicationConflictException(req.name());
        }
    }

    @DeleteMapping("/{applicationId:" + Ulid.PATTERN + "}")
    @Transactional("transactionManager")
    // Soft-delete RETAINS the capacity rows (no ON DELETE CASCADE fires), so
    // nothing in ApplicationCapacityRepository evicts the cached entry for the
    // now-retired app. Evict the whole capacity cache here so a hidden app's
    // stale capacity (per-app key AND the 'all' grouped key) can't linger.
    @CacheEvict(cacheNames = CacheConfig.CACHE_APPLICATION_CAPACITY, allEntries = true)
    public ResponseEntity<Void> delete(@PathVariable String applicationId) {
        // Soft delete ("hide") — the registry row, its capacity rows, this
        // app's run history, metrics, audit events, and uploaded blobs are all
        // RETAINED (a future purge job reclaims them).
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
                                 List<String> healthEndpoints, List<CapacityEntry> capacity) {
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
        if (capacity != null) {
            java.util.Set<String> seenRegions = new java.util.HashSet<>();
            for (CapacityEntry c : capacity) {
                if (c.region() == null || !REGION_PATTERN.matcher(c.region()).matches()) {
                    throw new ApplicationValidationException(
                            "capacity.region must be DNS-friendly: " + c.region());
                }
                if (!seenRegions.add(c.region())) {
                    throw new ApplicationValidationException(
                            "duplicate region in capacity: " + c.region());
                }
                if (c.maxAvailable() < 0 || c.maxAvailable() > MAX_POD_BUDGET) {
                    throw new ApplicationValidationException(
                            "capacity.maxAvailable must be 0.." + MAX_POD_BUDGET
                                    + " for region " + c.region() + "; got " + c.maxAvailable());
                }
            }
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ── WORKER-HYGIENE Phase C — recycle policy validation ───────────

    private static final int MAX_RUNS_PER_POD_CEILING = 10_000;
    private static final int POD_MAX_AGE_HOURS_CEILING = 720; // 30 days

    /** Returns REUSE when the input is null; otherwise parses the enum (400 on unknown). */
    private static RecyclePolicy resolveRecyclePolicy(String raw) {
        if (raw == null || raw.isBlank()) return RecyclePolicy.REUSE;
        try {
            return RecyclePolicy.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApplicationValidationException(
                    "recyclePolicy must be one of REUSE, MAX_RUNS, MAX_AGE, BOTH, EVERY_RUN, DRAIN_AFTER_RUN — got " + raw);
        }
    }

    /** Cross-field invariants per {@link RecyclePolicy}'s table. */
    private static void validateRecyclePolicy(RecyclePolicy policy,
                                              Integer maxRunsPerPod,
                                              Integer podMaxAgeHours) {
        if (maxRunsPerPod != null && (maxRunsPerPod < 1 || maxRunsPerPod > MAX_RUNS_PER_POD_CEILING)) {
            throw new ApplicationValidationException(
                    "maxRunsPerPod must be 1.." + MAX_RUNS_PER_POD_CEILING + "; got " + maxRunsPerPod);
        }
        if (podMaxAgeHours != null && (podMaxAgeHours < 1 || podMaxAgeHours > POD_MAX_AGE_HOURS_CEILING)) {
            throw new ApplicationValidationException(
                    "podMaxAgeHours must be 1.." + POD_MAX_AGE_HOURS_CEILING + "; got " + podMaxAgeHours);
        }
        switch (policy) {
            case REUSE, EVERY_RUN, DRAIN_AFTER_RUN -> {
                if (maxRunsPerPod != null || podMaxAgeHours != null) {
                    throw new ApplicationValidationException(
                            "policy=" + policy + " forbids maxRunsPerPod / podMaxAgeHours");
                }
            }
            case MAX_RUNS -> {
                if (maxRunsPerPod == null) {
                    throw new ApplicationValidationException(
                            "policy=MAX_RUNS requires maxRunsPerPod");
                }
                if (podMaxAgeHours != null) {
                    throw new ApplicationValidationException(
                            "policy=MAX_RUNS forbids podMaxAgeHours");
                }
            }
            case MAX_AGE -> {
                if (podMaxAgeHours == null) {
                    throw new ApplicationValidationException(
                            "policy=MAX_AGE requires podMaxAgeHours");
                }
                if (maxRunsPerPod != null) {
                    throw new ApplicationValidationException(
                            "policy=MAX_AGE forbids maxRunsPerPod");
                }
            }
            case BOTH -> {
                if (maxRunsPerPod == null || podMaxAgeHours == null) {
                    throw new ApplicationValidationException(
                            "policy=BOTH requires both maxRunsPerPod and podMaxAgeHours");
                }
            }
        }
    }

    private static Application withCapacity(Application a, List<ApplicationCapacity> capacity) {
        return new Application(
                a.applicationId(), a.name(), a.sealId(), a.description(),
                a.healthEndpoints(), capacity, a.createdAt(),
                a.lastHealthCheckedAt(), a.lastHealthStatus(), a.lastHealthDetails(),
                a.recyclePolicy(), a.maxRunsPerPod(), a.podMaxAgeHours(),
                a.alwaysOn());
    }

    // ── Request bodies ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapacityEntry(String region, int maxAvailable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateApplicationRequest(
            String name, String sealId, String description, List<String> healthEndpoints,
            /** D-Capacity v2 — per-region grid; empty/null = configure later. */
            List<CapacityEntry> capacity,
            /** WORKER-HYGIENE Phase C — null defaults to REUSE. */
            String recyclePolicy,
            Integer maxRunsPerPod,
            Integer podMaxAgeHours,
            /** AUTOMATION Phase C — null defaults to false (DRAIN_REGION jobs fire normally). */
            Boolean alwaysOn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateApplicationRequest(
            String name, String sealId, String description, List<String> healthEndpoints,
            List<CapacityEntry> capacity,
            /** WORKER-HYGIENE Phase C — null = no policy change. */
            String recyclePolicy,
            Integer maxRunsPerPod,
            Integer podMaxAgeHours,
            /** AUTOMATION Phase C — null = preserve current. */
            Boolean alwaysOn) {}

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
