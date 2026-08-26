package com.perf.k8sorchestrator.provision;

import java.util.Locale;

/**
 * Whether this deployment may create worker pods
 * on demand.
 *
 * <p>Selected by the {@code PROVISIONING_MODE} env var (bound to
 * {@link #PROPERTY}); defaults to {@link #DYNAMIC} so an existing
 * deployment behaves exactly as it did before this flag existed.
 *
 * <h2>Why this is separate from {@code podProvisioner.substrate}</h2>
 * "Substrate" answers <em>how</em> we create pods (docker socket vs the
 * Kubernetes API). This answers <em>whether</em> we create them at all.
 * Overloading the substrate key with a third value would silently
 * invalidate a dozen sibling {@code podProvisioner.*} keys and read as
 * "static is a kind of daemon", which it isn't. When the mode is
 * {@link #STATIC} the substrate key is ignored entirely — neither
 * daemon-backed provisioner bean is wired.
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
    public static final String PROPERTY = "k8sOrchestrator.provisioning.mode";

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
