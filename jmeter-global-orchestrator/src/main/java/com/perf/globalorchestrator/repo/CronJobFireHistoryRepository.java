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
 * Append-only access for {@code globalOrchestrator.cronJobFireHistory}. One row per fire attempt; never updated or deleted (the role
 * has only SELECT + INSERT, like {@code runEvent}).
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
                rs.getString("fireId"),
                rs.getString("cronJobId"),
                OracleBind.instant(rs, "firedAt"),
                rs.getString("outcome"),
                rs.getString("runId"),
                rs.getString("errorReason"));
    }

    public void insert(CronJobFire f) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"cronJobFireHistory\" "
                + "(\"fireId\",\"cronJobId\",\"firedAt\",\"outcome\",\"runId\",\"errorReason\") "
                + "VALUES (?,?,?,?,?,?)",
                f.fireId(), f.cronJobId(),
                OracleBind.ts(f.firedAt()),
                f.outcome(), f.runId(), OracleBind.text(f.errorReason(), OracleBind.TEXT_CHARS));
    }

    /** Newest-first fire history for one schedule, capped at {@code limit}. */
    public List<CronJobFire> findByCronJobId(String cronJobId, int limit) {
        return jdbc.query(
                "SELECT \"fireId\",\"cronJobId\",\"firedAt\",\"outcome\",\"runId\",\"errorReason\" "
                + "FROM \"globalOrchestrator\".\"cronJobFireHistory\" "
                + "WHERE \"cronJobId\"=? ORDER BY \"firedAt\" DESC FETCH FIRST ? ROWS ONLY",
                rowMapper, cronJobId, limit);
    }

    /** Count of all recorded fires for a schedule (used by tests / detail page). */
    public int countByCronJobId(String cronJobId) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"cronJobFireHistory\" WHERE \"cronJobId\"=?",
                Integer.class, cronJobId);
        return c == null ? 0 : c;
    }
}
