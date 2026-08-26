package com.perf.orchestrator.http;

/**
 * Reads the live readiness signals the {@code /api/v1/ready} endpoint returns.
 *
 * <p>Decoupled from the controller so the real reachability check (driven by
 * {@code INGEST_HEALTH_CHECK_INTERVAL_MS}) can evolve without touching the
 * HTTP wiring.
 *
 * <p>Per {@code orchestratorPlan.md}, readiness is decoupled from test
 * lifecycle — {@code testState} is informational and never causes a 503.
 * The controller decides 200 vs 503 from {@link Snapshot#ingestReachable()}
 * and {@link Snapshot#diskFreeBytes()}, not from {@code testState}.
 */
@FunctionalInterface
public interface ReadinessProbe {

    Snapshot snapshot();

    record Snapshot(boolean ingestReachable, long diskFreeBytes, String testState, String reason) {

        /** Overall verdict: any non-null {@code reason} means DOWN. */
        public boolean isUp() { return reason == null; }

        public static Snapshot up(long diskFreeBytes, String testState) {
            return new Snapshot(true, diskFreeBytes, testState, null);
        }

        /** DOWN because the metrics-consumer is unreachable — flips the {@code ingestReachable} flag. */
        public static Snapshot down(String reason, long diskFreeBytes, String testState) {
            return new Snapshot(false, diskFreeBytes, testState, reason);
        }

        /**
         * DOWN for a non-ingest reason (e.g. disk pressure). The
         * {@code ingestReachable} flag stays {@code true} so operators
         * reading the JSON see the actual consumer state, not a false
         * negative driven by a sibling failure mode.
         */
        public static Snapshot downIngestUp(String reason, long diskFreeBytes, String testState) {
            return new Snapshot(true, diskFreeBytes, testState, reason);
        }
    }

    /** Fixed-UP stub for tests that don't exercise readiness. */
    static ReadinessProbe alwaysHealthy(long diskFreeBytes, String testState) {
        Snapshot snap = Snapshot.up(diskFreeBytes, testState);
        return () -> snap;
    }
}
