package com.perf.orchestrator.observability;

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
 * Populates {@code actor} + {@code runId} MDC
 * keys on critical HTTP endpoints. Mirrors the global-orchestrator
 * filter; the only delta is the source of {@code runId}.
 *
 * <h2>Why a header for runId here</h2>
 * The local-orchestrator's REST resources are singletons by design
 * (no {@code runId} path parameter) — every endpoint is
 * {@code /api/v1/<resource>}, never {@code /api/v1/runs/{runId}/...}.
 * So the runId can't be parsed from the URL. We read it from the
 * {@code X-Run-Id} header instead, which the global-orchestrator's
 * fanout layer will set on every request (wired in Phase D).
 *
 * <p>Until the fanout sets the header, requests show up in logs with
 * no runId — same behaviour you'd see today. The filter is forward-
 * compatible: when the header lands, MDC light up automatically.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcEnrichmentFilter extends OncePerRequestFilter {

    public static final String HEADER_ACTOR  = "X-Actor";
    public static final String HEADER_RUN_ID = "X-Run-Id";
    public static final String MDC_KEY_ACTOR  = "actor";
    public static final String MDC_KEY_RUN_ID = "runId";
    public static final String DEFAULT_ACTOR  = "anonymous";

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
            putMdc(ownedKeys, MDC_KEY_RUN_ID, trimToNull(request.getHeader(HEADER_RUN_ID)));
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

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
