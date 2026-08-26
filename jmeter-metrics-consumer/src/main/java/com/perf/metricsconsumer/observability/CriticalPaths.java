package com.perf.metricsconsumer.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Request-path prefixes that count as critical for logging: only these run
 * {@link MdcEnrichmentFilter} and {@link AccessLogFilter}, keeping actuator and
 * springdoc traffic out of the logs.
 *
 * <p>One prefix is enough here — {@code /api/v1/ingest} is this service's only
 * business surface, and it is the platform's sole metrics path.
 */
public final class CriticalPaths {

    public static final List<String> CRITICAL_PREFIXES = List.of("/api/v1");

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
