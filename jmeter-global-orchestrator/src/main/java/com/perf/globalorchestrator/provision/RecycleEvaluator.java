package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * WORKER-HYGIENE Phase D — pure decision logic. Given a pod, its bound
 * application, the current image digest, and "now", returns the first
 * {@link RecycleReason} that fires (NONE if none).
 *
 * <p>Image-mismatch takes priority over policy-driven triggers — a stale
 * image is a correctness concern (the pod might not understand a new
 * endpoint), while threshold triggers are quality-of-service. We never
 * want to lose an upgrade because some policy threshold happened to be
 * larger than the run rate.
 *
 * <p>The evaluator is stateless and pure (no DB calls, no I/O); side
 * effects live in {@link PodRecycler}. That split keeps the policy
 * table cheap to unit-test.
 */
@Component
public class RecycleEvaluator {

    private final Clock clock;

    /**
     * Production constructor — uses the system UTC clock. The test seam below
     * lets a unit test inject a {@link Clock#fixed} for deterministic MAX_AGE
     * comparisons. A Spring {@code @Bean Clock} would be cleaner, but adding
     * one to this module risks colliding with one a future caller wires; the
     * default-arg constructor keeps the choice local.
     */
    public RecycleEvaluator() {
        this(Clock.systemUTC());
    }

    /** Test seam. */
    public RecycleEvaluator(Clock clock) {
        this.clock = clock;
    }

    public RecycleReason decide(Pod pod, Application application, String currentImageDigest) {
        if (application == null) {
            // Defensive — caller should filter applicationId-null rows out.
            return RecycleReason.NONE;
        }
        // (1) Image-mismatch — highest priority. We need both digests to
        // compare; if either is null we can't decide so the check is a
        // no-op this tick (the reconciler back-fill will populate
        // pod.imageDigest eventually).
        if (currentImageDigest != null
                && pod.imageDigest() != null
                && !currentImageDigest.equals(pod.imageDigest())) {
            return RecycleReason.IMAGE_MISMATCH;
        }

        // (2) Policy-driven triggers.
        RecyclePolicy policy = application.recyclePolicy();
        return switch (policy) {
            case REUSE     -> RecycleReason.NONE;
            case EVERY_RUN -> pod.runsServed() >= 1 ? RecycleReason.EVERY_RUN : RecycleReason.NONE;
            case DRAIN_AFTER_RUN -> pod.runsServed() >= 1 ? RecycleReason.DRAIN_AFTER_RUN : RecycleReason.NONE;
            case MAX_RUNS  -> tripsMaxRuns(pod, application) ? RecycleReason.MAX_RUNS : RecycleReason.NONE;
            case MAX_AGE   -> tripsMaxAge(pod, application)  ? RecycleReason.MAX_AGE  : RecycleReason.NONE;
            case BOTH      -> {
                if (tripsMaxRuns(pod, application)) yield RecycleReason.MAX_RUNS;
                if (tripsMaxAge(pod, application))  yield RecycleReason.MAX_AGE;
                yield RecycleReason.NONE;
            }
        };
    }

    private boolean tripsMaxRuns(Pod pod, Application app) {
        Integer max = app.maxRunsPerPod();
        return max != null && pod.runsServed() >= max;
    }

    private boolean tripsMaxAge(Pod pod, Application app) {
        Integer hours = app.podMaxAgeHours();
        if (hours == null || pod.provisionedAt() == null) {
            // No threshold or no anchor — can't decide; treat as not-yet.
            return false;
        }
        Duration age = Duration.between(pod.provisionedAt(), Instant.now(clock));
        return age.compareTo(Duration.ofHours(hours)) >= 0;
    }

    /** Why a pod was selected for recycle. NONE means the pod is fine. */
    public enum RecycleReason {
        NONE, IMAGE_MISMATCH, MAX_RUNS, MAX_AGE, EVERY_RUN, DRAIN_AFTER_RUN;

        /**
         * Whether this reason should spin a replacement after draining.
         * {@link #DRAIN_AFTER_RUN} is the only drain-without-replace reason;
         * everything else is a 1-for-1 swap.
         */
        public boolean replacesPod() {
            return this != DRAIN_AFTER_RUN;
        }
    }
}
