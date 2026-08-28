package com.perf.metricsconsumer.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.util.List;

/**
 * Ages out metrics by partition drop — raw tables after {@code retention.rawDays},
 * rollups after {@code retention.rollupWeeks} — at boot and on a daily cron,
 * through {@code metrics."metricsRetention"}. There is no partition runway to
 * extend: Oracle creates a week's partition on its first insert.
 *
 * <p>Replica-safe: the pass first takes the {@code "maintenanceLock"} row
 * {@code FOR UPDATE SKIP LOCKED}, so a second replica firing at the same moment
 * skips. The package drops partitions in an autonomous transaction, which is
 * what keeps that row lock held across the DDL's implicit commit.
 *
 * <p>Failures are logged at ERROR and swallowed: retention has weeks of slack
 * and a transient database hiccup must not kill an otherwise-healthy consumer.
 */
@Component
public class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    private static final String LOCK_SQL =
            "SELECT \"name\" FROM metrics.\"maintenanceLock\" WHERE \"name\" = 'retention' "
            + "FOR UPDATE SKIP LOCKED";
    private static final String DROP_RAW_CALL =
            "BEGIN metrics.\"metricsRetention\".\"dropOldRaw\"(?, ?); END;";
    private static final String DROP_ROLLUPS_CALL =
            "BEGIN metrics.\"metricsRetention\".\"dropOldRollups\"(?, ?, ?); END;";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int rawDays;
    private final int rollupWeeks;

    public RetentionJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            @Value("${metricsConsumer.retention.rawDays:30}") int rawDays,
            @Value("${metricsConsumer.retention.rollupWeeks:52}") int rollupWeeks) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.rawDays = rawDays;
        this.rollupWeeks = rollupWeeks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runGuarded("startup");
    }

    @Scheduled(cron = "${metricsConsumer.retention.cron:0 17 3 * * *}", zone = "UTC")
    public void onSchedule() {
        runGuarded("schedule");
    }

    private void runGuarded(String trigger) {
        try {
            runRetention();
        } catch (RuntimeException e) {
            LOG.error("Retention pass failed (trigger={}) — data past the window stays until the next attempt",
                    trigger, e);
        }
    }

    /** One pass; public so a test can drive it. */
    public RetentionResult runRetention() {
        RetentionResult result = tx.execute(status -> {
            List<String> locked = jdbc.queryForList(LOCK_SQL, String.class);
            if (locked.isEmpty()) {
                return RetentionResult.SKIPPED;
            }
            String rawDropped = jdbc.execute(DROP_RAW_CALL, (CallableStatementCallback<String>) cs -> {
                cs.setInt(1, rawDays);
                cs.registerOutParameter(2, Types.VARCHAR);
                cs.execute();
                return cs.getString(2);
            });
            Object[] rollups = jdbc.execute(DROP_ROLLUPS_CALL, (CallableStatementCallback<Object[]>) cs -> {
                cs.setInt(1, rollupWeeks);
                cs.registerOutParameter(2, Types.VARCHAR);
                cs.registerOutParameter(3, Types.NUMERIC);
                cs.execute();
                return new Object[] { cs.getString(2), cs.getLong(3) };
            });
            return new RetentionResult(false, split(rawDropped),
                    split((String) rollups[0]), (Long) rollups[1]);
        });
        if (result == null || result.skipped()) {
            LOG.info("Retention pass skipped — another instance holds the maintenance lock");
            return RetentionResult.SKIPPED;
        }
        LOG.info("Retention pass: raw kept {}d (dropped {}{}), rollups kept {}w (dropped {}{}, {} runLabel row(s) removed)",
                rawDays, result.rawPartitionsDropped().size(),
                result.rawPartitionsDropped().isEmpty() ? "" : " " + result.rawPartitionsDropped(),
                rollupWeeks, result.rollupPartitionsDropped().size(),
                result.rollupPartitionsDropped().isEmpty() ? "" : " " + result.rollupPartitionsDropped(),
                result.runLabelRowsDeleted());
        return result;
    }

    private static List<String> split(String commaJoined) {
        return commaJoined == null || commaJoined.isBlank() ? List.of() : List.of(commaJoined.split(","));
    }

    /** Outcome of one pass; {@code skipped} means another replica held the lock. */
    public record RetentionResult(boolean skipped, List<String> rawPartitionsDropped,
                                  List<String> rollupPartitionsDropped, long runLabelRowsDeleted) {
        static final RetentionResult SKIPPED = new RetentionResult(true, List.of(), List.of(), 0L);
    }
}
