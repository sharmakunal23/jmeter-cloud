package com.perf.globalorchestrator.repo;

/**
 * Where one run's metrics live in the metrics schema: its application group's
 * fact tables and the run's surrogate key there. Resolved by
 * {@link com.perf.globalorchestrator.service.MetricsGroupResolver}; every read
 * and the purge carry it, and every table name in it has passed
 * {@link #IDENTIFIER} before being spliced into SQL.
 *
 * @param groupId      the group's registry key ({@code cps})
 * @param prefix       {@code TABLE_PREFIX} — the dimensions' {@code GROUP_ID} value ({@code CPS})
 * @param metricsTable the hot fact table ({@code CPS_METRICS})
 * @param historyTable the archived-day table ({@code CPS_METRICS_H}), or null when the group has none
 * @param runId        {@code RUN.RUN_ID} for this run in the group
 */
public record MetricsTarget(String groupId, String prefix, String metricsTable, String historyTable, long runId) {

    public static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,127}$");

    public MetricsTarget {
        requireIdentifier("METRICS_TABLE", metricsTable);
        if (historyTable != null && historyTable.isBlank()) historyTable = null;
        if (historyTable != null) requireIdentifier("METRICS_HIST_TABLE", historyTable);
    }

    static String requireIdentifier(String what, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException(what + " is not an identifier: " + value);
        }
        return value;
    }
}
