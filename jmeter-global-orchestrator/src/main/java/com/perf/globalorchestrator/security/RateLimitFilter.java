package com.perf.globalorchestrator.security;

import com.perf.globalorchestrator.observability.CriticalPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SECURITY S-1 — per-(client IP, endpoint-class) rate limit, the in-process
 * backstop behind nginx's {@code limit_req}. nginx blocks brute connection
 * floods cheaply; this filter adds the per-endpoint granularity nginx can't
 * easily express (a {@code POST /runs} flood is far more expensive than a
 * {@code /timeseries} read, so they get different buckets).
 *
 * <h2>Off by default in {@code local}</h2>
 * Gated on {@code security.rateLimit.enabled} (default {@code false}; the
 * {@code cloud} profile flips it on). The IDE-on-loopback workflow is
 * therefore untouched, while a local dev can set
 * {@code SECURITY_RATE_LIMIT_ENABLED=true} to exercise the rejection path.
 *
 * <h2>Filter ordering</h2>
 * {@code HIGHEST_PRECEDENCE + 30} — runs <em>after</em> {@code MdcEnrichmentFilter}
 * (+10, populates MDC) and {@code AccessLogFilter} (+20, wraps the chain), so a
 * rejected request still emits an access-log line at status 429 carrying the
 * operator's trace/run ids. Only the critical {@code /api/v1/**} surface is
 * limited ({@link #shouldNotFilter}); actuator/swagger/static are exempt.
 *
 * <h2>Bounded memory</h2>
 * Buckets live in an access-ordered LRU map capped at
 * {@code maxTrackedClients}: once full, the least-recently-seen key is evicted,
 * so an attacker rotating source IPs can't grow the map without bound. An
 * evicted client that returns simply gets a fresh (full) bucket — harmless,
 * since an idle client would have refilled anyway. Dependency-free on purpose
 * (no Bucket4j / Caffeine): the token bucket is a ~30-line primitive we own and
 * unit-test, which also keeps the S-14 dependency surface clean.
 *
 * <h2>Client IP + the spoofing caveat</h2>
 * Keyed on the direct peer ({@code getRemoteAddr()}) by default. Behind a
 * trusted LB the operator sets {@code security.rateLimit.trustForwardedFor=true}
 * to key on the leftmost {@code X-Forwarded-For} hop instead — only do this
 * when the LB overwrites the header, or a client can forge it and evade (or
 * poison) another client's bucket.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * Endpoint classes and their per-IP limits (security S-1).
     * Blob endpoints are intentionally absent — nginx proxies
     * {@code /api/v1/blob} straight to document-service, so the global
     * orchestrator never sees them; their limit lives in nginx.
     */
    enum Category {
        RUNS_LAUNCH(5, 10),    // POST /api/v1/runs — expensive (claims a fleet)
        TIMESERIES(30, 100),   // GET  /api/v1/runs/{id}/timeseries — heavy read
        OTHER(50, 200);        // everything else under /api/v1
        final double ratePerSec;
        final long burst;
        Category(double ratePerSec, long burst) { this.ratePerSec = ratePerSec; this.burst = burst; }
    }

    /**
     * SLIMDOWN SL-E (D-4): the `security.ratelimit.exceeded` counter was the
     * sole signal of a rejection storm (the S-0 abuse signal), so it became a
     * log line. Throttled to one WARN per category per interval with a
     * suppressed count — an attack must not be able to flood the log through
     * the very filter that exists to stop floods.
     */
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final Map<String, TokenBucket> buckets;
    private final Map<Category, AtomicLong> lastWarnNanos = new EnumMap<>(Category.class);
    private final Map<Category, AtomicLong> pendingRejections = new EnumMap<>(Category.class);

    public RateLimitFilter(
            @Value("${security.rateLimit.enabled:false}") boolean enabled,
            @Value("${security.rateLimit.trustForwardedFor:false}") boolean trustForwardedFor,
            @Value("${security.rateLimit.maxTrackedClients:100000}") int maxTrackedClients) {
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        // Access-ordered LRU: removeEldestEntry evicts the least-recently-seen
        // key once the cap is hit. synchronizedMap because access-order
        // LinkedHashMap mutates on get(); the hot-path critical section is a
        // single map op, so the lock is negligible at this service's request rate.
        this.buckets = Collections.synchronizedMap(
                new LinkedHashMap<>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, TokenBucket> eldest) {
                        return size() > maxTrackedClients;
                    }
                });
        for (Category c : Category.values()) {
            lastWarnNanos.put(c, new AtomicLong(0L));
            pendingRejections.put(c, new AtomicLong(0L));
        }
        if (enabled) {
            log.info("SECURITY S-1 rate limiting ENABLED (trustForwardedFor={}, maxTrackedClients={})",
                    trustForwardedFor, maxTrackedClients);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !CriticalPaths.isCritical(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Category category = classify(request);
        String key = category.name() + '|' + clientIp(request);
        long now = System.nanoTime();
        // get-or-create under the map's monitor (compound op on a synchronizedMap).
        TokenBucket bucket;
        synchronized (buckets) {
            bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new TokenBucket(category.burst, category.ratePerSec, now);
                buckets.put(key, bucket);
            }
        }

        if (bucket.tryConsume(now)) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = bucket.retryAfterSeconds(now);
        logRejection(category, clientIp(request), now);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests; retry after "
                        + retryAfter + "s.\"}");
    }

    /**
     * One WARN per category per {@link #WARN_INTERVAL_NANOS}, carrying how
     * many rejections were folded into it. The CAS on lastWarnNanos elects a
     * single warning thread; losers just leave their rejection in the pending
     * count for the next winner to report.
     */
    private void logRejection(Category category, String clientIp, long now) {
        long pending = pendingRejections.get(category).incrementAndGet();
        AtomicLong last = lastWarnNanos.get(category);
        long prev = last.get();
        if (now - prev >= WARN_INTERVAL_NANOS && last.compareAndSet(prev, now)) {
            pendingRejections.get(category).addAndGet(-pending);
            log.warn("RATE_LIMITED: {} request(s) rejected on {} since last report (latest clientIp={})",
                    pending, category, clientIp);
        }
    }

    private static Category classify(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && "/api/v1/runs".equals(path)) {
            return Category.RUNS_LAUNCH;
        }
        if ("GET".equals(request.getMethod())
                && path.startsWith("/api/v1/runs/") && path.endsWith("/timeseries")) {
            return Category.TIMESERIES;
        }
        return Category.OTHER;
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
