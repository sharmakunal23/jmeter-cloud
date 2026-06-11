package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.RegionCapacity;
import com.perf.globalorchestrator.repo.PodRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Pod-registry REST surface. Owned by the local-orchestrators —
 * they call POST /registerPod on boot and POST /heartbeat every 30 s
 * (see jmeter-local-orchestrator's PodRegistrar). Admin / debug
 * endpoint GET /pods returns the current registry view.
 */
@RestController
@RequestMapping("/api/v1")
public class PodController {

    private final PodRepository pods;
    private final Counter registrations;
    private final Counter heartbeats;
    private final Counter unknownHeartbeats;

    public PodController(PodRepository pods, MeterRegistry meterRegistry) {
        this.pods = pods;
        this.registrations = Counter.builder("globalOrchestrator.pods.registrations")
                .description("Successful POST /registerPod calls.")
                .register(meterRegistry);
        this.heartbeats = Counter.builder("globalOrchestrator.pods.heartbeats")
                .description("Successful POST /heartbeat calls.")
                .register(meterRegistry);
        this.unknownHeartbeats = Counter.builder("globalOrchestrator.pods.unknownHeartbeats")
                .description("Heartbeats from podIds the registry doesn't know — caller likely needs to re-register.")
                .register(meterRegistry);
    }

    @PostMapping("/registerPod")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterPodRequest req) {
        if (req == null || req.podId() == null || req.podId().isBlank()
                || req.baseUrl() == null || req.baseUrl().isBlank()
                || req.applicationId() == null || req.applicationId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "INVALID_REQUEST",
                    "message", "podId, baseUrl and applicationId are required"));
        }
        String region = req.region() != null && !req.region().isBlank() ? req.region() : "us-east-1";
        // Phase 6b capacity rework: applicationId is required — every pod is a
        // per-app container bound at provision time. The legacy null-app pool
        // (static orchestrator-1 / -2) was removed in Phase 6, and the column
        // is NOT NULL as of migration V16.
        String applicationId = req.applicationId();
        pods.register(req.podId(), region, req.baseUrl(), applicationId);
        registrations.increment();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("podId",         req.podId());
        body.put("region",        region);
        body.put("baseUrl",       req.baseUrl());
        body.put("state",         "IDLE");
        body.put("applicationId", applicationId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody HeartbeatRequest req) {
        if (req == null || req.podId() == null || req.podId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "INVALID_REQUEST",
                    "message", "podId is required"));
        }
        int updated = pods.heartbeat(req.podId());
        if (updated == 0) {
            // Caller's registry record is gone (e.g., DB reset). Tell them
            // to re-register; surfacing as a counter helps catch this in
            // production.
            unknownHeartbeats.increment();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code",    "POD_NOT_REGISTERED",
                    "message", "podId=" + req.podId() + " is not registered — call POST /registerPod first"));
        }
        heartbeats.increment();
        return ResponseEntity.ok(Map.of("podId", req.podId(), "state", "IDLE"));
    }

    /** Admin / UI view — returns every known pod with its lifecycle state. */
    @GetMapping("/pods")
    public List<Pod> listPods() {
        return pods.findAll();
    }

    /**
     * Track F (Step 26): per-region capacity rollup. The UI's launcher
     * polls this every 5 s to populate the per-region allocation cards
     * and pre-validate {@code fleetAllocation} before submit.
     * {@code idlePods} reflects true availability — claims by active
     * runs are deducted from the count.
     */
    @GetMapping("/regions")
    public List<RegionCapacity> regions() {
        return pods.regionCapacities();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterPodRequest(String podId, String region, String baseUrl, String applicationId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeartbeatRequest(String podId) {}
}
