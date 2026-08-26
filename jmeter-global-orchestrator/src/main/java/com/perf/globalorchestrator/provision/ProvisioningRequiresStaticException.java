package com.perf.globalorchestrator.provision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mirror of
 * {@link ProvisioningDisabledException}: an operation that only makes sense
 * on an operator-managed fleet was requested while this deployment
 * provisions its own workers.
 *
 * <p>Declaring is the case that matters. In {@code DYNAMIC} mode worker
 * names are allocated by {@code PodNameAllocator} and the control plane
 * owns the containers behind them; accepting an operator-chosen name would
 * fork the naming authority and produce a registry row with no container to
 * match — precisely the drift {@code PodReconciler} exists to delete. Use
 * {@code POST .../pods} (spin) instead.
 */
public class ProvisioningRequiresStaticException extends RuntimeException {

    /** Stable error code clients match on. */
    public static final String CODE = "PROVISIONING_REQUIRES_STATIC";

    private final String action;

    public ProvisioningRequiresStaticException(String action) {
        super("cannot " + action + " while " + ProvisioningMode.PROPERTY + "=DYNAMIC — "
                + "this deployment provisions its own workers; use POST .../pods to spin one");
        this.action = action;
    }

    public String action() {
        return action;
    }

    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", CODE);
        body.put("message", getMessage());
        body.put("action", action);
        body.put("provisioningMode", ProvisioningMode.DYNAMIC.name());
        return body;
    }
}
