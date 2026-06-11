package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * HARD-DELETE / purge — append-only tombstone for
 * physical deletes, backed by {@code "globalOrchestrator"."purgeAudit"} (V27).
 *
 * <p>A purged run's own {@code runEvent} audit trail is cascaded away with the
 * run row, so the "who purged what, when, and how much was reclaimed" record has
 * to live OUTSIDE the deleted subtree. This table has no FK to run/application by
 * design — it must survive the deletion of its target. SELECT + INSERT only
 * (tombstones are immutable). Writes through the run-state (writer) datasource.
 */
@Repository
public class PurgeAuditRepository {

    private final JdbcTemplate jdbc;

    public PurgeAuditRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one purge. {@code childRunsPurged} is null for a run purge (only an
     * application purge sweeps child runs); the count fields are nullable so a
     * partial/failed purge still leaves a tombstone with what it managed to
     * reclaim.
     */
    public void record(String purgeId, String targetType, String targetId,
                       String applicationName, String actor, String reason,
                       Long metricRowsDeleted, Integer blobsDeleted, Integer childRunsPurged,
                       String detailsJson) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"purgeAudit\" "
                + "(\"purgeId\",\"targetType\",\"targetId\",\"applicationName\",\"actor\","
                + " \"reason\",\"metricRowsDeleted\",\"blobsDeleted\",\"childRunsPurged\","
                + " \"purgedAt\",\"details\") "
                + "VALUES (?,?,?,?,?,?,?,?,?, now(), ?::jsonb)",
                purgeId, targetType, targetId, applicationName, actor,
                reason, metricRowsDeleted, blobsDeleted, childRunsPurged,
                detailsJson);
    }
}
