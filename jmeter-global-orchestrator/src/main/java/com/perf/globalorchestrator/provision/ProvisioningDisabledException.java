package com.perf.globalorchestrator.provision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pod-lifecycle operation was requested while
 * {@code PROVISIONING_MODE=STATIC}. Workers are operator-managed in that
 * mode; the control plane must not create, restart or destroy them.
 *
 * <p>Thrown from two layers on purpose:
 * <ul>
 *   <li><b>Controllers</b> guard <em>before</em> touching the provisioner so
 *       the operator gets a precise message naming the action.</li>
 *   <li>{@link StaticPodProvisioner} throws it from every mutator as
 *       defence in depth — a path that forgets its guard still fails as a
 *       clean {@code 409} instead of a 500 with a stack trace.</li>
 * </ul>
 *
 * <p>Deliberately a plain {@link RuntimeException} with no Spring HTTP
 * annotations: the provision package stays free of web concerns, and each
 * controller maps it with its own {@code @ExceptionHandler} (the house
 * pattern here — there is no {@code @ControllerAdvice} in this service).
 * {@link #toBody()} keeps the two response bodies from drifting.
 */
public class ProvisioningDisabledException extends RuntimeException {

    /** Stable error code clients match on. */
    public static final String CODE = "PROVISIONING_DISABLED";

    private final String action;

    /**
     * @param action operator-facing name of the refused operation, e.g.
     *               {@code "spin a worker"} — phrased to read after
     *               "cannot".
     */
    public ProvisioningDisabledException(String action) {
        this(action, "workers are operator-managed in this deployment");
    }

    /**
     * @param because trailing clause replacing the default "workers are
     *                operator-managed" — for refusals whose real reason is
     *                different, e.g. capacity being derived rather than set.
     */
    public ProvisioningDisabledException(String action, String because) {
        super("cannot " + action + " while " + ProvisioningMode.PROPERTY + "=STATIC — " + because);
        this.action = action;
    }

    public String action() {
        return action;
    }

    /** Response body shared by every handler, so the shape can't drift. */
    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", CODE);
        body.put("message", getMessage());
        body.put("action", action);
        body.put("provisioningMode", ProvisioningMode.STATIC.name());
        return body;
    }
}
