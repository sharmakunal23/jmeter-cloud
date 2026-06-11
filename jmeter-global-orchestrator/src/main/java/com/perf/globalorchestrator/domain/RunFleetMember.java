package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * One row from {@code globalOrchestrator.runFleetMember}.
 *
 * <p><b>Track G (Step 31)</b> added {@code properties} — the JMeter
 * {@code -J} system properties this pod was launched with. Stored as
 * JSONB; surfaced on the run-detail page so an operator can audit
 * what each node ran with.
 *
 * <p><b>MID-TEST-SCALING Phase A</b> added {@code joinedAtSecond} —
 * NULL for original-fleet members, {@code >= 0} for mid-test scale-up
 * joiners (seconds since {@code run.startedAt}). Lets the UI render a
 * "joined +Xm" chip and lets per-second rollups distinguish members
 * live at second X from members that hadn't joined yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunFleetMember(
        String runId,
        String workerId,
        String region,
        MemberState state,
        String stateReason,
        Integer fanoutStatusCode,
        String podBaseUrl,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Map<String, String> properties,
        Long joinedAtSecond,
        /**
         * WORKER-HYGIENE Phase F2 — joined from {@code pod.runsServed} at
         * read-time. Null on INSERT (write path) and when the pod row is
         * gone (deleted-and-not-yet-cleaned member). The UI uses this to
         * flag a worker whose pod is near its recycle threshold.
         */
        Long runsServed) {

    public RunFleetMember {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** Pre-Step-31 convenience constructor (no properties, original-fleet). */
    public RunFleetMember(
            String runId, String workerId, String region,
            MemberState state, String stateReason, Integer fanoutStatusCode,
            String podBaseUrl, Instant createdAt, Instant startedAt, Instant completedAt) {
        this(runId, workerId, region, state, stateReason, fanoutStatusCode,
                podBaseUrl, createdAt, startedAt, completedAt, Map.of(), null, null);
    }

    /** Pre-MID-TEST-SCALING convenience constructor (with properties, original-fleet). */
    public RunFleetMember(
            String runId, String workerId, String region,
            MemberState state, String stateReason, Integer fanoutStatusCode,
            String podBaseUrl, Instant createdAt, Instant startedAt, Instant completedAt,
            Map<String, String> properties) {
        this(runId, workerId, region, state, stateReason, fanoutStatusCode,
                podBaseUrl, createdAt, startedAt, completedAt, properties, null, null);
    }

    /** Pre-Phase-F2 convenience constructor (with properties + joinedAtSecond, no runsServed). */
    public RunFleetMember(
            String runId, String workerId, String region,
            MemberState state, String stateReason, Integer fanoutStatusCode,
            String podBaseUrl, Instant createdAt, Instant startedAt, Instant completedAt,
            Map<String, String> properties, Long joinedAtSecond) {
        this(runId, workerId, region, state, stateReason, fanoutStatusCode,
                podBaseUrl, createdAt, startedAt, completedAt, properties, joinedAtSecond, null);
    }
}
