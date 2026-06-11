package com.perf.globalorchestrator.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OBSERVABILITY Phase C — populates request-scoped MDC keys so every log
 * line emitted during a critical request carries the IDs an operator needs
 * to grep by (runId, applicationId, etc.) plus an actor identity.
 *
 * <h2>Critical-vs-non-critical policy</h2>
 * The filter skips itself ({@link #shouldNotFilter}) for non-critical
 * paths — actuator probes, springdoc UI, openapi spec, static assets.
 * Those paths emit log lines under the basic JSON shape (Phase A) and
 * carry the auto-populated traceId/spanId from Spring Boot's
 * Slf4jBaggageManager, but no business IDs and no actor. The complementary
 * {@link ObservationConfig} drops them from tracing entirely so they don't
 * burn the sampling budget.
 *
 * <h2>MDC lifecycle</h2>
 * Keys we set are remembered in a local list and removed in a
 * {@code finally} block, even if the downstream filter chain throws.
 * We deliberately do NOT call {@link MDC#clear()} — that would also wipe
 * the {@code traceId} / {@code spanId} entries Spring Boot's tracing
 * autoconfig owns.
 *
 * <h2>Filter ordering</h2>
 * Registered at {@link Ordered#HIGHEST_PRECEDENCE} + 10 so it runs before
 * any future SECURITY-track auth filter (which will sit at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER}). Putting it first means
 * authentication failures still get logged with the operator's IDs in
 * MDC — handy when a 401 storm hits and the operator needs to see which
 * runId / applicationId was being probed.
 *
 * <h2>Why a header for the actor</h2>
 * The AUDIT-TRAIL track will replace the {@code X-Actor} header with a
 * subject claim from the auth filter. Until then operators can override
 * via the header for manual smoke tests; default is {@code "anonymous"}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TracingFilter extends OncePerRequestFilter {

    public static final String HEADER_ACTOR  = "X-Actor";
    public static final String MDC_KEY_ACTOR = "actor";
    public static final String DEFAULT_ACTOR = "anonymous";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CriticalPaths.isCritical(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        List<String> ownedKeys = new ArrayList<>(6);
        try {
            putMdc(ownedKeys, MDC_KEY_ACTOR, resolveActor(request));
            for (Map.Entry<String, String> e : PathIds.extract(request.getRequestURI()).entrySet()) {
                putMdc(ownedKeys, e.getKey(), e.getValue());
            }
            chain.doFilter(request, response);
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

    private static String resolveActor(HttpServletRequest request) {
        String header = request.getHeader(HEADER_ACTOR);
        if (header == null || header.isBlank()) return DEFAULT_ACTOR;
        // Defensive trim — header values can pick up whitespace from proxies.
        String trimmed = header.trim();
        return trimmed.isEmpty() ? DEFAULT_ACTOR : trimmed;
    }
}
