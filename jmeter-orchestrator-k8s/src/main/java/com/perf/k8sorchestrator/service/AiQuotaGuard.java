package com.perf.k8sorchestrator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * AI-0 — the cost guardrail. An in-memory counter of AI
 * invocations that resets at UTC midnight; once the daily cap trips,
 * {@link #acquire()} throws and the controller maps it to 429
 * {@code AI_QUOTA_EXCEEDED}.
 *
 * <p>The counter is per-instance, matching the doc's v1 decision: a multi-pod
 * deployment gets {@code cap × instanceCount} effective headroom. That is
 * acceptable for v1 — the cap is a runaway-cost backstop, not a billing meter.
 * A shared (Redis-backed) counter is the cloud follow-up if precise org-wide
 * limiting is needed.
 *
 * <p>Only actual Claude calls acquire — cache hits never do, so re-loading a
 * cached terminal-run summary is free and uncapped.
 */
@Service
public class AiQuotaGuard {

    private final int cap;

    // Guarded by `this`. The day this counter belongs to (UTC) + the count so far.
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private int count = 0;

    public AiQuotaGuard(@Value("${k8sOrchestrator.ai.dailyInvocationCap:200}") int cap) {
        this.cap = cap;
    }

    /**
     * Reserve one invocation against today's budget. Rolls the counter over at
     * UTC midnight before checking.
     *
     * @throws AiQuotaExceededException when the daily cap has already been reached.
     */
    public synchronized void acquire() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            count = 0;
        }
        if (count >= cap) {
            throw new AiQuotaExceededException(
                    "daily AI invocation cap of " + cap + " reached; resets at UTC midnight");
        }
        count++;
    }

    /** The configured daily cap (surfaced for tests + diagnostics). */
    public int cap() {
        return cap;
    }

    /** Daily invocation cap reached — controller maps this to 429 {@code AI_QUOTA_EXCEEDED}. */
    public static class AiQuotaExceededException extends RuntimeException {
        public AiQuotaExceededException(String message) { super(message); }
    }
}
