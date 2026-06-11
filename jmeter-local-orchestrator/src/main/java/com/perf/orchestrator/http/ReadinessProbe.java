package com.perf.orchestrator.http;

/**
 * Reads the live readiness signals the {@code /api/v1/ready} endpoint returns.
 *
 * <p>Decoupled from the controller so step 8 can swap in the real Kafka
 * health check (driven by {@code KAFKA_HEALTH_CHECK_INTERVAL_MS}) without
 * touching the HTTP wiring. Step 4 ships the placeholder
 * {@link #alwaysHealthy()} that reports kafka-reachable=true and a real
 * disk-free reading, since no Kafka client exists yet.
 *
 * <p>Per {@code ORCHESTRATOR-PLAN.md}, readiness is decoupled from test
 * lifecycle — {@code testState} is informational and never causes a 503.
 * The controller decides 200 vs 503 from {@link Snapshot#kafkaReachable()}
 * and {@link Snapshot#diskFreeBytes()}, not from {@code testState}.
 */
@FunctionalInterface
public interface ReadinessProbe {

    Snapshot snapshot();

    record Snapshot(boolean kafkaReachable, long diskFreeBytes, String testState, String reason) {

        /** Overall verdict: any non-null {@code reason} means DOWN. */
        public boolean isUp() { return reason == null; }

        public static Snapshot up(long diskFreeBytes, String testState) {
            return new Snapshot(true, diskFreeBytes, testState, null);
        }

        /** DOWN because Kafka is unreachable — flips the {@code kafkaReachable} flag. */
        public static Snapshot down(String reason, long diskFreeBytes, String testState) {
            return new Snapshot(false, diskFreeBytes, testState, reason);
        }

        /**
         * DOWN for a non-Kafka reason (e.g. disk pressure). The
         * {@code kafkaReachable} flag stays {@code true} so operators
         * reading the JSON see the actual Kafka state, not a false negative
         * driven by a sibling failure mode.
         */
        public static Snapshot downKafkaUp(String reason, long diskFreeBytes, String testState) {
            return new Snapshot(true, diskFreeBytes, testState, reason);
        }
    }

    /**
     * Step 4 placeholder — assumes Kafka reachable. Replaced in step 8 by
     * a real probe that pings Kafka on the configured interval.
     */
    static ReadinessProbe alwaysHealthy(long diskFreeBytes, String testState) {
        Snapshot snap = Snapshot.up(diskFreeBytes, testState);
        return () -> snap;
    }
}
