package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * A team's set of applications. {@code groupId} is what every worker sends as
 * {@code ?groupId=} on its metrics POSTs and, upper-cased, the prefix of the
 * group's own fact tables in the metrics schema ({@code cps} →
 * {@code CPS_METRICS}, {@code CPS_METRICS_H}); it must name a row of
 * {@code GROUP_REGISTRY} there. Persisted in
 * {@code "globalOrchestrator"."applicationGroup"}.
 *
 * @param grafanaLiveUrl    the group's live Grafana dashboard (reads
 *                          {@code <P>_METRICS}); the UI's "Open in Grafana"
 *                          default for every application in the group
 * @param grafanaHistoryUrl the history dashboard (reads {@code <P>_METRICS_H});
 *                          optional — the UI falls back to the live URL
 * @param hotDays           days the live dashboard covers (the group's hot
 *                          retention); a run older than this opens history
 * @param applicationCount  applications (visible or archived) in the group —
 *                          hydrated on reads, {@code null} when not requested
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationGroup(
        String groupId,
        String name,
        String description,
        String grafanaLiveUrl,
        String grafanaHistoryUrl,
        Integer hotDays,
        Instant createdAt,
        Integer applicationCount) {

    public static final int DEFAULT_HOT_DAYS = 7;

    /** No dashboards, the default hot window. */
    public ApplicationGroup(String groupId, String name, String description,
                            Instant createdAt, Integer applicationCount) {
        this(groupId, name, description, null, null, DEFAULT_HOT_DAYS, createdAt, applicationCount);
    }

    public ApplicationGroup withApplicationCount(int count) {
        return new ApplicationGroup(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl,
                hotDays, createdAt, count);
    }
}
