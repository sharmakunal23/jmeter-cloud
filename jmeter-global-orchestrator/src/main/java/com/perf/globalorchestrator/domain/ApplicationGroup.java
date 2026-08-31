package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * A team's set of applications. {@code groupId} is what every worker sends as
 * {@code ?groupId=} on its metrics POSTs and, upper-cased, the prefix of the
 * group's own fact tables in the metrics schema ({@code cps} →
 * {@code CPS_METRICS}, {@code CPS_METRICS_H}); it must name a row of
 * {@code GROUP_REGISTRY} there. The group also owns the worker pool
 * ({@code groupCapacity}, {@code pod}) and the pool's recycle policy
 * (GROUP-CAPACITY, 2026-08-30). Persisted in
 * {@code ORCH_APPLICATION_GROUP}.
 *
 * @param grafanaLiveUrl    the group's live Grafana dashboard (reads
 *                          {@code <P>_METRICS}); the UI's "Open in Grafana"
 *                          for every application in the group
 * @param grafanaHistoryUrl the history dashboard (reads {@code <P>_METRICS_H});
 *                          optional — the UI falls back to the live URL
 * @param hotDays           days the live dashboard covers (the group's hot
 *                          retention); a run older than this opens history
 * @param recyclePolicy     when the group's workers are recycled; {@code REUSE} by default
 * @param maxRunsPerPod     the MAX_RUNS / BOTH threshold; null otherwise
 * @param podMaxAgeHours    the MAX_AGE / BOTH threshold; null otherwise
 * @param alwaysOn          DRAIN_REGION jobs skip this group's workers
 * @param teamName          the team that owns the group; display only
 * @param notifyTo          default recipients a workflow's email nodes inherit
 *                          when they name none of their own — stored
 *                          comma-separated, exposed as a list
 * @param notifyCc          default Cc, same rule
 * @param notifyBcc         default Bcc, same rule
 * @param applicationCount  VISIBLE (non-archived) applications in the group —
 *                          the display count; archived rows still hold the FK
 *                          and block group deletion —
 *                          hydrated on reads, {@code null} when not requested
 * @param capacity          the group's per-region budget — hydrated on reads,
 *                          {@code null} when not requested
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationGroup(
        String groupId,
        String name,
        String description,
        String grafanaLiveUrl,
        String grafanaHistoryUrl,
        Integer hotDays,
        RecyclePolicy recyclePolicy,
        Integer maxRunsPerPod,
        Integer podMaxAgeHours,
        boolean alwaysOn,
        String teamName,
        List<String> notifyTo,
        List<String> notifyCc,
        List<String> notifyBcc,
        Instant createdAt,
        Integer applicationCount,
        List<GroupCapacity> capacity) {

    public static final int DEFAULT_HOT_DAYS = 7;

    public ApplicationGroup {
        recyclePolicy = recyclePolicy == null ? RecyclePolicy.REUSE : recyclePolicy;
        notifyTo  = notifyTo  == null ? List.of() : List.copyOf(notifyTo);
        notifyCc  = notifyCc  == null ? List.of() : List.copyOf(notifyCc);
        notifyBcc = notifyBcc == null ? List.of() : List.copyOf(notifyBcc);
    }

    /** Pre-WORKFLOWS callers (tests, legacy paths): no team, no notification defaults. */
    public ApplicationGroup(String groupId, String name, String description, String grafanaLiveUrl,
                            String grafanaHistoryUrl, Integer hotDays, RecyclePolicy recyclePolicy,
                            Integer maxRunsPerPod, Integer podMaxAgeHours, boolean alwaysOn,
                            Instant createdAt, Integer applicationCount, List<GroupCapacity> capacity) {
        this(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays, recyclePolicy,
                maxRunsPerPod, podMaxAgeHours, alwaysOn, null, List.of(), List.of(), List.of(),
                createdAt, applicationCount, capacity);
    }

    /** No dashboards, the default hot window, the default policy. */
    public ApplicationGroup(String groupId, String name, String description,
                            Instant createdAt, Integer applicationCount) {
        this(groupId, name, description, null, null, DEFAULT_HOT_DAYS, RecyclePolicy.REUSE, null, null, false,
                createdAt, applicationCount, null);
    }

    /** Dashboards and hot window, the default policy. */
    public ApplicationGroup(String groupId, String name, String description, String grafanaLiveUrl,
                            String grafanaHistoryUrl, Integer hotDays, Instant createdAt, Integer applicationCount) {
        this(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays, RecyclePolicy.REUSE, null, null,
                false, createdAt, applicationCount, null);
    }

    public ApplicationGroup withApplicationCount(int count) {
        return new ApplicationGroup(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays,
                recyclePolicy, maxRunsPerPod, podMaxAgeHours, alwaysOn, teamName, notifyTo, notifyCc,
                notifyBcc, createdAt, count, capacity);
    }

    public ApplicationGroup withCapacity(List<GroupCapacity> rows) {
        return new ApplicationGroup(groupId, name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays,
                recyclePolicy, maxRunsPerPod, podMaxAgeHours, alwaysOn, teamName, notifyTo, notifyCc,
                notifyBcc, createdAt, applicationCount, rows);
    }
}
