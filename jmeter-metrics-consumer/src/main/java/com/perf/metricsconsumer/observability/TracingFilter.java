package com.perf.metricsconsumer.observability;

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

/**
 * OBSERVABILITY Phase C — populates the {@code actor} MDC key on the
 * (one) critical HTTP endpoint ({@code POST /api/v1/ingest}). Kafka
 * consumer logs pick up traceId/spanId via Spring Boot's tracing
 * autoconfig — no extra filter needed for the consumer path.
 *
 * <p>Mirrors the global-orchestrator filter; see that class's Javadoc
 * for the full critical-vs-non-critical policy.
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
        List<String> ownedKeys = new ArrayList<>(1);
        try {
            String actor = resolveActor(request);
            MDC.put(MDC_KEY_ACTOR, actor);
            ownedKeys.add(MDC_KEY_ACTOR);
            chain.doFilter(request, response);
        } finally {
            for (String k : ownedKeys) {
                MDC.remove(k);
            }
        }
    }

    private static String resolveActor(HttpServletRequest request) {
        String header = request.getHeader(HEADER_ACTOR);
        if (header == null || header.isBlank()) return DEFAULT_ACTOR;
        String trimmed = header.trim();
        return trimmed.isEmpty() ? DEFAULT_ACTOR : trimmed;
    }
}
