package com.perf.metricsconsumer.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Owns the {@code metrics."workerMetric"} weekly-partition runway, extending it
 * at boot and again on a daily cron so it can never lapse — when it was a
 * manual responsibility it did lapse, and ingest failed with missing-partition
 * errors until an operator created them by hand.
 *
 * <p>Retention is enforced by partition DROP, which is instant and creates no
 * vacuum debt. The rollup tables are swept in the same pass, but they are
 * unpartitioned, so for them retention really is a DELETE.
 *
 * <p><b>Replica-safe:</b> the whole pass runs in one transaction that first
 * takes {@code pg_try_advisory_xact_lock}, so a second replica firing at the
 * same moment skips instead of racing. The lock is transaction-scoped and
 * cannot leak on crash.
 *
 * <p>The SQL functions are {@code SECURITY DEFINER} with EXECUTE granted to
 * {@code metricsWriter}, so this works whether the consumer connects as the
 * owner (local dev) or as the unprivileged app user (cloud).
 *
 * <p>Failures are logged at ERROR and swallowed: the runway normally has weeks
 * of slack, so a transient DB hiccup must not kill an otherwise-healthy
 * consumer, and the next trigger retries.
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionMaintenanceJob.class);

    /**
     * Advisory-lock key shared by every consumer replica — the value is
     * arbitrary (ASCII {@code "mPartMnt"}) but must be identical across
     * instances and unused by any other advisory-lock caller on the
     * metrics database.
     */
    public static final long ADVISORY_LOCK_KEY = 0x6D506172744D6E74L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int weeksAhead;
    private final int retentionWeeks;

    public PartitionMaintenanceJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            @Value("${metricsConsumer.partitionMaintenance.weeksAhead:8}") int weeksAhead,
            @Value("${metricsConsumer.partitionMaintenance.retentionWeeks:52}") int retentionWeeks) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.weeksAhead = weeksAhead;
        this.retentionWeeks = retentionWeeks;
    }

    /** Boot-time heal — see class javadoc. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runGuarded("startup");
    }

    /** Daily runway extension + retention sweep. */
    @Scheduled(cron = "${metricsConsumer.partitionMaintenance.cron:0 17 3 * * *}", zone = "UTC")
    public void onSchedule() {
        runGuarded("schedule");
    }

    private void runGuarded(String trigger) {
        try {
            runMaintenance();
        } catch (RuntimeException e) {
            LOG.error("Partition maintenance failed (trigger={}) — ingest will hit "
                    + "missing-partition errors if the runway is exhausted before the next attempt",
                    trigger, e);
        }
    }

    /**
     * One maintenance pass. Public so the IT can drive it directly; the
     * returned result says what actually happened.
     */
    public MaintenanceResult runMaintenance() {
        MaintenanceResult result = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return MaintenanceResult.SKIPPED;
            }
            List<String> ensured = jdbc.queryForList(
                    "SELECT metrics.\"ensureUpcomingPartitions\"(?)", String.class, weeksAhead);
            List<String> dropped = jdbc.queryForList(
                    "SELECT metrics.\"dropOldPartitions\"(?)", String.class, retentionWeeks);
            // The rollup tables are NOT partitioned, so partition DROP does not
            // age them out. Without this sweep they would be the one thing in the
            // metrics DB that grows forever. They share the raw retention window
            // for now; splitting them (raw days, rollups a year) is a later call.
            Long rollupRows = jdbc.queryForObject(
                    "SELECT metrics.\"dropOldRollups\"(?)", Long.class, retentionWeeks);
            return new MaintenanceResult(false, ensured, dropped,
                    rollupRows == null ? 0L : rollupRows);
        });
        if (result.skipped()) {
            LOG.info("Partition maintenance skipped — another instance holds the advisory lock");
        } else {
            LOG.info("Partition maintenance: runway ensured through +{}w ({} partitions, newest {}), "
                    + "dropped {} beyond {}w retention{}, removed {} rollup row(s)",
                    weeksAhead, result.ensuredPartitions().size(),
                    result.ensuredPartitions().isEmpty() ? "n/a"
                            : result.ensuredPartitions().get(result.ensuredPartitions().size() - 1),
                    result.droppedPartitions().size(), retentionWeeks,
                    result.droppedPartitions().isEmpty() ? "" : " " + result.droppedPartitions(),
                    result.droppedRollupRows());
        }
        return result;
    }

    /**
     * Outcome of one pass. {@code skipped} means another instance held the
     * advisory lock; the lists carry the partition names the SQL functions
     * reported (ensured includes already-existing runway weeks — the
     * helpers are idempotent). {@code droppedRollupRows} is the row count the
     * rollup retention sweep removed (rollups are unpartitioned, so retention
     * there is a DELETE rather than a partition drop).
     */
    public record MaintenanceResult(
            boolean skipped, List<String> ensuredPartitions, List<String> droppedPartitions,
            long droppedRollupRows) {

        static final MaintenanceResult SKIPPED =
                new MaintenanceResult(true, List.of(), List.of(), 0L);
    }
}
