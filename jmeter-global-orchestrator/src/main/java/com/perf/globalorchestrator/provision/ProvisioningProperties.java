package com.perf.globalorchestrator.provision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The deployment's provisioning posture, resolved
 * once at boot and injected wherever behavior branches on it.
 *
 * <p>This is the single source of truth the UI also reads (via
 * {@code GET /api/v1/platform/capabilities}). Hiding a tab in the browser
 * is not a control; the server decides, the browser reflects.
 */
@Component
public class ProvisioningProperties {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisioningProperties.class);

    private final ProvisioningMode mode;
    private final List<String> regions;

    public ProvisioningProperties(
            @Value("${globalOrchestrator.provisioning.mode:DYNAMIC}") String mode,
            // Region identifiers this deployment uses. In STATIC mode these are
            // the operator's data centers (na-east, na-west, …) — the platform
            // keeps calling the axis "region" everywhere (it is already the
            // placement axis in the pod registry, the capacity matrix, the
            // claim SQL and the run request;
            // a parallel dataCenter column would double every key and express
            // nothing new). Only the UI label changes — see regionLabel().
            // Empty means "no deployment override" and the UI keeps its own
            // defaults.
            @Value("${globalOrchestrator.provisioning.regions:}") String regions) {
        this.mode = ProvisioningMode.parse(mode);
        this.regions = parseRegions(regions);
        LOG.info("Provisioning mode = {} ({}); regions = {}",
                this.mode,
                this.mode.isStatic()
                        ? "workers are operator-managed; spin/restart/drain-container and the "
                          + "reconciler/recycler are disabled"
                        : "the control plane owns worker lifecycle",
                this.regions.isEmpty() ? "<deployment default>" : this.regions);
    }

    public ProvisioningMode mode() {
        return mode;
    }

    public boolean isDynamic() {
        return mode.isDynamic();
    }

    public boolean isStatic() {
        return mode.isStatic();
    }

    /** Configured region ids; empty when the deployment sets no override. */
    public List<String> regions() {
        return regions;
    }

    /**
     * What the UI should call the region axis: {@code "dataCenter"} in
     * static mode (operator-deployed workers live in named data centers),
     * {@code "region"} otherwise. Machine-readable so the vocabulary seam
     * from D1 lives here rather than being hardcoded in the browser.
     */
    public String regionLabel() {
        return mode.isStatic() ? "dataCenter" : "region";
    }

    /**
     * Refuses {@code action} when the deployment does not provision. Call
     * this at the top of any controller path that mutates worker
     * lifecycle, so the operator gets a message naming the action rather
     * than a provisioner-level failure.
     */
    public void requireDynamic(String action) {
        if (mode.isStatic()) {
            throw new ProvisioningDisabledException(action);
        }
    }

    /** As {@link #requireDynamic(String)} with an explicit reason clause. */
    public void requireDynamic(String action, String because) {
        if (mode.isStatic()) {
            throw new ProvisioningDisabledException(action, because);
        }
    }

    /**
     * Mirror of {@link #requireDynamic} for operations that only make sense
     * on an operator-managed fleet — declaring a worker the control plane
     * did not create. In dynamic mode names are allocator-owned, so letting
     * an operator declare an arbitrary one would fork the naming authority.
     */
    public void requireStatic(String action) {
        if (mode.isDynamic()) {
            throw new ProvisioningRequiresStaticException(action);
        }
    }

    /** Comma-separated, trimmed, blanks dropped, order preserved, deduplicated. */
    private static List<String> parseRegions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }
}
