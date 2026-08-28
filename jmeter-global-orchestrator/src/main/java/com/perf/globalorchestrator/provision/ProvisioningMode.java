package com.perf.globalorchestrator.provision;

import java.util.Locale;

/**
 * Whether this deployment may create worker pods
 * on demand.
 *
 * <p>Selected by the {@code PROVISIONING_MODE} env var (bound to
 * {@link #PROPERTY}); the platform default is {@link #STATIC}. Under STATIC
 * every {@code podProvisioner.*} key is ignored and no provisioner bean is
 * wired.
 *
 * @see ConditionalOnProvisioningMode
 * @see ProvisioningProperties
 */
public enum ProvisioningMode {

    /**
     * The control plane owns worker lifecycle: it spins, restarts, drains
     * and recycles pods through a {@link PodProvisioner}. The historical
     * (and default) behavior.
     */
    DYNAMIC,

    /**
     * Workers are deployed and owned by the operator; the control plane
     * only <em>uses</em> them. Every mutating provisioner path is refused
     * ({@code 409 PROVISIONING_DISABLED}) and the reconciler / recycler
     * are not wired at all. Bean absence rather than an in-method flag
     * check is deliberate: "the reconciler does not exist here" is a
     * structural guarantee, where "every entry point remembers to check"
     * is only a promise.
     */
    STATIC;

    /** Spring property this mode is read from. */
    public static final String PROPERTY = "globalOrchestrator.provisioning.mode";

    /**
     * Parses a raw property value. Null/blank means {@link #STATIC} — the
     * platform default since 2026-07-27, when the operator made
     * operator-managed fleets the norm rather than the exception. An
     * unrecognised value throws rather than silently defaulting: a typo like
     * {@code PROVISIONING_MODE=statics} must fail the boot loudly, not
     * quietly pick a posture nobody asked for.
     */
    public static ProvisioningMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    PROPERTY + " must be DYNAMIC or STATIC (case-insensitive); got '" + raw + "'");
        }
    }

    public boolean isDynamic() {
        return this == DYNAMIC;
    }

    public boolean isStatic() {
        return this == STATIC;
    }
}
