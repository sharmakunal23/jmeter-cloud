package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.service.GroupReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Deployment capabilities the UI reads once at
 * boot to decide which surfaces apply.
 *
 * <p>This exists so policy has exactly one home. A build-time
 * {@code VITE_} flag was the cheaper option and was rejected: it cannot
 * enforce anything (hiding the Capacity tab would leave the spin
 * endpoints live), and it would force a UI image rebuild per environment
 * — breaking the one-image-serves-docker-and-Kubernetes property KUBE-6
 * deliberately established. The server decides; the browser reflects.
 *
 * <p>{@code regions} follows the runtime cluster registry (refreshed by the
 * region probe's tick), so this stays a plain uncached read.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final GroupReservationService reservations;
    private final com.perf.globalorchestrator.health.PlatformHealthService platformHealth;

    public PlatformController(GroupReservationService reservations,
                              com.perf.globalorchestrator.health.PlatformHealthService platformHealth) {
        this.reservations = reservations;
        this.platformHealth = platformHealth;
    }

    /**
     * The whole platform's health as one tree, from the hub's last probe
     * round (every minute, async): itself + its Oracle pools and cache, the
     * metrics-consumer, the document-service, and every data center (regional
     * orchestrator + workers). {@code ?refresh=true} probes now (bounded).
     */
    @GetMapping("/health")
    public com.perf.globalorchestrator.health.PlatformHealth health(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean refresh) {
        return refresh ? platformHealth.refreshNow() : platformHealth.snapshot();
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok(new Capabilities(reservations.maxClustersPerGroup()));
    }

    /**
     * @param maxClustersPerGroup how many clusters one application group may
     *                            reserve capacity on. The cluster LIST is not
     *                            here on purpose — it changes at runtime, so
     *                            every surface reads {@code GET /api/v1/regions/status}
     *                            instead of a boot-time snapshot.
     */
    public record Capabilities(int maxClustersPerGroup) {}
}
