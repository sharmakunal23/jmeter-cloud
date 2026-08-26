package com.perf.documentservice.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Request-path prefixes that count as critical for logging: only these run
 * {@link MdcEnrichmentFilter} and {@link AccessLogFilter}, keeping actuator and
 * springdoc traffic out of the logs entirely.
 *
 * <p>Every service owns its own copy — there is no shared module, by repo
 * convention. A controller added under a new prefix must be added here too, or
 * its requests are logged by neither filter.
 */
public final class CriticalPaths {

    public static final List<String> CRITICAL_PREFIXES = List.of("/api/v1");

    /** Not consulted at runtime — {@link #isCritical} is a whitelist. Listed so a
     *  reviewer can see what the whitelist deliberately excludes. */
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
