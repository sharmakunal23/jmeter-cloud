package com.perf.k8sorchestrator.http;

import com.perf.k8sorchestrator.provision.ProvisioningProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
 * <p>Values are resolved at boot and never change for the life of the
 * process, so this is a plain read with no caching needed.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final ProvisioningProperties provisioning;

    public PlatformController(ProvisioningProperties provisioning) {
        this.provisioning = provisioning;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok(new Capabilities(
                provisioning.mode().name(),
                provisioning.isDynamic(),
                provisioning.isDynamic(),
                provisioning.regions(),
                provisioning.regionLabel()));
    }

    /**
     * @param provisioningMode      {@code DYNAMIC} | {@code STATIC}
     * @param dynamicScalingEnabled whether the control plane may create /
     *                              destroy workers — gates the Capacity tab,
     *                              spin buttons and the shortfall prompt
     * @param podRecyclingEnabled   whether the recycler runs — gates the
     *                              per-application recycle-policy editor
     * @param regions               region ids this deployment uses; empty
     *                              means "no override, use the UI default".
     *                              In static mode these are the operator's
     *                              data centers
     * @param regionLabel           {@code region} | {@code dataCenter} — what
     *                              the UI should call the axis. The API and
     *                              schema keep saying "region" everywhere —
     *                              do not rename the column; this
     *                              makes the vocabulary seam machine-readable
     *                              instead of hardcoded in the browser
     */
    public record Capabilities(
            String provisioningMode,
            boolean dynamicScalingEnabled,
            boolean podRecyclingEnabled,
            List<String> regions,
            String regionLabel) {}
}
