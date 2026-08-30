package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The application-group registry. A group's {@code groupId} is what workers
 * send as {@code ?groupId=} on metrics POSTs and, upper-cased, the prefix of the
 * group's tables in {@code CARDZATE_DB_GRAF} ({@code cps} → {@code CPS_METRICS});
 * it must equal a {@code GROUP_REGISTRY.GROUP_ID} there. Groups are hard-deleted
 * and a delete is refused while any application (visible or archived) is in
 * the group.
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
    /** The same regex serves as the path constraint (no capture groups). */
    private static final String GROUP_ID_PATH = GROUP_ID_REGEX;
    private static final int MAX_NAME_LEN = 255;
    private static final int MAX_DESCRIPTION_LEN = 512;

    private final ApplicationGroupRepository repo;

    public ApplicationGroupController(ApplicationGroupRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<ApplicationGroup>> list() {
        Map<String, Integer> counts = repo.applicationCounts();
        return ResponseEntity.ok(repo.findAll().stream()
                .map(g -> g.withApplicationCount(counts.getOrDefault(g.groupId(), 0)))
                .toList());
    }

    @GetMapping("/{groupId:" + GROUP_ID_PATH + "}")
    public ResponseEntity<ApplicationGroup> get(@PathVariable String groupId) {
        ApplicationGroup group = repo.findById(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        return ResponseEntity.ok(group.withApplicationCount(repo.countApplications(groupId)));
    }

    @PostMapping
    public ResponseEntity<ApplicationGroup> create(@RequestBody CreateApplicationGroupRequest req) {
        String groupId = validateGroupId(req.groupId());
        String name = validateName(req.name());
        String description = validateDescription(req.description());
        String grafanaLiveUrl = validateUrl("grafanaLiveUrl", req.grafanaLiveUrl());
        String grafanaHistoryUrl = validateUrl("grafanaHistoryUrl", req.grafanaHistoryUrl());
        int hotDays = validateHotDays(req.hotDays());
        if (repo.findById(groupId).isPresent()) {
            throw new GroupIdTakenException(groupId);
        }
        ApplicationGroup stored;
        try {
            stored = repo.insert(new ApplicationGroup(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays, Instant.now(), null));
        } catch (DuplicateKeyException e) {
            throw new GroupNameTakenException(name);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(stored.withApplicationCount(0));
    }

    @PutMapping("/{groupId:" + GROUP_ID_PATH + "}")
    public ResponseEntity<ApplicationGroup> update(@PathVariable String groupId,
                                                   @RequestBody UpdateApplicationGroupRequest req) {
        repo.findById(groupId).orElseThrow(() -> new GroupNotFoundException(groupId));
        String name = validateName(req.name());
        String description = validateDescription(req.description());
        String grafanaLiveUrl = validateUrl("grafanaLiveUrl", req.grafanaLiveUrl());
        String grafanaHistoryUrl = validateUrl("grafanaHistoryUrl", req.grafanaHistoryUrl());
        int hotDays = validateHotDays(req.hotDays());
        try {
            ApplicationGroup updated = repo.update(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays);
            return ResponseEntity.ok(updated.withApplicationCount(repo.countApplications(groupId)));
        } catch (DuplicateKeyException e) {
            throw new GroupNameTakenException(name);
        }
    }

    /** Idempotent: an unknown group is already gone (204); a group with applications is 409. */
    @DeleteMapping("/{groupId:" + GROUP_ID_PATH + "}")
    public ResponseEntity<Void> delete(@PathVariable String groupId) {
        int inUse = repo.countApplications(groupId);
        if (inUse > 0) {
            throw new GroupHasApplicationsException(groupId, inUse);
        }
        try {
            repo.delete(groupId);
        } catch (DataIntegrityViolationException e) {
            // An application was assigned between the count and the delete.
            throw new GroupHasApplicationsException(groupId, repo.countApplications(groupId));
        }
        return ResponseEntity.noContent().build();
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

    // ── Request bodies ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateApplicationGroupRequest(String groupId, String name, String description,
                                                String grafanaLiveUrl, String grafanaHistoryUrl, Integer hotDays) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateApplicationGroupRequest(String name, String description,
                                                String grafanaLiveUrl, String grafanaHistoryUrl, Integer hotDays) {}

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

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> handleEmpty(EmptyResultDataAccessException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_GROUP_NOT_FOUND", "message", e.getMessage()));
    }
}
