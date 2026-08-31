package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.perf.globalorchestrator.domain.Region;
import com.perf.globalorchestrator.domain.RegionCapacity;
import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionStatus;
import com.perf.globalorchestrator.region.RegionValidationService;
import com.perf.globalorchestrator.region.RegionValidationService.ClusterCheck;
import com.perf.globalorchestrator.region.RegionValidationService.ClusterValidationException;
import com.perf.globalorchestrator.region.TestProvisionService;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RegionRepository;
import com.perf.globalorchestrator.service.ClusterRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cluster registry's API (CLUSTER-CAPACITY) — the UI's "Clusters" page.
 * A cluster is registered at runtime with its regional-orchestrator endpoint
 * and only after the validation chain passes ({@link RegionValidationService});
 * {@code GET /status} is the page's single read (registration + probe verdict
 * + reservations + worker counts per cluster, no fan-out);
 * {@code POST /{region}/testProvision} runs the deep probe asynchronously.
 * The axis stays {@code region} in every path and column — "Cluster" is the
 * display word.
 */
@RestController
@RequestMapping("/api/v1/regions")
public class RegionController {

    private static final Logger LOG = LoggerFactory.getLogger(RegionController.class);
    /** Hard cap per cluster: the 180 GB namespace grant at 9 GB per worker. */
    private static final int MAX_WORKERS_CAP = 20;
    /** {@code ORCH_REGION.LAST_PROBE_STATUS} while a probe holds the cluster's slot. */
    private static final String PROBE_RUNNING = "RUNNING";

    private final RegionRepository repo;
    private final RegionRegistry registry;
    private final RegionValidationService validator;
    private final TestProvisionService testProvision;
    private final GroupCapacityRepository capacity;
    private final PodRepository pods;
    /** Owns the writes that must serialise against a concurrent reservation. */
    private final ClusterRegistryService clusters;

    public RegionController(RegionRepository repo, RegionRegistry registry,
                            RegionValidationService validator, TestProvisionService testProvision,
                            GroupCapacityRepository capacity, PodRepository pods,
                            ClusterRegistryService clusters) {
        this.repo = repo;
        this.registry = registry;
        this.validator = validator;
        this.testProvision = testProvision;
        this.capacity = capacity;
        this.pods = pods;
        this.clusters = clusters;
    }

    // ── GET /status — the Clusters page's single read ──────────────────

    @GetMapping("/status")
    public List<ClusterStatusView> status() {
        Map<String, Integer> reserved = capacity.reservedByRegion();
        Map<String, Long> provisioned = new LinkedHashMap<>();
        for (RegionCapacity c : pods.regionCapacities()) {
            provisioned.put(c.region(), c.totalPods());
        }
        return repo.findAll().stream()
                .map(r -> view(r, reserved.getOrDefault(r.region(), 0),
                        provisioned.getOrDefault(r.region(), 0L)))
                .toList();
    }

    // ── POST — register a cluster (validated first) ────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterClusterRequest req) {
        if (req == null) throw new ClusterRequestException("request body is required");
        String region = requireRegionId(req.region());
        String label = requireLabel(req.label());
        int maxWorkers = requireMaxWorkers(req.maxWorkers());
        if (repo.find(region).isPresent()) {
            throw new ClusterExistsException(region);
        }
        String url = stripTrailingSlash(req.regionalUrl());
        // Uniqueness before the (slow) validation chain: id, display name and
        // regional URL must each identify exactly one cluster.
        requireUniqueLabelAndUrl(label, url, null);
        List<ClusterCheck> checks = validator.validate(region, url);
        try {
            repo.insert(region, label, url, maxWorkers);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            throw duplicateOf(race, region, label, url);
        }
        registry.reload();
        LOG.info("cluster {} registered: {} at {} (maxWorkers={})", region, label, url, maxWorkers);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cluster", view(repo.find(region).orElseThrow(), 0, 0L));
        body.put("checks", checks);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ── PUT /{region} — edit label / endpoint / ceiling ────────────────

    @PutMapping("/{region}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String region,
                                                      @RequestBody UpdateClusterRequest req) {
        if (req == null) throw new ClusterRequestException("request body is required");
        Region existing = repo.find(region).orElseThrow(() -> new ClusterNotFoundException(region));
        String label = requireLabel(req.label() == null ? existing.label() : req.label());
        int maxWorkers = requireMaxWorkers(req.maxWorkers() == null ? existing.maxWorkers() : req.maxWorkers());
        String url = stripTrailingSlash(req.regionalUrl() == null ? existing.regionalUrl() : req.regionalUrl());

        requireUniqueLabelAndUrl(label, url, region);
        boolean urlChanged = !url.equals(existing.regionalUrl());
        // Validate BEFORE the transaction — it dials the regional, and a row
        // lock must never span a network call.
        List<ClusterCheck> checks = urlChanged ? validator.validate(region, url) : List.of();
        int reserved;
        try {
            reserved = clusters.update(region, label, url, maxWorkers, urlChanged);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            throw duplicateOf(race, region, label, url);
        }
        registry.reload();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cluster", view(repo.find(region).orElseThrow(), reserved,
                pods.regionCapacities().stream()
                        .filter(c -> region.equals(c.region()))
                        .map(RegionCapacity::totalPods).findFirst().orElse(0L)));
        if (urlChanged) body.put("checks", checks);
        return ResponseEntity.ok(body);
    }

    // ── DELETE /{region} — remove an unused cluster ────────────────────

    @DeleteMapping("/{region}")
    public ResponseEntity<Void> delete(@PathVariable String region) {
        repo.find(region).orElseThrow(() -> new ClusterNotFoundException(region));
        clusters.delete(region);
        registry.reload();
        LOG.info("cluster {} deregistered", region);
        return ResponseEntity.noContent().build();
    }

    // ── POST /{region}/testProvision — the async deep probe ────────────

    @PostMapping("/{region}/testProvision")
    public ResponseEntity<Map<String, Object>> testProvision(@PathVariable String region) {
        // The row is the authority for the endpoint — a replica whose registry
        // snapshot has not caught up must not refuse a registered cluster.
        Region row = repo.find(region).orElseThrow(() -> new ClusterNotFoundException(region));
        boolean started = testProvision.start(region, row.regionalUrl());
        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "PROBE_IN_PROGRESS",
                    "message", "a test-provisioning probe is already running for cluster '" + region + "'"));
        }
        return ResponseEntity.accepted().body(Map.of(
                "region", region,
                "probing", true,
                "message", "spinning one probe worker; the verdict lands on GET /api/v1/regions/status"));
    }

    // ── view assembly ──────────────────────────────────────────────────

    private ClusterStatusView view(Region r, int reservedWorkers, long provisionedWorkers) {
        Optional<RegionStatus> probe = registry.statusOf(r.region());
        return new ClusterStatusView(
                r.region(), r.label(), r.regionalUrl(), r.maxWorkers(),
                reservedWorkers, provisionedWorkers,
                probe.map(RegionStatus::reachable).orElse(null),
                probe.map(RegionStatus::lastSeenAt).orElse(null),
                probe.map(RegionStatus::lastError).orElse(null),
                probe.map(RegionStatus::capabilities).orElse(null),
                r.lastValidatedAt(),
                // RUNNING is the in-flight claim, not a verdict — it surfaces as
                // `probing` instead, and correctly on every replica.
                r.lastProbeAt() == null || PROBE_RUNNING.equals(r.lastProbeStatus()) ? null
                        : new ProbeVerdict(r.lastProbeAt(), r.lastProbeStatus(), r.lastProbeDetail()),
                PROBE_RUNNING.equals(r.lastProbeStatus()));
    }

    private static String requireRegionId(String region) {
        if (region == null || !RegionValidationService.REGION_ID.matcher(region).matches()) {
            throw new ClusterRequestException("region must match " + RegionValidationService.REGION_ID.pattern()
                    + " (lowercase DNS-1123, max 20 chars so worker pod names stay valid): " + region);
        }
        return region;
    }

    private static String requireLabel(String label) {
        if (label == null || label.isBlank() || label.length() > 255) {
            throw new ClusterRequestException("label is required (max 255 chars)");
        }
        return label.trim();
    }

    private static int requireMaxWorkers(Integer maxWorkers) {
        int v = maxWorkers == null ? MAX_WORKERS_CAP : maxWorkers;
        if (v < 1 || v > MAX_WORKERS_CAP) {
            throw new ClusterRequestException("maxWorkers must be 1.." + MAX_WORKERS_CAP
                    + " — a cluster hosts at most " + MAX_WORKERS_CAP
                    + " workers (the 180 GB grant at 9 GB per worker); got " + v);
        }
        return v;
    }

    /**
     * A cluster is identified three ways — id, display name, regional URL —
     * and each must be unique. {@code exceptRegion} skips the row being
     * edited; the {@code ORCH_REGION_*_UQ} constraints close the race.
     */
    private void requireUniqueLabelAndUrl(String label, String url, String exceptRegion) {
        repo.findByLabel(label)
                .filter(r -> !r.region().equals(exceptRegion))
                .ifPresent(r -> { throw new ClusterNameTakenException(label, r.region()); });
        repo.findByRegionalUrl(url)
                .filter(r -> !r.region().equals(exceptRegion))
                .ifPresent(r -> { throw new ClusterUrlTakenException(url, r.region()); });
    }

    /** Maps a lost uniqueness race onto the same 409 the pre-check gives. */
    private static RuntimeException duplicateOf(org.springframework.dao.DuplicateKeyException e,
                                                String region, String label, String url) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("ORCH_REGION_LABEL_UQ")) return new ClusterNameTakenException(label, null);
        if (msg.contains("ORCH_REGION_REGIONAL_URL_UQ")) return new ClusterUrlTakenException(url, null);
        return new ClusterExistsException(region);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) throw new ClusterRequestException("regionalUrl is required");
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterClusterRequest(String region, String label, String regionalUrl, Integer maxWorkers) {}

    /** Null field = keep the current value; a changed regionalUrl re-runs validation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateClusterRequest(String label, String regionalUrl, Integer maxWorkers) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProbeVerdict(Instant at, String status, String detail) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClusterStatusView(
            String region,
            String label,
            String regionalUrl,
            int maxWorkers,
            int reservedWorkers,
            long provisionedWorkers,
            Boolean reachable,
            Instant lastSeenAt,
            String lastError,
            RegionCapabilities capabilities,
            Instant lastValidatedAt,
            ProbeVerdict lastProbe,
            boolean probing) {}

    // ── Exceptions → HTTP ──────────────────────────────────────────────

    static final class ClusterRequestException extends RuntimeException {
        ClusterRequestException(String message) { super(message); }
    }
    static final class ClusterNotFoundException extends RuntimeException {
        ClusterNotFoundException(String region) {
            super("region '" + region + "' is not a registered cluster");
        }
    }
    static final class ClusterExistsException extends RuntimeException {
        ClusterExistsException(String region) {
            super("cluster '" + region + "' is already registered; edit it instead");
        }
    }
    static final class ClusterNameTakenException extends RuntimeException {
        ClusterNameTakenException(String label, String byRegion) {
            super("display name '" + label + "' is already used"
                    + (byRegion == null ? "" : " by cluster '" + byRegion + "'")
                    + " — every cluster needs its own name");
        }
    }
    static final class ClusterUrlTakenException extends RuntimeException {
        ClusterUrlTakenException(String url, String byRegion) {
            super("regional orchestrator " + url + " is already registered"
                    + (byRegion == null ? "" : " as cluster '" + byRegion + "'")
                    + " — one regional serves exactly one cluster");
        }
    }

    @ExceptionHandler(ClusterRequestException.class)
    public ResponseEntity<Map<String, String>> handleRequest(ClusterRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }
    @ExceptionHandler(ClusterNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ClusterNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "CLUSTER_NOT_REGISTERED", "message", e.getMessage()));
    }
    @ExceptionHandler(ClusterExistsException.class)
    public ResponseEntity<Map<String, String>> handleExists(ClusterExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CLUSTER_EXISTS", "message", e.getMessage()));
    }
    @ExceptionHandler(ClusterNameTakenException.class)
    public ResponseEntity<Map<String, String>> handleNameTaken(ClusterNameTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CLUSTER_NAME_TAKEN", "message", e.getMessage()));
    }
    @ExceptionHandler(ClusterUrlTakenException.class)
    public ResponseEntity<Map<String, String>> handleUrlTaken(ClusterUrlTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CLUSTER_URL_TAKEN", "message", e.getMessage()));
    }
    @ExceptionHandler(ClusterRegistryService.ClusterInUseException.class)
    public ResponseEntity<Map<String, Object>> handleInUse(ClusterRegistryService.ClusterInUseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "CLUSTER_IN_USE", "message", e.getMessage(),
                "reservations", e.reservations, "workers", e.workers));
    }
    @ExceptionHandler(ClusterRegistryService.ShrinkBelowReservedException.class)
    public ResponseEntity<Map<String, Object>> handleShrink(ClusterRegistryService.ShrinkBelowReservedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "CLUSTER_SHRINK_BELOW_RESERVED", "message", e.getMessage(),
                "maxWorkers", e.maxWorkers, "reserved", e.reserved));
    }
    /** Deregistered mid-call — the same 404 a missing cluster gives. */
    @ExceptionHandler(ClusterRegistryService.ClusterGoneException.class)
    public ResponseEntity<Map<String, String>> handleGone(ClusterRegistryService.ClusterGoneException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "CLUSTER_NOT_REGISTERED", "message", e.getMessage()));
    }
    /** The whole checklist rides along so the UI can render ✓/✗ per check. */
    @ExceptionHandler(ClusterValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ClusterValidationException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", e.code, "message", e.getMessage(), "region", e.region, "checks", e.checks));
    }
}
