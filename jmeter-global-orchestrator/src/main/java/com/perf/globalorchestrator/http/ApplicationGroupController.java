package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.GroupCapacitySummary;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The application-group registry. A group's {@code groupId} is what workers
 * send as {@code ?groupId=} on metrics POSTs and, upper-cased, the prefix of the
 * group's tables in {@code CARDZATE_DB_GRAF} ({@code cps} → {@code CPS_METRICS});
 * it must equal a {@code GROUP_REGISTRY.GROUP_ID} there. The group owns the
 * worker pool (GROUP-CAPACITY, 2026-08-30): its per-region capacity rows are
 * seeded at 0 on create and edited through {@link CapacityController}, and its
 * recycle policy is set here. Groups are hard-deleted; a delete is refused while
 * any application (visible or archived), worker or capacity row is in the group.
 */
@RestController
@RequestMapping("/api/v1/applicationGroups")
public class ApplicationGroupController {

    /**
     * An identifier stem: {@code UPPER(groupId)} prefixes the group's metrics tables
     * ({@code CPS_METRICS}), so letters/digits/underscore, letter first, ≤ 30 chars
     * (the {@code GROUP_REGISTRY.GROUP_ID} width). Lowercase like the hosted registry.
     */
    static final String GROUP_ID_REGEX = "[a-z][a-z0-9_]{0,29}";
    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^" + GROUP_ID_REGEX + "$");
    /** The same regex serves as the path constraint (no capture groups); the capacity routes reuse it. */
    static final String GROUP_ID_PATH = GROUP_ID_REGEX;
    private static final int MAX_NAME_LEN = 255;
    private static final int MAX_DESCRIPTION_LEN = 512;

    private final ApplicationGroupRepository repo;
    private final GroupCapacityRepository capacityRepo;
    private final PodRepository pods;

    public ApplicationGroupController(ApplicationGroupRepository repo, GroupCapacityRepository capacityRepo,
                                      PodRepository pods) {
        this.repo = repo;
        this.capacityRepo = capacityRepo;
        this.pods = pods;
    }

    @GetMapping
    public ResponseEntity<List<ApplicationGroup>> list() {
        Map<String, Integer> counts = repo.applicationCounts();
        Map<String, List<GroupCapacity>> capacity = capacityRepo.findAllGroupedByGroup();
        return ResponseEntity.ok(repo.findAll().stream()
                .map(g -> g.withApplicationCount(counts.getOrDefault(g.groupId(), 0))
                        .withCapacity(capacity.getOrDefault(g.groupId(), List.of())))
                .toList());
    }

    /**
     * Every group's reservation and live pod counts in one response — the
     * Capacity list's whole table.
     *
     * <p>The per-region {@code /capacity/{region}/pods} call asks the region's
     * Kubernetes API for container status, so a list page reading it once per
     * row polled that API {@code groups × regions} times per tick. This answers
     * from {@code ORCH_GROUP_CAPACITY} and {@code ORCH_POD} alone: two queries,
     * no substrate call, one request. It therefore carries no per-pod detail
     * and no {@code containerRunning} — the drill-in page still uses the
     * per-region call, which is where that evidence belongs.
     *
     * <p>A reserved (group, region) with no pods yet is present with zero
     * counts; a pod in a region the group no longer reserves is not, because
     * the reservation grid is what the page lists.
     */
    @GetMapping("/capacitySummary")
    public ResponseEntity<List<GroupCapacitySummary>> capacitySummary() {
        Map<String, PodRepository.GroupRegionPods> podsByKey = new LinkedHashMap<>();
        for (PodRepository.GroupRegionPods p : pods.groupRegionPods()) {
            podsByKey.put(p.groupId() + "\u0000" + p.region(), p);
        }
        List<GroupCapacitySummary> out = new ArrayList<>();
        capacityRepo.findAllGroupedByGroup().forEach((groupId, rows) -> {
            for (GroupCapacity c : rows) {
                PodRepository.GroupRegionPods p = podsByKey.get(groupId + "\u0000" + c.region());
                long provisioned = p == null ? 0L : p.provisioned();
                long inUse = p == null ? 0L : p.inUse();
                out.add(new GroupCapacitySummary(
                        groupId, c.region(), c.maxAvailable(),
                        provisioned,
                        // The per-region snapshot reports ready as
                        // "everything not bound to a run"; match it exactly so
                        // the list and the drill-in never disagree.
                        provisioned - inUse,
                        inUse,
                        p == null ? null : p.lastActivityAt()));
            }
        });
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{groupId:" + GROUP_ID_PATH + "}")
    public ResponseEntity<ApplicationGroup> get(@PathVariable String groupId) {
        ApplicationGroup group = repo.findById(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        return ResponseEntity.ok(hydrate(group));
    }

    @PostMapping
    public ResponseEntity<ApplicationGroup> create(@RequestBody CreateApplicationGroupRequest req) {
        String groupId = validateGroupId(req.groupId());
        String name = validateName(req.name());
        String description = validateDescription(req.description());
        String grafanaLiveUrl = validateUrl("grafanaLiveUrl", req.grafanaLiveUrl());
        String grafanaHistoryUrl = validateUrl("grafanaHistoryUrl", req.grafanaHistoryUrl());
        int hotDays = validateHotDays(req.hotDays());
        RecyclePolicy policy = resolveRecyclePolicy(req.recyclePolicy());
        validateRecyclePolicy(policy, req.maxRunsPerPod(), req.podMaxAgeHours());
        if (repo.findById(groupId).isPresent()) {
            throw new GroupIdTakenException(groupId);
        }
        ApplicationGroup stored;
        try {
            stored = repo.insert(new ApplicationGroup(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl,
                    hotDays, policy, req.maxRunsPerPod(), req.podMaxAgeHours(), Boolean.TRUE.equals(req.alwaysOn()),
                    validateName255("teamName", req.teamName()),
                    validateAddresses("notifyTo", req.notifyTo()),
                    validateAddresses("notifyCc", req.notifyCc()),
                    validateAddresses("notifyBcc", req.notifyBcc()),
                    Instant.now(), null, null));
        } catch (DuplicateKeyException e) {
            throw new GroupNameTakenException(name);
        }
        // The pool starts with NO clusters (CLUSTER-CAPACITY): the group
        // attaches registered clusters and reserves capacity explicitly on
        // the Capacity tab.
        return ResponseEntity.status(HttpStatus.CREATED).body(hydrate(stored.withApplicationCount(0)));
    }

    @PutMapping("/{groupId:" + GROUP_ID_PATH + "}")
    public ResponseEntity<ApplicationGroup> update(@PathVariable String groupId,
                                                   @RequestBody UpdateApplicationGroupRequest req) {
        ApplicationGroup existing = repo.findById(groupId).orElseThrow(() -> new GroupNotFoundException(groupId));
        String name = validateName(req.name());
        String description = validateDescription(req.description());
        String grafanaLiveUrl = validateUrl("grafanaLiveUrl", req.grafanaLiveUrl());
        String grafanaHistoryUrl = validateUrl("grafanaHistoryUrl", req.grafanaHistoryUrl());
        int hotDays = validateHotDays(req.hotDays());
        // The policy is replaced wholesale when sent (null = keep REUSE's
        // shape); alwaysOn omitted preserves the current value — no surprise
        // flips for callers that don't know about it.
        RecyclePolicy policy = resolveRecyclePolicy(req.recyclePolicy());
        validateRecyclePolicy(policy, req.maxRunsPerPod(), req.podMaxAgeHours());
        boolean alwaysOn = req.alwaysOn() == null ? existing.alwaysOn() : req.alwaysOn();
        // Each address list is replaced only when sent, so a caller that
        // predates the field cannot silently wipe a group's recipients.
        List<String> notifyTo  = req.notifyTo()  == null ? existing.notifyTo()
                : validateAddresses("notifyTo", req.notifyTo());
        List<String> notifyCc  = req.notifyCc()  == null ? existing.notifyCc()
                : validateAddresses("notifyCc", req.notifyCc());
        List<String> notifyBcc = req.notifyBcc() == null ? existing.notifyBcc()
                : validateAddresses("notifyBcc", req.notifyBcc());
        String teamName = req.teamName() == null ? existing.teamName()
                : validateName255("teamName", req.teamName());
        try {
            ApplicationGroup updated = repo.update(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays,
                    policy, req.maxRunsPerPod(), req.podMaxAgeHours(), alwaysOn,
                    teamName, notifyTo, notifyCc, notifyBcc);
            return ResponseEntity.ok(hydrate(updated));
        } catch (DuplicateKeyException e) {
            throw new GroupNameTakenException(name);
        }
    }

    /**
     * Idempotent: an unknown group is already gone (204); a group with
     * applications, workers or capacity rows is 409 — drain and release the
     * pool first, then move or purge the applications.
     */
    @DeleteMapping("/{groupId:" + GROUP_ID_PATH + "}")
    public ResponseEntity<Void> delete(@PathVariable String groupId) {
        int inUse = repo.countApplications(groupId);
        if (inUse > 0) {
            throw new GroupHasApplicationsException(groupId, inUse);
        }
        int pods = repo.countPods(groupId);
        int capacity = capacityRepo.countByGroupId(groupId);
        if (pods > 0 || capacity > 0) {
            throw new GroupHasWorkersException(groupId, pods, capacity);
        }
        try {
            repo.delete(groupId);
        } catch (DataIntegrityViolationException e) {
            // An application or worker was assigned between the count and the delete.
            int apps = repo.countApplications(groupId);
            if (apps > 0) {
                throw new GroupHasApplicationsException(groupId, apps);
            }
            throw new GroupHasWorkersException(groupId, repo.countPods(groupId), capacityRepo.countByGroupId(groupId));
        }
        return ResponseEntity.noContent().build();
    }

    private ApplicationGroup hydrate(ApplicationGroup group) {
        return group.withApplicationCount(repo.countVisibleApplications(group.groupId()))
                .withCapacity(capacityRepo.findByGroupId(group.groupId()));
    }

    // ── Recycle policy (moved from the application with the pool) ───

    /** Null → REUSE; otherwise a {@link RecyclePolicy} name (400 on anything else). */
    static RecyclePolicy resolveRecyclePolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return RecyclePolicy.REUSE;
        }
        try {
            return RecyclePolicy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GroupValidationException("unknown recyclePolicy '" + raw + "'; allowed: "
                    + java.util.Arrays.toString(RecyclePolicy.values()));
        }
    }

    /** The thresholds exist exactly when the policy reads them — the schema's CHECK, said first. */
    static void validateRecyclePolicy(RecyclePolicy policy, Integer maxRunsPerPod, Integer podMaxAgeHours) {
        if (maxRunsPerPod != null && (maxRunsPerPod < 1 || maxRunsPerPod > 10_000)) {
            throw new GroupValidationException("maxRunsPerPod must be 1..10000");
        }
        if (podMaxAgeHours != null && (podMaxAgeHours < 1 || podMaxAgeHours > 720)) {
            throw new GroupValidationException("podMaxAgeHours must be 1..720");
        }
        switch (policy) {
            case REUSE, EVERY_RUN, DRAIN_AFTER_RUN -> {
                if (maxRunsPerPod != null || podMaxAgeHours != null) {
                    throw new GroupValidationException("policy=" + policy + " takes no thresholds");
                }
            }
            case MAX_RUNS -> {
                if (maxRunsPerPod == null || podMaxAgeHours != null) {
                    throw new GroupValidationException("policy=MAX_RUNS requires maxRunsPerPod only");
                }
            }
            case MAX_AGE -> {
                if (podMaxAgeHours == null || maxRunsPerPod != null) {
                    throw new GroupValidationException("policy=MAX_AGE requires podMaxAgeHours only");
                }
            }
            case BOTH -> {
                if (maxRunsPerPod == null || podMaxAgeHours == null) {
                    throw new GroupValidationException("policy=BOTH requires both maxRunsPerPod and podMaxAgeHours");
                }
            }
        }
    }

    // ── Validation ─────────────────────────────────────────────────

    private static String validateGroupId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GroupValidationException("groupId is required");
        }
        String id = raw.trim();
        if (!GROUP_ID_PATTERN.matcher(id).matches()) {
            throw new GroupValidationException(
                    "groupId must match " + GROUP_ID_REGEX + " — lowercase, letter first, max 30 chars: "
                    + "its upper-case form names the group's tables (<GROUP_ID>_METRICS) and workers send it as ?groupId=");
        }
        return id;
    }

    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GroupValidationException("name is required");
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME_LEN) {
            throw new GroupValidationException("name > " + MAX_NAME_LEN + " chars");
        }
        return name;
    }

    static final int MAX_URL_LEN = 2000;
    static final int MAX_HOT_DAYS = 3650;

    /**
     * A Grafana dashboard URL: blank → none; otherwise an absolute
     * {@code http(s)} URL with a host, ≤ 2000 chars. The UI appends
     * {@code from/to/refresh/var-…} to it, so it must parse as a URL.
     */
    static String validateUrl(String field, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > MAX_URL_LEN) {
            throw new GroupValidationException(field + " > " + MAX_URL_LEN + " chars");
        }
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new GroupValidationException(field + " must be an absolute http(s) URL: " + value);
            }
        } catch (java.net.URISyntaxException e) {
            throw new GroupValidationException(field + " is not a valid URL: " + value);
        }
        return value;
    }

    /** {@code hotDays}: null → the default (7); otherwise 1..3650. */
    static int validateHotDays(Integer raw) {
        if (raw == null) return ApplicationGroup.DEFAULT_HOT_DAYS;
        if (raw < 1 || raw > MAX_HOT_DAYS) {
            throw new GroupValidationException("hotDays must be 1.." + MAX_HOT_DAYS + ", got " + raw);
        }
        return raw;
    }

    private static String validateDescription(String raw) {
        if (raw == null) return null;
        String d = raw.trim();
        if (d.isEmpty()) return null;
        if (d.length() > MAX_DESCRIPTION_LEN) {
            throw new GroupValidationException("description > " + MAX_DESCRIPTION_LEN + " chars");
        }
        return d;
    }

    /** Optional single-line text bounded to the column's 255 chars; blank → null. */
    static String validateName255(String field, String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (v.length() > MAX_NAME_LEN) {
            throw new GroupValidationException(field + " > " + MAX_NAME_LEN + " chars");
        }
        return v;
    }

    static final int MAX_NOTIFY_ADDRESSES = 50;
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+");

    /**
     * A notification list: trimmed, de-duplicated, ≤ 50 entries, each a
     * syntactically valid address. A comma is the storage separator, so an
     * address containing one is rejected rather than silently split on read.
     */
    static List<String> validateAddresses(String field, List<String> raw) {
        if (raw == null) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            String value = entry.trim();
            if (!EMAIL.matcher(value).matches()) {
                throw new GroupValidationException(field + ": '" + value + "' is not a valid email address");
            }
            out.add(value);
        }
        if (out.size() > MAX_NOTIFY_ADDRESSES) {
            throw new GroupValidationException(
                    field + " holds at most " + MAX_NOTIFY_ADDRESSES + " addresses, got " + out.size());
        }
        return List.copyOf(out);
    }

    // ── Request bodies ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateApplicationGroupRequest(String groupId, String name, String description,
                                                String grafanaLiveUrl, String grafanaHistoryUrl, Integer hotDays,
                                                /** The pool's policy; null defaults to REUSE. */
                                                String recyclePolicy, Integer maxRunsPerPod, Integer podMaxAgeHours,
                                                /** null defaults to false. */
                                                Boolean alwaysOn,
                                                /** Who owns the group; display only. */
                                                String teamName,
                                                /** Defaults a workflow's email nodes inherit; null = none. */
                                                List<String> notifyTo, List<String> notifyCc, List<String> notifyBcc) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateApplicationGroupRequest(String name, String description,
                                                String grafanaLiveUrl, String grafanaHistoryUrl, Integer hotDays,
                                                /** Replaced wholesale; null = REUSE with no thresholds. */
                                                String recyclePolicy, Integer maxRunsPerPod, Integer podMaxAgeHours,
                                                /** null = preserve the current value. */
                                                Boolean alwaysOn,
                                                String teamName,
                                                /** Replaced wholesale; null = keep the current list. */
                                                List<String> notifyTo, List<String> notifyCc, List<String> notifyBcc) {}

    // ── Exceptions + handlers ──────────────────────────────────────

    static final class GroupNotFoundException extends RuntimeException {
        GroupNotFoundException(String id) { super("application group not found: " + id); }
    }
    static final class GroupValidationException extends RuntimeException {
        GroupValidationException(String message) { super(message); }
    }
    static final class GroupIdTakenException extends RuntimeException {
        GroupIdTakenException(String id) { super("application group id already exists: " + id); }
    }
    static final class GroupNameTakenException extends RuntimeException {
        GroupNameTakenException(String name) { super("application group name already exists: " + name); }
    }
    static final class GroupHasWorkersException extends RuntimeException {
        GroupHasWorkersException(String id, int pods, int capacity) {
            super("application group '" + id + "' still owns " + pods + " worker" + (pods == 1 ? "" : "s")
                    + " and " + capacity + " capacity row" + (capacity == 1 ? "" : "s")
                    + " — drain its workers and remove its regions before deleting the group");
        }
    }
    static final class GroupHasApplicationsException extends RuntimeException {
        GroupHasApplicationsException(String id, int n) {
            super("application group '" + id + "' still has " + n + " application" + (n == 1 ? "" : "s")
                    + " — move or purge them before deleting the group");
        }
    }

    @ExceptionHandler(GroupNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(GroupNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_GROUP_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(GroupValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(GroupValidationException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(GroupIdTakenException.class)
    public ResponseEntity<Map<String, String>> handleIdTaken(GroupIdTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_GROUP_ID_TAKEN", "message", e.getMessage()));
    }

    @ExceptionHandler(GroupNameTakenException.class)
    public ResponseEntity<Map<String, String>> handleNameTaken(GroupNameTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_GROUP_NAME_TAKEN", "message", e.getMessage()));
    }

    @ExceptionHandler(GroupHasApplicationsException.class)
    public ResponseEntity<Map<String, String>> handleHasApplications(GroupHasApplicationsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_GROUP_HAS_APPLICATIONS", "message", e.getMessage()));
    }

    @ExceptionHandler(GroupHasWorkersException.class)
    public ResponseEntity<Map<String, String>> handleHasWorkers(GroupHasWorkersException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "APPLICATION_GROUP_HAS_WORKERS", "message", e.getMessage()));
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> handleEmpty(EmptyResultDataAccessException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_GROUP_NOT_FOUND", "message", e.getMessage()));
    }
}
