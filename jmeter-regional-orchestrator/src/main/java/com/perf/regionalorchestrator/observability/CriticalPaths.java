package com.perf.regionalorchestrator.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Request-path prefixes that count as critical for logging: only these run
 * {@link MdcEnrichmentFilter} and {@link AccessLogFilter}, so actuator,
 * springdoc, the spec and static assets stay out of the log stream entirely.
 *
 * <p>A pure utility with no Spring dependency, so both filters and the unit
 * tests can use it without classpath surprises. Adding a controller under a new
 * prefix means adding it here too, or its requests are logged by neither filter.
 */
public final class CriticalPaths {

    /**
     * Path prefixes treated as critical — every request whose URI starts
     * with one of these strings is observed end-to-end. Every controller
     * routes under {@code /api/v1}, so a single prefix covers them all.
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
