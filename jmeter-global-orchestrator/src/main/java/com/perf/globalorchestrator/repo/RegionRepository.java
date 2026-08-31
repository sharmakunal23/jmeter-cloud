package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.Region;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@code ORCH_REGION} — the cluster registry
 * (CLUSTER-CAPACITY). Rows are written only through the registration flow
 * (validated first), so every read path may trust {@code regionalUrl}.
 * Deliberately uncached: the table is tiny, the probe reads it once per tick,
 * and a stale URL would mis-route provisioning.
 */
@Repository
public class RegionRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Region> rowMapper = (rs, n) -> new Region(
            rs.getString("REGION"),
            rs.getString("LABEL"),
            rs.getString("REGIONAL_URL"),
            rs.getInt("MAX_WORKERS"),
            OracleBind.instant(rs, "LAST_VALIDATED_AT"),
            OracleBind.instant(rs, "LAST_PROBE_AT"),
            rs.getString("LAST_PROBE_STATUS"),
            rs.getString("LAST_PROBE_DETAIL"),
            OracleBind.instant(rs, "CREATED_AT"),
            OracleBind.instant(rs, "UPDATED_AT"));

    public RegionRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Region> findAll() {
        return jdbc.query("SELECT * FROM ORCH_REGION ORDER BY REGION", rowMapper);
    }

    public Optional<Region> find(String region) {
        List<Region> rows = jdbc.query(
                "SELECT * FROM ORCH_REGION WHERE REGION = ?", rowMapper, region);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Exact match on the unique LABEL — the registration uniqueness pre-check. */
    public Optional<Region> findByLabel(String label) {
        List<Region> rows = jdbc.query(
                "SELECT * FROM ORCH_REGION WHERE LABEL = ?", rowMapper, label);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Exact match on the unique REGIONAL_URL — one regional serves one cluster. */
    public Optional<Region> findByRegionalUrl(String regionalUrl) {
        List<Region> rows = jdbc.query(
                "SELECT * FROM ORCH_REGION WHERE REGIONAL_URL = ?", rowMapper, regionalUrl);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Registration write — only after the validation chain passed, so the row is born validated. */
    public void insert(String region, String label, String regionalUrl, int maxWorkers) {
        jdbc.update(
                "INSERT INTO ORCH_REGION (REGION, LABEL, REGIONAL_URL, MAX_WORKERS, LAST_VALIDATED_AT) "
                + "VALUES (?, ?, ?, ?, SYSTIMESTAMP)",
                region, label, regionalUrl, maxWorkers);
    }

    /** @param revalidated true when the validation chain re-ran (URL change) — stamps LAST_VALIDATED_AT */
    public int update(String region, String label, String regionalUrl, int maxWorkers, boolean revalidated) {
        return jdbc.update(
                "UPDATE ORCH_REGION SET LABEL = ?, REGIONAL_URL = ?, MAX_WORKERS = ?, "
                + "LAST_VALIDATED_AT = CASE WHEN ? = 1 THEN SYSTIMESTAMP ELSE LAST_VALIDATED_AT END, "
                + "UPDATED_AT = SYSTIMESTAMP "
                + "WHERE REGION = ?",
                label, regionalUrl, maxWorkers, revalidated ? 1 : 0, region);
    }

    public int delete(String region) {
        return jdbc.update("DELETE FROM ORCH_REGION WHERE REGION = ?", region);
    }

    /**
     * Serialises reservation writes for one cluster: locks the row and returns
     * its {@code MAX_WORKERS}. Call inside the reservation transaction — the
     * lock is what closes the concurrent-oversubscription window.
     */
    public Optional<Integer> lockMaxWorkers(String region) {
        List<Integer> rows = jdbc.query(
                "SELECT MAX_WORKERS FROM ORCH_REGION WHERE REGION = ? FOR UPDATE",
                (rs, n) -> rs.getInt("MAX_WORKERS"), region);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Claims the cluster's probe slot for THIS replica — the cross-replica
     * "one probe at a time" guard (an in-JVM flag would let a second hub start
     * a second probe Pod against the same quota). A {@code RUNNING} row older
     * than {@code staleAfterSeconds} is re-claimable, so a replica that died
     * mid-probe cannot wedge the button forever.
     *
     * @return true when this replica won the claim
     */
    public boolean tryStartProbe(String region, long staleAfterSeconds) {
        return jdbc.update(
                "UPDATE ORCH_REGION SET LAST_PROBE_STATUS = 'RUNNING', LAST_PROBE_AT = SYSTIMESTAMP, "
                + "LAST_PROBE_DETAIL = NULL, UPDATED_AT = SYSTIMESTAMP "
                + "WHERE REGION = ? AND (LAST_PROBE_STATUS IS NULL OR LAST_PROBE_STATUS <> 'RUNNING' "
                + "                      OR LAST_PROBE_AT < SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND'))",
                region, staleAfterSeconds) == 1;
    }

    /** The on-demand test-provisioning probe's verdict. */
    public int recordProbe(String region, boolean pass, String detail) {
        return jdbc.update(
                "UPDATE ORCH_REGION SET LAST_PROBE_AT = SYSTIMESTAMP, LAST_PROBE_STATUS = ?, "
                + "LAST_PROBE_DETAIL = ?, UPDATED_AT = SYSTIMESTAMP WHERE REGION = ?",
                pass ? "PASS" : "FAIL", detail, region);
    }
}
