package com.perf.k8sorchestrator.provision;

import com.perf.k8sorchestrator.domain.Application;
import com.perf.k8sorchestrator.domain.Pod;
import com.perf.k8sorchestrator.domain.PodState;
import com.perf.k8sorchestrator.domain.RecyclePolicy;
import com.perf.k8sorchestrator.provision.RecycleEvaluator.RecycleReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests for the WORKER-HYGIENE Phase D recycle decision table.
 * Covers each policy variant + the image-mismatch priority. All cases
 * use a fixed {@link Clock} so MAX_AGE comparisons are deterministic.
 */
@DisplayName("RecycleEvaluator — policy decision table")
class RecycleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-05-16T20:00:00Z");
    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecycleEvaluator evaluator = new RecycleEvaluator(fixedClock);

    private static final String CURRENT_IMAGE = "sha256:current";
    private static final String STALE_IMAGE   = "sha256:stale";

    @Test
    @DisplayName("REUSE — never recycles, regardless of runsServed or age")
    void reuseNeverRecycles() {
        Pod pod = pod("p1", 9999, daysAgo(60), CURRENT_IMAGE);
        Application app = app(RecyclePolicy.REUSE, null, null);
        assertThat(evaluator.decide(pod, app, CURRENT_IMAGE)).isEqualTo(RecycleReason.NONE);
    }

    @Test
    @DisplayName("Image mismatch — wins over policy thresholds (correctness > QoS)")
    void imageMismatchWinsOverPolicy() {
        // Provisioned a day ago — well past the multi-instance grace window.
        Pod pod = pod("p1", 0, daysAgo(1), STALE_IMAGE);
        // Even REUSE recycles on image mismatch.
        Application app = app(RecyclePolicy.REUSE, null, null);
        assertThat(evaluator.decide(pod, app, CURRENT_IMAGE))
                .isEqualTo(RecycleReason.IMAGE_MISMATCH);
    }

    @Test
    @DisplayName("Image mismatch — grace window: a freshly-provisioned pod is left alone "
            + "(MULTI-INSTANCE rollout ping-pong guard); null provisionedAt keeps old behavior")
    void imageMismatchGraceWindow() {
        Application app = app(RecyclePolicy.REUSE, null, null);

        // 2 min old — inside the 10-min default grace: not recycled this tick.
        assertThat(evaluator.decide(
                pod("p1", 0, NOW.minusSeconds(120), STALE_IMAGE), app, CURRENT_IMAGE))
                .as("inside grace").isEqualTo(RecycleReason.NONE);

        // Just past the 600 s default grace: recycles.
        assertThat(evaluator.decide(
                pod("p2", 0, NOW.minusSeconds(601), STALE_IMAGE), app, CURRENT_IMAGE))
                .as("past grace").isEqualTo(RecycleReason.IMAGE_MISMATCH);

        // Null provisionedAt (adopted row before back-fill) — grace can't be
        // evaluated; the mismatch recycle stays allowed.
        assertThat(evaluator.decide(
                pod("p3", 0, null, STALE_IMAGE), app, CURRENT_IMAGE))
                .as("null provisionedAt").isEqualTo(RecycleReason.IMAGE_MISMATCH);

        // Custom window via the test seam: 0 ms grace = pre-change behavior.
        RecycleEvaluator noGrace = new RecycleEvaluator(fixedClock, 0L);
        assertThat(noGrace.decide(
                pod("p4", 0, NOW, STALE_IMAGE), app, CURRENT_IMAGE))
                .as("zero grace window").isEqualTo(RecycleReason.IMAGE_MISMATCH);
    }

    @Test
    @DisplayName("Image mismatch — null currentImage or null pod.imageDigest → skip the check")
    void nullDigestsSkipImageCheck() {
        Pod podMissingDigest = pod("p1", 0, daysAgo(0), null);
        Application reuse = app(RecyclePolicy.REUSE, null, null);
        assertThat(evaluator.decide(podMissingDigest, reuse, CURRENT_IMAGE))
                .isEqualTo(RecycleReason.NONE);
        Pod podWithDigest = pod("p2", 0, daysAgo(0), CURRENT_IMAGE);
        assertThat(evaluator.decide(podWithDigest, reuse, null))
                .isEqualTo(RecycleReason.NONE);
    }

    @Test
    @DisplayName("MAX_RUNS — fires when runsServed >= maxRunsPerPod")
    void maxRunsThreshold() {
        Application app = app(RecyclePolicy.MAX_RUNS, 5, null);

        assertThat(evaluator.decide(pod("p1", 4, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("below threshold").isEqualTo(RecycleReason.NONE);
        assertThat(evaluator.decide(pod("p1", 5, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("at threshold").isEqualTo(RecycleReason.MAX_RUNS);
        assertThat(evaluator.decide(pod("p1", 9, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("past threshold").isEqualTo(RecycleReason.MAX_RUNS);
    }

    @Test
    @DisplayName("MAX_AGE — fires when age >= podMaxAgeHours")
    void maxAgeThreshold() {
        Application app = app(RecyclePolicy.MAX_AGE, null, 24);

        assertThat(evaluator.decide(pod("p1", 0, hoursAgo(23), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("below threshold").isEqualTo(RecycleReason.NONE);
        assertThat(evaluator.decide(pod("p1", 0, hoursAgo(24), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("at threshold").isEqualTo(RecycleReason.MAX_AGE);
        assertThat(evaluator.decide(pod("p1", 0, daysAgo(7), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("past threshold").isEqualTo(RecycleReason.MAX_AGE);
    }

    @Test
    @DisplayName("MAX_AGE — null provisionedAt is treated as 'cannot decide; no-op'")
    void maxAgeWithNullProvisionedAt() {
        Application app = app(RecyclePolicy.MAX_AGE, null, 24);
        Pod p = pod("p1", 0, null, CURRENT_IMAGE);
        assertThat(evaluator.decide(p, app, CURRENT_IMAGE)).isEqualTo(RecycleReason.NONE);
    }

    @Test
    @DisplayName("BOTH — MAX_RUNS hit reports MAX_RUNS; otherwise MAX_AGE; otherwise NONE")
    void bothPolicy() {
        Application app = app(RecyclePolicy.BOTH, 5, 24);

        assertThat(evaluator.decide(pod("p1", 1, hoursAgo(1), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("neither threshold tripped").isEqualTo(RecycleReason.NONE);
        assertThat(evaluator.decide(pod("p1", 5, hoursAgo(1), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("MAX_RUNS only").isEqualTo(RecycleReason.MAX_RUNS);
        assertThat(evaluator.decide(pod("p1", 1, hoursAgo(25), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("MAX_AGE only").isEqualTo(RecycleReason.MAX_AGE);
        assertThat(evaluator.decide(pod("p1", 9, hoursAgo(25), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("both tripped — MAX_RUNS reported first").isEqualTo(RecycleReason.MAX_RUNS);
    }

    @Test
    @DisplayName("EVERY_RUN — fires on first run served")
    void everyRunPolicy() {
        Application app = app(RecyclePolicy.EVERY_RUN, null, null);
        assertThat(evaluator.decide(pod("p1", 0, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("fresh pod (no runs) — not yet").isEqualTo(RecycleReason.NONE);
        assertThat(evaluator.decide(pod("p1", 1, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("one run served — recycle").isEqualTo(RecycleReason.EVERY_RUN);
    }

    @Test
    @DisplayName("DRAIN_AFTER_RUN — fires on first run served, same trigger as EVERY_RUN")
    void drainAfterRunPolicy() {
        Application app = app(RecyclePolicy.DRAIN_AFTER_RUN, null, null);
        assertThat(evaluator.decide(pod("p1", 0, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("fresh pod (no runs) — not yet").isEqualTo(RecycleReason.NONE);
        assertThat(evaluator.decide(pod("p1", 1, daysAgo(0), CURRENT_IMAGE), app, CURRENT_IMAGE))
                .as("one run served — drain").isEqualTo(RecycleReason.DRAIN_AFTER_RUN);
    }

    @Test
    @DisplayName("replacesPod() — DRAIN_AFTER_RUN is the only drain-without-replace reason")
    void replacesPodFlag() {
        assertThat(RecycleReason.DRAIN_AFTER_RUN.replacesPod()).isFalse();
        for (RecycleReason r : List.of(
                RecycleReason.EVERY_RUN, RecycleReason.MAX_RUNS,
                RecycleReason.MAX_AGE, RecycleReason.IMAGE_MISMATCH, RecycleReason.NONE)) {
            assertThat(r.replacesPod()).as("%s replaces", r).isTrue();
        }
    }

    @Test
    @DisplayName("Null application → NONE (defensive against a deleted-app race)")
    void nullApplicationIsNone() {
        Pod pod = pod("p1", 999, daysAgo(0), STALE_IMAGE);
        assertThat(evaluator.decide(pod, null, CURRENT_IMAGE)).isEqualTo(RecycleReason.NONE);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Pod pod(String id, long runsServed, Instant provisionedAt, String imageDigest) {
        return new Pod(id, "us-east", "http://" + id + ":8080",
                PodState.IDLE,
                NOW, NOW.minusSeconds(60), "appId",
                runsServed, imageDigest, provisionedAt,
                com.perf.k8sorchestrator.domain.PodSource.DYNAMIC);
    }

    private Application app(RecyclePolicy policy, Integer maxRuns, Integer maxAgeHours) {
        return new Application(
                "appId", "demo", null, null, List.of(),
                null, NOW, null, null, null,
                policy, maxRuns, maxAgeHours, /* alwaysOn */ false);
    }

    private Instant daysAgo(int days) {
        return NOW.minusSeconds(days * 24L * 3600L);
    }

    private Instant hoursAgo(int hours) {
        return NOW.minusSeconds(hours * 3600L);
    }
}
