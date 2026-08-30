package com.perf.metricsconsumer.jdbc;

/**
 * Where one application group's rows go — the {@code GROUP_REGISTRY} row the
 * consumer routes {@code ?groupId=} through.
 *
 * @param groupId      the producer-facing key (`cps`)
 * @param prefix       {@code TABLE_PREFIX} — written into the dimensions' {@code GROUP_ID} column (`CPS`)
 * @param metricsTable the hot fact table the rows are inserted into (`CPS_METRICS`)
 * @param classifyFn   the label classifier function, or null when the group has none
 */
public record GroupTarget(String groupId, String prefix, String metricsTable, String classifyFn) { }
