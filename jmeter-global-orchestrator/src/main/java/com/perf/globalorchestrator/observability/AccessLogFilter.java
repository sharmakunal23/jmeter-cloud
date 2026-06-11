package com.perf.globalorchestrator.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OBSERVABILITY Phase E — emits one structured access-log record per
 * critical request. Method, path, status, latencyMs, and clientIp are
 * placed in MDC immediately before the {@code INFO} call, so the existing
 * {@code logstash-logback-encoder} (Phase A) renders them as top-level
 * JSON fields. The dedicated logger name {@code "access"} lets operators
 * grep / route these lines separately from regular app logs.
 *
 * <h2>Critical-vs-non-critical policy</h2>
 * The filter follows the same {@link CriticalPaths} gate as the TracingFilter:
 * actuator probes, swagger UI, openapi spec, and static assets are skipped
 * entirely — no log line, no MDC mutation. Otherwise a 10-second healthcheck
 * cadence × 5 services × 1 access line each = 4,320 nonsense lines per hour
 * drowning the signal.
 *
 * <h2>Filter ordering</h2>
 * Registered at {@code HIGHEST_PRECEDENCE + 20} — runs AFTER the
 * {@link TracingFilter} ({@code HIGHEST_PRECEDENCE + 10}) so the MDC keys
 * the access line emits ({@code actor}, {@code runId}, etc.) are already
 * populated by the time we log. Spring Boot's tracing autoconfig sets
 * {@code traceId} / {@code spanId} EARLIER in the chain, so those are
 * also present.
 *
 * <h2>MDC discipline</h2>
 * Owned keys are tracked in a local list and removed in {@code finally} —
 * never {@link MDC#clear()} (which would wipe the tracing-autoconfig keys).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AccessLogFilter extends OncePerRequestFilter {

    public static final String MDC_METHOD       = "method";
    public static final String MDC_PATH         = "path";
    public static final String MDC_STATUS       = "status";
    public static final String MDC_LATENCY_MS   = "latencyMs";
    public static final String MDC_CLIENT_IP    = "clientIp";
    public static final String MDC_USER_AGENT   = "userAgent";

    /** Dedicated logger name so access lines are grep-friendly. */
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CriticalPaths.isCritical(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        Throwable thrown = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // Capture-and-rethrow so the access line still fires for failed
            // requests — operators need to see 5xx + the latency it took
            // to fail. Java requires the rethrow for checked types.
            thrown = e;
            throw e;
        } finally {
            long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            emitAccessLine(request, response, latencyMs, thrown);
        }
    }

    private static void emitAccessLine(HttpServletRequest request,
                                       HttpServletResponse response,
                                       long latencyMs,
                                       Throwable thrown) {
        List<String> ownedKeys = new ArrayList<>(6);
        try {
            putMdc(ownedKeys, MDC_METHOD,      request.getMethod());
            putMdc(ownedKeys, MDC_PATH,        request.getRequestURI());
            // Status may be 0 if a downstream filter blew up before the
            // response committed — log it anyway so the operator can see
            // the request hit the server.
            putMdc(ownedKeys, MDC_STATUS,      String.valueOf(response.getStatus()));
            putMdc(ownedKeys, MDC_LATENCY_MS,  String.valueOf(latencyMs));
            putMdc(ownedKeys, MDC_CLIENT_IP,   clientIp(request));
            putMdc(ownedKeys, MDC_USER_AGENT,  request.getHeader("User-Agent"));
            if (thrown != null) {
                ACCESS_LOG.warn("access");
            } else {
                ACCESS_LOG.info("access");
            }
        } finally {
            for (String k : ownedKeys) {
                MDC.remove(k);
            }
        }
    }

    private static void putMdc(List<String> owned, String key, String value) {
        if (value == null || value.isBlank()) return;
        MDC.put(key, value);
        owned.add(key);
    }

    /**
     * Best-effort source-IP extraction. Honors {@code X-Forwarded-For} so
     * the originating client (not the nginx proxy) is logged when the UI
     * fronts the global-orchestrator. Takes the first token if multiple
     * proxies appended.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return first.trim();
        }
        return request.getRemoteAddr();
    }
}
