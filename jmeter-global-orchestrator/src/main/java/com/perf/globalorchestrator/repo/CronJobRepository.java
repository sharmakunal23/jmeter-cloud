package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobKind;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access for {@code ORCH_CRON_JOB}.
 * Mirrors {@link RunRepository}'s conventions: the run-state JdbcTemplate,
 * inline-lambda RowMapper, quoted-camelCase SQL.
 *
 * <p>The claim path ({@link #findDueForUpdate}) goes through
 * {@code ORCH_CLAIMS.CLAIM_DUE_CRON_JOBS}, which locks the due
 * rows one at a time with {@code FOR UPDATE SKIP LOCKED} — it MUST be called
 * inside a transaction (the caller, {@code CronFireService.claimDue}, is
 * {@code @Transactional}) or the row locks are released immediately and the HA
 * guarantee is lost.
 */
@Repository
public class CronJobRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<CronJob> rowMapper = CronJobRepository::mapRow;

    public CronJobRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "CRON_JOB_ID,NAME,APPLICATION_NAME,TEMPLATE_BLOB_ID,CRON_EXPRESSION,"
            + "TIME_ZONE,ENABLED,CREATED_BY,CREATED_AT,LAST_FIRED_AT,"
            + "LAST_FIRED_RUN_ID,LAST_FIRE_STATUS,NEXT_FIRE_AT,CLAIMED_AT,"
            // kind is NOT NULL (default LAUNCH_RUN); region only for
            // DRAIN_REGION/PROVISION_REGION; recipients/customSubject/customIntro
            // for report kinds only.
            + "KIND,REGION,RECIPIENTS,CUSTOM_SUBJECT,CUSTOM_INTRO";

    private static CronJob mapRow(ResultSet rs, int n) throws SQLException {
        return new CronJob(
                rs.getString("CRON_JOB_ID"),
                rs.getString("NAME"),
                rs.getString("APPLICATION_NAME"),
                rs.getString("TEMPLATE_BLOB_ID"),
                rs.getString("CRON_EXPRESSION"),
                rs.getString("TIME_ZONE"),
                rs.getBoolean("ENABLED"),
                rs.getString("CREATED_BY"),
                instant(rs, "CREATED_AT"),
                instant(rs, "LAST_FIRED_AT"),
                rs.getString("LAST_FIRED_RUN_ID"),
                rs.getString("LAST_FIRE_STATUS"),
                instant(rs, "NEXT_FIRE_AT"),
                instant(rs, "CLAIMED_AT"),
                CronJobKind.valueOf(rs.getString("KIND")),
                rs.getString("REGION"),
                rs.getString("RECIPIENTS"),
                rs.getString("CUSTOM_SUBJECT"),
                rs.getString("CUSTOM_INTRO"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return OracleBind.instant(rs, col);
    }

    private static Object ts(Instant i) {
        return OracleBind.ts(i);
    }

    /** Insert a new schedule. Relies on the UNIQUE(applicationName,name) constraint
     *  to surface duplicates as {@code DuplicateKeyException} (controller → 409). */
    public CronJob insert(CronJob c) {
        jdbc.update(
                "INSERT INTO ORCH_CRON_JOB (" + COLS + ") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.cronJobId(), c.name(), c.applicationName(), c.templateBlobId(),
                c.cronExpression(), c.timeZone(), c.enabled(), c.createdBy(),
                ts(c.createdAt()), ts(c.lastFiredAt()), c.lastFiredRunId(),
                c.lastFireStatus(), ts(c.nextFireAt()), ts(c.claimedAt()),
                c.kind().name(), c.region(), c.recipients(),
                c.customSubject(), c.customIntro());
        return c;
    }

    public Optional<CronJob> findById(String cronJobId) {
        List<CronJob> rows = jdbc.query(
                "SELECT " + COLS + " FROM ORCH_CRON_JOB WHERE CRON_JOB_ID=?",
                rowMapper, cronJobId);
        return rows.stream().findFirst();
    }

    /** All schedules, optionally filtered by application, newest-fire-first
     *  (NULLs — disabled — last). */
    public List<CronJob> findAll(String applicationFilter) {
        if (applicationFilter == null || applicationFilter.isBlank()) {
            return jdbc.query(
                    "SELECT " + COLS + " FROM ORCH_CRON_JOB "
                    + "ORDER BY NEXT_FIRE_AT ASC NULLS LAST, CREATED_AT DESC",
                    rowMapper);
        }
        return jdbc.query(
                "SELECT " + COLS + " FROM ORCH_CRON_JOB "
                + "WHERE APPLICATION_NAME=? "
                + "ORDER BY NEXT_FIRE_AT ASC NULLS LAST, CREATED_AT DESC",
                rowMapper, applicationFilter.trim());
    }

    /**
     * The HA claim — up to {@code limit} enabled rows whose {@code nextFireAt} is
     * due, each locked {@code FOR UPDATE SKIP LOCKED} by the claims package so a
     * sibling replica's concurrent sweep skips already-claimed rows. MUST run
     * inside a transaction.
     */
    public List<CronJob> findDueForUpdate(Instant now, int limit) {
        return OracleBind.refCursor(jdbc,
                "BEGIN ORCH_CLAIMS.CLAIM_DUE_CRON_JOBS(?, ?, ?); END;",
                cs -> { cs.setObject(1, OracleBind.ts(now)); cs.setInt(2, limit); },
                3, rowMapper);
    }

    /**
     * Advance {@code nextFireAt} to the next future slot and stamp
     * {@code claimedAt}. Called for each claimed row inside the claim
     * transaction — advancing here (not after the fire) is what guarantees no
     * sibling replica re-selects the row, i.e. exactly-once per window even on
     * a mid-fire crash (we err toward not-double-firing).
     */
    public void reschedule(String cronJobId, Instant nextFireAt, Instant claimedAt) {
        jdbc.update(
                "UPDATE ORCH_CRON_JOB "
                + "SET NEXT_FIRE_AT=?, CLAIMED_AT=? WHERE CRON_JOB_ID=?",
                ts(nextFireAt), ts(claimedAt), cronJobId);
    }

    /** Record a fire's outcome. Does NOT touch {@code nextFireAt} (the claim
     *  already advanced it; a manual fireNow leaves the schedule untouched).
     *  Clears {@code claimedAt} — the in-flight marker. */
    public void recordFire(String cronJobId, Instant firedAt, String runId, String outcomeName) {
        jdbc.update(
                "UPDATE ORCH_CRON_JOB "
                + "SET LAST_FIRED_AT=?, LAST_FIRED_RUN_ID=?, LAST_FIRE_STATUS=?, CLAIMED_AT=NULL "
                + "WHERE CRON_JOB_ID=?",
                ts(firedAt), runId, outcomeName, cronJobId);
    }

    /** Edit the mutable fields of a schedule. Recompute {@code nextFireAt} in
     *  the controller before calling. Surfaces a UNIQUE violation as
     *  {@code DuplicateKeyException}. Phase C — kind/region are settable so an
     *  operator can convert a schedule between LAUNCH_RUN / DRAIN_REGION /
     *  PROVISION_REGION; the controller enforces the per-kind invariants. */
    public void update(String cronJobId, String name, String applicationName,
                       String templateBlobId, String cronExpression, String timeZone,
                       Instant nextFireAt, CronJobKind kind, String region, String recipients,
                       String customSubject, String customIntro) {
        jdbc.update(
                "UPDATE ORCH_CRON_JOB "
                + "SET NAME=?, APPLICATION_NAME=?, TEMPLATE_BLOB_ID=?, "
                + "CRON_EXPRESSION=?, TIME_ZONE=?, NEXT_FIRE_AT=?, "
                + "KIND=?, REGION=?, RECIPIENTS=?, "
                + "CUSTOM_SUBJECT=?, CUSTOM_INTRO=? "
                + "WHERE CRON_JOB_ID=?",
                name, applicationName, templateBlobId, cronExpression, timeZone,
                ts(nextFireAt), kind.name(), region, recipients,
                customSubject, customIntro, cronJobId);
    }

    /** Move {@code nextFireAt} to a new slot without touching enabled/last-fire
     *  state — used by "skip next" (advance one occurrence). Clears
     *  {@code claimedAt} so a stale in-flight marker can't block the new slot. */
    public void setNextFireAt(String cronJobId, Instant nextFireAt) {
        jdbc.update(
                "UPDATE ORCH_CRON_JOB "
                + "SET NEXT_FIRE_AT=?, CLAIMED_AT=NULL WHERE CRON_JOB_ID=?",
                ts(nextFireAt), cronJobId);
    }

    /** Enable / disable. Disabling clears {@code nextFireAt} so the sweep can't
     *  see the row; enabling sets the freshly-computed next slot. */
    public void setEnabled(String cronJobId, boolean enabled, Instant nextFireAt) {
        jdbc.update(
                "UPDATE ORCH_CRON_JOB "
                + "SET ENABLED=?, NEXT_FIRE_AT=?, CLAIMED_AT=NULL WHERE CRON_JOB_ID=?",
                enabled, ts(nextFireAt), cronJobId);
    }

    public int delete(String cronJobId) {
        return jdbc.update(
                "DELETE FROM ORCH_CRON_JOB WHERE CRON_JOB_ID=?", cronJobId);
    }
}
