package com.perf.globalorchestrator.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Whitelist of request-path prefixes that count as "critical" for the
 * observability stack — TracingFilter enriches MDC for them, and
 * ObservationConfig keeps tracing spans for them.
 *
 * <p>The complementary blacklist (paths that are skipped) covers actuator,
 * springdoc, openapi spec, static assets. The design intent:
 * "I want observability only around critical endpoints, non-critical
 * endpoints like health checks can be ignored." Dropping non-critical
 * paths at the observation predicate level prevents low-value spans
 * (every 10-second healthcheck) from flooding Jaeger and lets the
 * operator-targeted 1% sampling rate budget go to the calls that matter.
 *
 * <p>This is a pure utility (no Spring dependency) so it can be unit-tested
 * in isolation and reused from the TracingFilter and the ObservationPredicate
 * without classpath surprises.
 */
public final class CriticalPaths {

    /**
     * Path prefixes treated as critical — every request whose URI starts
     * with one of these strings is observed end-to-end. All four services
     * route their public APIs under {@code /api/v1}, so a single prefix
     * covers every controller.
     */
    public static final List<String> CRITICAL_PREFIXES = List.of("/api/v1");

    /**
     * Prefixes explicitly excluded. Listed for documentation + symmetry —
     * a path that doesn't match {@link #CRITICAL_PREFIXES} is already
     * excluded; this list exists so a future contributor can see at a
     * glance what we deliberately don't observe.
     */
    public static final List<String> NON_CRITICAL_PREFIXES = List.of(
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/openapi.yaml",
            "/favicon.ico",
            "/webjars"
    );

    private CriticalPaths() {}

    /** True when {@code path} is a critical API path. Null-safe. */
    public static boolean isCritical(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String prefix : CRITICAL_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Convenience overload — pulls the path from the request URI. */
    public static boolean isCritical(HttpServletRequest request) {
        return request != null && isCritical(request.getRequestURI());
    }
}
