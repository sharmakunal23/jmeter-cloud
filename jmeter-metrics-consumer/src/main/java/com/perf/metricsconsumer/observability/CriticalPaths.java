package com.perf.metricsconsumer.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Whitelist of request-path prefixes that count as "critical" for the
 * observability stack. Metrics-consumer is primarily a Kafka consumer;
 * the only HTTP surface is {@code /api/v1/ingest} (a fallback replay
 * endpoint), so the critical list is a single prefix.
 *
 * <p>Non-critical paths (actuator, swagger, openapi spec) are skipped.
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
