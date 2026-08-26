package com.perf.globalorchestrator.domain;

/**
 * Who performed a state-changing action, and how the server
 * learned their identity. Built from the {@code X-Actor} request header today
 * (self-attested, unauthenticated); when the cloud auth filter lands it will
 * construct verified actors tagged {@link #SOURCE_OIDC_SUBJECT} /
 * {@link #SOURCE_IAM_ROLE} instead — a header read, not a schema change.
 *
 * <p>{@code source} lets a future incident responder weigh trust: "Alice typed
 * her name into a curl flag" ({@link #SOURCE_HEADER}) reads very differently
 * from "OIDC verified Alice signed in via SSO" ({@link #SOURCE_OIDC_SUBJECT}).
 *
 * @param name   the actor identity (default {@link #ANONYMOUS}).
 * @param source how {@code name} was learned (one of the {@code SOURCE_*}).
 */
public record Actor(String name, String source) {

    /** Identity used when no actor is supplied. */
    public static final String ANONYMOUS = "anonymous";

    /** Header absent / blank — we don't know who acted (default, local profile). */
    public static final String SOURCE_ANONYMOUS = "anonymous";
    /** X-Actor header supplied but unauthenticated — operator self-attested. */
    public static final String SOURCE_HEADER = "headerActor";
    /** Derived from a verified JWT subject claim (cloud profile). */
    public static final String SOURCE_OIDC_SUBJECT = "oidcSubject";
    /** Derived from an AWS IAM role (cloud profile, machine-to-machine). */
    public static final String SOURCE_IAM_ROLE = "iamRole";
    /**
     * The action was initiated by the platform itself, not a human — e.g. a
     * scheduled AUTOMATION run or an internal reconciler. The UI renders these
     * with a "(System)" marker so operators can tell automated activity apart
     * from their own.
     */
    public static final String SOURCE_SYSTEM = "system";

    /** Shared instance for the no-identity-supplied case. */
    public static final Actor ANONYMOUS_ACTOR = new Actor(ANONYMOUS, SOURCE_ANONYMOUS);

    /**
     * A system/automated actor (e.g. the AUTOMATION scheduler). {@code name}
     * identifies the automation (e.g. "scheduler", "ciWebhook"); {@code source}
     * is always {@link #SOURCE_SYSTEM}.
     */
    public static Actor system(String name) {
        return new Actor(name == null || name.isBlank() ? "system" : name.trim(), SOURCE_SYSTEM);
    }

    /**
     * Classify an incoming {@code X-Actor} header value. Null / blank /
     * whitespace-only → {@link #ANONYMOUS_ACTOR}; otherwise the trimmed value
     * tagged {@link #SOURCE_HEADER}. Mirrors the trim semantics of
     * {@code MdcEnrichmentFilter.resolveActor} so the audit actor and the MDC actor
     * always agree for a given request.
     */
    public static Actor fromHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return ANONYMOUS_ACTOR;
        }
        return new Actor(headerValue.trim(), SOURCE_HEADER);
    }
}
