package com.perf.documentservice.observability;

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
 * line emitted during a critical blob operation carries the blobId + an
 * actor identity. Mirrors the global-orchestrator's TracingFilter; see
 * its Javadoc for the full critical-vs-non-critical policy rationale.
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
        List<String> ownedKeys = new ArrayList<>(2);
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
        String trimmed = header.trim();
        return trimmed.isEmpty() ? DEFAULT_ACTOR : trimmed;
    }
}
