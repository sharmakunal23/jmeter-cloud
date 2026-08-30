package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.CronJobFire;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Append-only access for {@code ORCH_CRON_JOB_FIRE_HISTORY}. One row per fire attempt; never updated or deleted (the role
 * has only SELECT + INSERT, like {@code ORCH_RUN_EVENT}).
 */
@Repository
public class CronJobFireHistoryRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<CronJobFire> rowMapper = CronJobFireHistoryRepository::mapRow;

    public CronJobFireHistoryRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static CronJobFire mapRow(ResultSet rs, int n) throws SQLException {
        return new CronJobFire(
                rs.getString("FIRE_ID"),
                rs.getString("CRON_JOB_ID"),
                OracleBind.instant(rs, "FIRED_AT"),
                rs.getString("OUTCOME"),
                rs.getString("RUN_ID"),
                rs.getString("ERROR_REASON"));
    }

    public void insert(CronJobFire f) {
        jdbc.update(
                "INSERT INTO ORCH_CRON_JOB_FIRE_HISTORY "
                + "(FIRE_ID,CRON_JOB_ID,FIRED_AT,OUTCOME,RUN_ID,ERROR_REASON) "
                + "VALUES (?,?,?,?,?,?)",
                f.fireId(), f.cronJobId(),
                OracleBind.ts(f.firedAt()),
                f.outcome(), f.runId(), OracleBind.text(f.errorReason(), OracleBind.TEXT_CHARS));
    }

    /** Newest-first fire history for one schedule, capped at {@code limit}. */
    public List<CronJobFire> findByCronJobId(String cronJobId, int limit) {
        return jdbc.query(
                "SELECT FIRE_ID,CRON_JOB_ID,FIRED_AT,OUTCOME,RUN_ID,ERROR_REASON "
                + "FROM ORCH_CRON_JOB_FIRE_HISTORY "
                + "WHERE CRON_JOB_ID=? ORDER BY FIRED_AT DESC FETCH FIRST ? ROWS ONLY",
                rowMapper, cronJobId, limit);
    }

    /** Count of all recorded fires for a schedule (used by tests / detail page). */
    public int countByCronJobId(String cronJobId) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM ORCH_CRON_JOB_FIRE_HISTORY WHERE CRON_JOB_ID=?",
                Integer.class, cronJobId);
        return c == null ? 0 : c;
    }
}
