package com.perf.globalorchestrator.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.RunEvent;
import com.perf.globalorchestrator.domain.RunEventType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Persistence for {@code globalOrchestrator.runEvent}. Uses the
 * <strong>same</strong> runState datasource as {@link RunRepository} so an
 * {@link #insert(RunEvent)} call from inside a {@code @Transactional} run
 * mutation commits (or rolls back) atomically with the mutation — a
 * rolled-back action emits zero events (audit-trail decision #7).
 *
 * <p>Append-only: there is no update or delete here. Tampering is prevented at
 * the grant level too (the writer role has SELECT + INSERT only).
 */
@Repository
public class RunEventRepository {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<RunEvent> rowMapper;

    public RunEventRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc,
                              ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = buildRowMapper(json);
    }

    private static RowMapper<RunEvent> buildRowMapper(ObjectMapper json) {
        return (rs, n) -> {
            String payloadRaw = OracleBind.json(rs, "payload");
            Map<String, Object> payload;
            if (payloadRaw == null || payloadRaw.isBlank()) {
                payload = Map.of();
            } else {
                try {
                    payload = json.readValue(payloadRaw, PAYLOAD_TYPE);
                } catch (Exception e) {
                    throw new SQLException("failed to deserialise runEvent.payload", e);
                }
            }
            return new RunEvent(
                    rs.getString("eventId"),
                    rs.getString("runId"),
                    RunEventType.valueOf(rs.getString("eventType")),
                    rs.getString("actor"),
                    rs.getString("actorSource"),
                    payload,
                    rs.getString("result"),
                    OracleBind.instant(rs, "occurredAt"));
        };
    }

    /**
     * Append one audit event. Idempotent on the {@code eventId} PK — a retried
     * request carrying the same id matches the MERGE and is silently dropped
     * (decision #10). The column's {@code IS JSON} check validates the payload
     * server-side, same as {@link RunRepository#insertFleetMember}.
     */
    public void insert(RunEvent e) {
        String payloadJson;
        try {
            payloadJson = json.writeValueAsString(
                    e.payload() == null ? Map.of() : e.payload());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialise runEvent payload", ex);
        }
        jdbc.update(
                "MERGE INTO \"globalOrchestrator\".\"runEvent\" t "
                + "USING (SELECT ? AS \"eventId\" FROM dual) s ON (t.\"eventId\" = s.\"eventId\") "
                + "WHEN NOT MATCHED THEN INSERT "
                + "(\"eventId\",\"runId\",\"eventType\",\"actor\",\"actorSource\","
                + " \"payload\",\"result\",\"occurredAt\") "
                + "VALUES (s.\"eventId\",?,?,?,?,?,?,?)",
                e.eventId(), e.runId(), e.eventType().name(),
                OracleBind.text(e.actor(), OracleBind.NAME_CHARS), e.actorSource(),
                OracleBind.clob(payloadJson), e.result(),
                OracleBind.ts(e.occurredAt()));
    }

    /**
     * The full reverse-chronological audit timeline for one run. The
     * {@code eventId} DESC tie-break keeps same-instant events stably ordered
     * (ULIDs are time-sortable, so this also respects sub-millisecond creation
     * order). Used by internal callers + tests; the REST path uses the
     * paginated {@link #findByRunId(String, int, int)} since a long-running
     * test can accumulate many events.
     */
    public List<RunEvent> findByRunId(String runId) {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"runEvent\" "
                + "WHERE \"runId\"=? "
                + "ORDER BY \"occurredAt\" DESC, \"eventId\" DESC",
                rowMapper, runId);
    }

    /**
     * One page of the reverse-chronological timeline (offset/limit, newest
     * first). The ORDER BY matches {@link #findByRunId(String)}
     * so paging is stable across requests. {@code offset}/{@code limit} are
     * assumed already clamped by the service.
     */
    public List<RunEvent> findByRunId(String runId, int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"runEvent\" "
                + "WHERE \"runId\"=? "
                + "ORDER BY \"occurredAt\" DESC, \"eventId\" DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                rowMapper, runId, offset, limit);
    }

    /**
     * Save Results — the set of worker IDs that already have a
     * {@code RESULTS_SAVED} event for this run. Used as a <b>durable</b> dedup
     * guard so neither a global-orchestrator restart nor the background
     * reconciliation sweeper re-emits an event a prior poll already wrote
     * (the in-memory dedup set in {@code RunService} is lost on restart).
     * Reads {@code JSON_VALUE(payload, '$.workerId')} — the {@code ResultsSaved}
     * record's first component.
     */
    public java.util.Set<String> resultsSavedWorkerIds(String runId) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT JSON_VALUE(\"payload\", '$.workerId') "
                + "FROM \"globalOrchestrator\".\"runEvent\" "
                + "WHERE \"runId\"=? AND \"eventType\"='RESULTS_SAVED' "
                + "  AND JSON_VALUE(\"payload\", '$.workerId') IS NOT NULL",
                String.class, runId));
    }

    /** Total event count for a run — drives the {@code X-Total-Count} header. */
    public long countByRunId(String runId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"globalOrchestrator\".\"runEvent\" WHERE \"runId\"=?",
                Long.class, runId);
        return n == null ? 0L : n;
    }

    /** One page of audit events plus the total count across all pages. */
    public record RunEventsPage(List<RunEvent> events, long total) {}
}
