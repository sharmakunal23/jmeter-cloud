package com.perf.k8sorchestrator.repo;

import com.perf.k8sorchestrator.domain.CronJob;
import com.perf.k8sorchestrator.domain.CronJobKind;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access for {@code globalOrchestrator.cronJob} (Flyway V20).
 * Mirrors {@link RunRepository}'s conventions: the run-state JdbcTemplate,
 * inline-lambda RowMapper, quoted-camelCase SQL.
 *
 * <p>The claim path ({@link #findDueForUpdate}) uses {@code FOR UPDATE SKIP
 * LOCKED} — it MUST be called inside a transaction (the caller,
 * {@code CronFireService.claimDue}, is {@code @Transactional}) or the row lock
 * is released immediately and the HA guarantee is lost.
 */
@Repository
public class CronJobRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<CronJob> rowMapper = CronJobRepository::mapRow;

    public CronJobRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "\"cronJobId\",\"name\",\"applicationName\",\"templateBlobId\",\"cronExpression\","
            + "\"timeZone\",\"enabled\",\"createdBy\",\"createdAt\",\"lastFiredAt\","
            + "\"lastFiredRunId\",\"lastFireStatus\",\"nextFireAt\",\"claimedAt\","
            // Phase C — kind/region. kind is NOT NULL (V22 default LAUNCH_RUN),
            // region is nullable (only set for DRAIN_REGION/PROVISION_REGION).
            // Phase E — recipients (report kinds only).
            // V25 — customSubject/customIntro (optional, report kinds only).
            + "\"kind\",\"region\",\"recipients\",\"customSubject\",\"customIntro\"";

    private static CronJob mapRow(ResultSet rs, int n) throws SQLException {
        return new CronJob(
                rs.getString("cronJobId"),
                rs.getString("name"),
                rs.getString("applicationName"),
                rs.getString("templateBlobId"),
                rs.getString("cronExpression"),
                rs.getString("timeZone"),
                rs.getBoolean("enabled"),
                rs.getString("createdBy"),
                instant(rs, "createdAt"),
                instant(rs, "lastFiredAt"),
                rs.getString("lastFiredRunId"),
                rs.getString("lastFireStatus"),
                instant(rs, "nextFireAt"),
                instant(rs, "claimedAt"),
                CronJobKind.valueOf(rs.getString("kind")),
                rs.getString("region"),
                rs.getString("recipients"),
                rs.getString("customSubject"),
                rs.getString("customIntro"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    /** Insert a new schedule. Relies on the UNIQUE(applicationName,name) constraint
     *  to surface duplicates as {@code DuplicateKeyException} (controller → 409). */
    public CronJob insert(CronJob c) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"cronJob\" (" + COLS + ") "
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
                "SELECT " + COLS + " FROM \"globalOrchestrator\".\"cronJob\" WHERE \"cronJobId\"=?",
                rowMapper, cronJobId);
        return rows.stream().findFirst();
    }

    /** All schedules, optionally filtered by application, newest-fire-first
     *  (NULLs — disabled — last). */
    public List<CronJob> findAll(String applicationFilter) {
        if (applicationFilter == null || applicationFilter.isBlank()) {
            return jdbc.query(
                    "SELECT " + COLS + " FROM \"globalOrchestrator\".\"cronJob\" "
                    + "ORDER BY \"nextFireAt\" ASC NULLS LAST, \"createdAt\" DESC",
                    rowMapper);
        }
        return jdbc.query(
                "SELECT " + COLS + " FROM \"globalOrchestrator\".\"cronJob\" "
                + "WHERE \"applicationName\"=? "
                + "ORDER BY \"nextFireAt\" ASC NULLS LAST, \"createdAt\" DESC",
                rowMapper, applicationFilter.trim());
    }

    /**
     * The HA claim query — enabled rows whose {@code nextFireAt} is due, locked
     * with {@code FOR UPDATE SKIP LOCKED} so a sibling replica's concurrent
     * sweep skips already-claimed rows. MUST run inside a transaction.
     */
    public List<CronJob> findDueForUpdate(Instant now, int limit) {
        return jdbc.query(
                "SELECT " + COLS + " FROM \"globalOrchestrator\".\"cronJob\" "
                + "WHERE \"enabled\"=true AND \"nextFireAt\" IS NOT NULL AND \"nextFireAt\" <= ? "
                + "ORDER BY \"nextFireAt\" ASC "
                + "LIMIT ? "
                + "FOR UPDATE SKIP LOCKED",
                rowMapper, ts(now), limit);
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
                "UPDATE \"globalOrchestrator\".\"cronJob\" "
                + "SET \"nextFireAt\"=?, \"claimedAt\"=? WHERE \"cronJobId\"=?",
                ts(nextFireAt), ts(claimedAt), cronJobId);
    }

    /** Record a fire's outcome. Does NOT touch {@code nextFireAt} (the claim
     *  already advanced it; a manual fireNow leaves the schedule untouched).
     *  Clears {@code claimedAt} — the in-flight marker. */
    public void recordFire(String cronJobId, Instant firedAt, String runId, String outcomeName) {
        jdbc.update(
                "UPDATE \"globalOrchestrator\".\"cronJob\" "
                + "SET \"lastFiredAt\"=?, \"lastFiredRunId\"=?, \"lastFireStatus\"=?, \"claimedAt\"=NULL "
                + "WHERE \"cronJobId\"=?",
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
                "UPDATE \"globalOrchestrator\".\"cronJob\" "
                + "SET \"name\"=?, \"applicationName\"=?, \"templateBlobId\"=?, "
                + "\"cronExpression\"=?, \"timeZone\"=?, \"nextFireAt\"=?, "
                + "\"kind\"=?, \"region\"=?, \"recipients\"=?, "
                + "\"customSubject\"=?, \"customIntro\"=? "
                + "WHERE \"cronJobId\"=?",
                name, applicationName, templateBlobId, cronExpression, timeZone,
                ts(nextFireAt), kind.name(), region, recipients,
                customSubject, customIntro, cronJobId);
    }

    /** Move {@code nextFireAt} to a new slot without touching enabled/last-fire
     *  state — used by "skip next" (advance one occurrence). Clears
     *  {@code claimedAt} so a stale in-flight marker can't block the new slot. */
    public void setNextFireAt(String cronJobId, Instant nextFireAt) {
        jdbc.update(
                "UPDATE \"globalOrchestrator\".\"cronJob\" "
                + "SET \"nextFireAt\"=?, \"claimedAt\"=NULL WHERE \"cronJobId\"=?",
                ts(nextFireAt), cronJobId);
    }

    /** Enable / disable. Disabling clears {@code nextFireAt} (and the partial
     *  index) so the sweep can't see the row; enabling sets the freshly-computed
     *  next slot. */
    public void setEnabled(String cronJobId, boolean enabled, Instant nextFireAt) {
        jdbc.update(
                "UPDATE \"globalOrchestrator\".\"cronJob\" "
                + "SET \"enabled\"=?, \"nextFireAt\"=?, \"claimedAt\"=NULL WHERE \"cronJobId\"=?",
                enabled, ts(nextFireAt), cronJobId);
    }

    public int delete(String cronJobId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"cronJob\" WHERE \"cronJobId\"=?", cronJobId);
    }
}
