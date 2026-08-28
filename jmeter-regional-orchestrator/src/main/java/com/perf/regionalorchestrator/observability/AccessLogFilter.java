package com.perf.regionalorchestrator.observability;

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
 * Emits one structured access-log record per critical request — method, path,
 * status, latency and client IP go into the MDC just before an {@code INFO} on
 * the dedicated {@code "access"} logger, so they render as top-level JSON fields
 * and operators can route them separately from app logs.
 *
 * <p>Scoped by {@link CriticalPaths}: actuator, swagger, the spec and static
 * assets are skipped entirely. Without that gate, a 10-second healthcheck
 * cadence across five services is 4,320 lines an hour drowning the signal.
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 20}, after
 * {@link MdcEnrichmentFilter} at +10, so the business IDs it logs are already
 * in the MDC by the time this fires.
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
     * the originating client (not a proxy) is logged. Takes the first token if multiple
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
