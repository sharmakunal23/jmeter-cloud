package com.perf.documentservice.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Whitelist of request-path prefixes that count as "critical" for the
 * observability stack — TracingFilter enriches MDC for them, and
 * ObservationConfig keeps tracing spans for them.
 *
 * <p>Non-critical paths (actuator, springdoc, static assets) are skipped
 * end-to-end so they don't spend the sampling budget. Each service owns
 * its own copy of this list — there is no shared module by repo
 * convention (each subproject builds independently).
 */
public final class CriticalPaths {

    public static final List<String> CRITICAL_PREFIXES = List.of("/api/v1");

    /** Documented as a sibling to {@link #CRITICAL_PREFIXES} for reviewer clarity. */
    public static final List<String> NON_CRITICAL_PREFIXES = List.of(
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/openapi.yaml",
            "/favicon.ico",
            "/webjars"
    );

    private CriticalPaths() {}

    public static boolean isCritical(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String prefix : CRITICAL_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isCritical(HttpServletRequest request) {
        return request != null && isCritical(request.getRequestURI());
    }
}
