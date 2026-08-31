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
            + "TRIGGERED_BY,STARTED_AT,COMPLETED_AT,NEXT_TICK_AT,HIDDEN_AT";

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
                // ORCH_CLAIMS.CLAIM_DUE_WORKFLOWS returns the columns the engine
                // needs and not this one — and never has to: the table's
                // HIDDEN_CHK makes an archived RUNNING row impossible, so a row
                // the claim can see is always unarchived.
                hasColumn(rs, "HIDDEN_AT") ? OracleBind.instant(rs, "HIDDEN_AT") : null,
                List.of());
    }

    /** True iff the result set carries this column; see the claim cursor above. */
    private static boolean hasColumn(ResultSet rs, String name) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(md.getColumnLabel(i))) return true;
        }
        return false;
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
        jdbc.update("INSERT INTO ORCH_WORKFLOW_EXECUTION (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                e.executionId(), e.workflowId(), e.groupId(), e.workflowName(), OracleBind.clob(graph),
                e.state().name(), OracleBind.text(e.stateReason(), OracleBind.TEXT_CHARS),
                OracleBind.text(e.triggeredBy(), OracleBind.NAME_CHARS), OracleBind.ts(e.startedAt()),
                OracleBind.ts(e.completedAt()), OracleBind.ts(e.nextTickAt()), OracleBind.ts(e.hiddenAt()));
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

    /**
     * Newest first, bounded — the workflow's history tab. {@code archived}
     * picks which side of the archive line to read; the two are never mixed,
     * because a list that quietly contains archived rows is how an operator
     * archives something twice.
     */
    public List<WorkflowExecution> findByWorkflow(String workflowId, int limit, boolean archived) {
        return jdbc.query(
                "SELECT " + COLS + " FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=? "
                + (archived ? "AND HIDDEN_AT IS NOT NULL " : "AND HIDDEN_AT IS NULL ")
                + "ORDER BY STARTED_AT DESC FETCH FIRST ? ROWS ONLY", row, workflowId, limit);
    }

    /** Archived rows still count, so a workflow's tab can offer the archive at all. */
    public int countArchived(String workflowId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=? AND HIDDEN_AT IS NOT NULL",
                Integer.class, workflowId);
        return n == null ? 0 : n;
    }

    /**
     * Archive the finished executions among {@code executionIds}, restricted to
     * one workflow so a caller cannot reach across workflows with a guessed id.
     *
     * @return how many rows this call actually archived
     */
    public int archive(String workflowId, List<String> executionIds, Instant at) {
        if (executionIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(executionIds.size(), "?"));
        Object[] args = new Object[executionIds.size() + 2];
        args[0] = OracleBind.ts(at);
        args[1] = workflowId;
        for (int i = 0; i < executionIds.size(); i++) args[i + 2] = executionIds.get(i);
        return jdbc.update(
                "UPDATE ORCH_WORKFLOW_EXECUTION SET HIDDEN_AT=? "
                + "WHERE WORKFLOW_ID=? AND STATE <> 'RUNNING' AND HIDDEN_AT IS NULL "
                + "AND EXECUTION_ID IN (" + placeholders + ")", args);
    }

    /** Put archived executions back on the history. */
    public int restore(String workflowId, List<String> executionIds) {
        if (executionIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(executionIds.size(), "?"));
        Object[] args = new Object[executionIds.size() + 1];
        args[0] = workflowId;
        for (int i = 0; i < executionIds.size(); i++) args[i + 1] = executionIds.get(i);
        return jdbc.update(
                "UPDATE ORCH_WORKFLOW_EXECUTION SET HIDDEN_AT=NULL "
                + "WHERE WORKFLOW_ID=? AND EXECUTION_ID IN (" + placeholders + ")", args);
    }

    /**
     * Delete archived executions for good; their tasks go with them by the FK's
     * cascade. Only archived rows are eligible — archiving first is what makes
     * this deliberate rather than a mis-click.
     *
     * <p>The runs a load test launched are NOT touched: a run is its own
     * history and outlives the workflow that started it.
     */
    public int deleteArchived(String workflowId, List<String> executionIds) {
        if (executionIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(executionIds.size(), "?"));
        Object[] args = new Object[executionIds.size() + 1];
        args[0] = workflowId;
        for (int i = 0; i < executionIds.size(); i++) args[i + 1] = executionIds.get(i);
        return jdbc.update(
                "DELETE FROM ORCH_WORKFLOW_EXECUTION "
                + "WHERE WORKFLOW_ID=? AND HIDDEN_AT IS NOT NULL AND EXECUTION_ID IN ("
                + placeholders + ")", args);
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

    /** The non-terminal executions themselves, for a delete that cancels them first. */
    public List<WorkflowExecution> findRunning(String workflowId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=? AND STATE='RUNNING'",
                row, workflowId);
    }

    /** Every execution of a workflow, archived or not; tasks cascade with them. */
    public int deleteForWorkflow(String workflowId) {
        return jdbc.update("DELETE FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=?", workflowId);
    }

    /** Non-terminal executions of a workflow — the guard that stops a delete pulling the rug. */
    public int countRunning(String workflowId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_WORKFLOW_EXECUTION WHERE WORKFLOW_ID=? AND STATE='RUNNING'",
                Integer.class, workflowId);
        return n == null ? 0 : n;
    }
}
