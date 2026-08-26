package com.perf.k8sorchestrator.security;

/**
 * Minimal continuous-refill token bucket — the per-(client, endpoint) unit of
 * the SECURITY S-1 rate-limit filter. Tokens refill linearly at
 * {@code refillPerSec} up to {@code capacity} (the burst ceiling); each allowed
 * request costs one token.
 *
 * <p>One instance per rate-limit key, held in a Caffeine cache. Access is
 * serialized with {@code synchronized} — correct under concurrency and cheap,
 * since contention is per-key (a single client hammering one endpoint) rather
 * than global. We deliberately own this ~30-line primitive instead of pulling
 * the (non-BOM-managed) Bucket4j dependency; see the pom comment on caffeine.
 *
 * <p>The caller samples {@link System#nanoTime()} once and threads it through
 * {@link #tryConsume} + {@link #retryAfterSeconds} so a single decision uses a
 * consistent clock reading.
 */
final class TokenBucket {

    private final long capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastNanos;

    TokenBucket(long capacity, double refillPerSec, long createdNanos) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.tokens = capacity;        // start full: a fresh client gets its whole burst
        this.lastNanos = createdNanos;
    }

    /** Try to take one token. Returns true (and decrements) when allowed. */
    synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Whole seconds until at least one token is available again (min 1 when empty). */
    synchronized long retryAfterSeconds(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) return 0;
        double seconds = (1.0 - tokens) / refillPerSec;
        return Math.max(1L, (long) Math.ceil(seconds));
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed <= 0) return;      // clock skew / same-instant — nothing to add
        tokens = Math.min(capacity, tokens + (elapsed / 1_000_000_000.0) * refillPerSec);
        lastNanos = nowNanos;
    }
}
