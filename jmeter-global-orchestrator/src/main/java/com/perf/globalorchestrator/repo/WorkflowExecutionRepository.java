package com.perf.globalorchestrator.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access for {@code ORCH_WORKFLOW_EXECUTION}.
 *
 * <p>{@link #claimDue} goes through {@code ORCH_CLAIMS.CLAIM_DUE_WORKFLOWS},
 * which locks each due row with {@code FOR UPDATE SKIP LOCKED} — it MUST run
 * inside a transaction that also calls {@link #leaseUntil} before committing,
 * or the lock releases immediately and two replicas advance one execution.
 */
@Repository
public class WorkflowExecutionRepository {

    private static final String COLS =
            "EXECUTION_ID,WORKFLOW_ID,GROUP_ID,WORKFLOW_NAME,GRAPH,STATE,STATE_REASON,"
            + "TRIGGERED_BY,STARTED_AT,COMPLETED_AT,NEXT_TICK_AT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<WorkflowExecution> row;

    public WorkflowExecutionRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, n) -> mapRow(rs, json);
    }

    private static WorkflowExecution mapRow(ResultSet rs, ObjectMapper json) throws SQLException {
        return new WorkflowExecution(
                rs.getString("EXECUTION_ID"),
                rs.getString("WORKFLOW_ID"),
                rs.getString("GROUP_ID"),
                rs.getString("WORKFLOW_NAME"),
                readGraph(OracleBind.json(rs, "GRAPH"), json),
                ExecutionState.valueOf(rs.getString("STATE")),
                rs.getString("STATE_REASON"),
                rs.getString("TRIGGERED_BY"),
                OracleBind.instant(rs, "STARTED_AT"),
                OracleBind.instant(rs, "COMPLETED_AT"),
                OracleBind.instant(rs, "NEXT_TICK_AT"),
                List.of());
    }

    private static WorkflowGraph readGraph(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) return WorkflowGraph.empty();
        try {
            return json.readValue(raw, WorkflowGraph.class);
        } catch (Exception e) {
            return WorkflowGraph.empty();
        }
    }

    public WorkflowExecution insert(WorkflowExecution e) {
        String graph;
        try {
            graph = json.writeValueAsString(e.graph());
        } catch (Exception ex) {
            throw new IllegalStateException("workflow graph is not serialisable", ex);
        }
        jdbc.update("INSERT INTO ORCH_WORKFLOW_EXECUTION (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                e.executionId(), e.workflowId(), e.groupId(), e.workflowName(), OracleBind.clob(graph),
                e.state().name(), OracleBind.text(e.stateReason(), OracleBind.TEXT_CHARS),
                OracleBind.text(e.triggeredBy(), OracleBind.NAME_CHARS), OracleBind.ts(e.startedAt()),
                OracleBind.ts(e.completedAt()), OracleBind.ts(e.nextTickAt()));
        return findById(e.executionId()).orElseThrow();
    }

    public Optional<WorkflowExecution> findById(String executionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM ORCH_WORKFLOW_EXECUTION WHERE EXECUTION_ID=?", row, executionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Newest first, bounded — the workflow's history tab. */
    public List<WorkflowExecution> findByWorkflow(String workflowId, int limit) {
        return jdbc.query(
                "SELECT " + COLS + " FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=? "
                + "ORDER BY STARTED_AT DESC FETCH FIRST ? ROWS ONLY", row, workflowId, limit);
    }

    /** Newest first across a group — the group landing's activity strip. */
    public List<WorkflowExecution> findByGroup(String groupId, int limit) {
        return jdbc.query(
                "SELECT " + COLS + " FROM ORCH_WORKFLOW_EXECUTION WHERE GROUP_ID=? "
                + "ORDER BY STARTED_AT DESC FETCH FIRST ? ROWS ONLY", row, groupId, limit);
    }

    /**
     * Lock the executions due at {@code now}. Call {@link #leaseUntil} on each
     * before the transaction commits: that push is what stops a sibling replica
     * re-claiming a row this one is still advancing.
     */
    public List<WorkflowExecution> claimDue(Instant now, int limit) {
        return OracleBind.refCursor(jdbc,
                "BEGIN ORCH_CLAIMS.CLAIM_DUE_WORKFLOWS(?, ?, ?); END;",
                cs -> { cs.setObject(1, OracleBind.ts(now)); cs.setInt(2, limit); },
                3, row);
    }

    /**
     * Bring the next tick forward, never push it back. Used when something the
     * engine was waiting for has happened — a run finished, an approval was
     * answered — so the execution is looked at now rather than at whatever
     * interval it had settled on.
     *
     * @return true when this call actually moved it; false when it was already
     *         due sooner, or the execution is no longer running
     */
    public boolean nudge(String executionId, Instant at) {
        return jdbc.update(
                "UPDATE ORCH_WORKFLOW_EXECUTION SET NEXT_TICK_AT=? "
                + "WHERE EXECUTION_ID=? AND STATE='RUNNING' AND NEXT_TICK_AT > ?",
                OracleBind.ts(at), executionId, OracleBind.ts(at)) > 0;
    }

    /** Move the next tick — the lease while advancing, the schedule once advanced. */
    public void leaseUntil(String executionId, Instant nextTickAt) {
        jdbc.update("UPDATE ORCH_WORKFLOW_EXECUTION SET NEXT_TICK_AT=? WHERE EXECUTION_ID=? AND STATE='RUNNING'",
                OracleBind.ts(nextTickAt), executionId);
    }

    /**
     * Settle the execution. {@code NEXT_TICK_AT} is cleared and
     * {@code COMPLETED_AT} set in the same statement because the table's CHECK
     * requires exactly that pairing.
     *
     * @return 1 when this caller made the transition, 0 when it was already terminal
     */
    public int markTerminal(String executionId, ExecutionState state, String reason, Instant completedAt) {
        return jdbc.update(
                "UPDATE ORCH_WORKFLOW_EXECUTION SET STATE=?, STATE_REASON=?, COMPLETED_AT=?, NEXT_TICK_AT=NULL "
                + "WHERE EXECUTION_ID=? AND STATE='RUNNING'",
                state.name(), OracleBind.text(reason, OracleBind.TEXT_CHARS),
                OracleBind.ts(completedAt), executionId);
    }

    /** Non-terminal executions of a workflow — the guard that stops a delete pulling the rug. */
    public int countRunning(String workflowId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=? AND STATE='RUNNING'",
                Integer.class, workflowId);
        return n == null ? 0 : n;
    }
}
