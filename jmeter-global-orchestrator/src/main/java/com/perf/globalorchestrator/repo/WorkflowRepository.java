package com.perf.globalorchestrator.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.domain.WorkflowExecutionSummary;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC access for {@code ORCH_WORKFLOW}. The graph is a JSON CLOB, so a row
 * that fails to parse is surfaced as an empty graph rather than an exception —
 * a workflow written by a newer version must still be listable and deletable.
 *
 * <p>{@link #update} carries the caller's {@code revision} in its WHERE clause:
 * zero rows updated means someone else saved first, and the service turns that
 * into a 409 instead of overwriting their work.
 */
@Repository
public class WorkflowRepository {

    private static final String COLS =
            "WORKFLOW_ID,GROUP_ID,NAME,DESCRIPTION,GRAPH,ENABLED,REVISION,"
            + "CREATED_BY,CREATED_AT,UPDATED_BY,UPDATED_AT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Workflow> row;

    public WorkflowRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, n) -> mapRow(rs, json);
    }

    private static Workflow mapRow(ResultSet rs, ObjectMapper json) throws SQLException {
        return new Workflow(
                rs.getString("WORKFLOW_ID"),
                rs.getString("GROUP_ID"),
                rs.getString("NAME"),
                rs.getString("DESCRIPTION"),
                readGraph(OracleBind.json(rs, "GRAPH"), json),
                rs.getInt("ENABLED") == 1,
                rs.getInt("REVISION"),
                rs.getString("CREATED_BY"),
                OracleBind.instant(rs, "CREATED_AT"),
                rs.getString("UPDATED_BY"),
                OracleBind.instant(rs, "UPDATED_AT"),
                null);
    }

    /** A graph this version cannot read must not make the row unreadable — the UI still needs to show and delete it. */
    private static WorkflowGraph readGraph(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) return WorkflowGraph.empty();
        try {
            return json.readValue(raw, WorkflowGraph.class);
        } catch (Exception e) {
            return WorkflowGraph.empty();
        }
    }

    String writeGraph(WorkflowGraph graph) {
        try {
            return json.writeValueAsString(graph == null ? WorkflowGraph.empty() : graph);
        } catch (Exception e) {
            throw new IllegalStateException("workflow graph is not serialisable", e);
        }
    }

    /** Insert. The UNIQUE (GROUP_ID, NAME) surfaces a duplicate as {@code DuplicateKeyException} → 409. */
    public Workflow insert(Workflow w) {
        jdbc.update(
                "INSERT INTO ORCH_WORKFLOW (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                w.workflowId(), w.groupId(), w.name(), OracleBind.text(w.description(), OracleBind.TEXT_CHARS),
                OracleBind.clob(writeGraph(w.graph())), w.enabled() ? 1 : 0, w.revision(),
                OracleBind.text(w.createdBy(), OracleBind.NAME_CHARS), OracleBind.ts(w.createdAt()),
                OracleBind.text(w.updatedBy(), OracleBind.NAME_CHARS), OracleBind.ts(w.updatedAt()));
        return findById(w.workflowId()).orElseThrow();
    }

    /**
     * Save over {@code expectedRevision}, bumping it. Returns empty when the row
     * is gone or another writer moved the revision on — never a blind overwrite.
     */
    public Optional<Workflow> update(String workflowId, int expectedRevision, String name, String description,
                                     WorkflowGraph graph, boolean enabled, String updatedBy, Instant updatedAt) {
        int updated = jdbc.update(
                "UPDATE ORCH_WORKFLOW SET NAME=?, DESCRIPTION=?, GRAPH=?, ENABLED=?, REVISION=REVISION+1, "
                + "UPDATED_BY=?, UPDATED_AT=? WHERE WORKFLOW_ID=? AND REVISION=?",
                name, OracleBind.text(description, OracleBind.TEXT_CHARS), OracleBind.clob(writeGraph(graph)),
                enabled ? 1 : 0, OracleBind.text(updatedBy, OracleBind.NAME_CHARS), OracleBind.ts(updatedAt),
                workflowId, expectedRevision);
        return updated == 0 ? Optional.empty() : findById(workflowId);
    }

    public Optional<Workflow> findById(String workflowId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM ORCH_WORKFLOW WHERE WORKFLOW_ID=?", row, workflowId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Workflow> findByGroup(String groupId) {
        return jdbc.query("SELECT " + COLS + " FROM ORCH_WORKFLOW WHERE GROUP_ID=? ORDER BY NAME", row, groupId);
    }

    public boolean delete(String workflowId) {
        return jdbc.update("DELETE FROM ORCH_WORKFLOW WHERE WORKFLOW_ID=?", workflowId) > 0;
    }

    /** {@code groupId → workflow count} for the Workflows landing page; one statement for every group. */
    /**
     * {@code workflowId → name} for every workflow. One small query so a list
     * that references workflows renders in a single round-trip instead of one
     * lookup per row; the table is bounded by what operators draw by hand.
     */
    public Map<String, String> namesById() {
        Map<String, String> out = new java.util.HashMap<>();
        jdbc.query("SELECT WORKFLOW_ID, NAME FROM ORCH_WORKFLOW",
                rs -> { out.put(rs.getString("WORKFLOW_ID"), rs.getString("NAME")); });
        return out;
    }

    public Map<String, Integer> countsByGroup() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("SELECT GROUP_ID, COUNT(*) AS N FROM ORCH_WORKFLOW GROUP BY GROUP_ID",
                rs -> { out.put(rs.getString("GROUP_ID"), rs.getInt("N")); });
        return out;
    }

    /**
     * The newest execution of each workflow in one group. Reads only the four
     * columns a list row shows, ranked in an inline view — the alternative is a
     * query per workflow.
     */
    public Map<String, WorkflowExecutionSummary> lastExecutionsByWorkflow(String groupId) {
        Map<String, WorkflowExecutionSummary> out = new HashMap<>();
        jdbc.query(
                "SELECT WORKFLOW_ID, EXECUTION_ID, STATE, STARTED_AT, COMPLETED_AT FROM ("
                + "  SELECT e.WORKFLOW_ID, e.EXECUTION_ID, e.STATE, e.STARTED_AT, e.COMPLETED_AT,"
                + "         ROW_NUMBER() OVER (PARTITION BY e.WORKFLOW_ID ORDER BY e.STARTED_AT DESC) AS RN"
                + "  FROM ORCH_WORKFLOW_EXECUTION e WHERE e.GROUP_ID=?"
                + ") WHERE RN=1",
                rs -> {
                    out.put(rs.getString("WORKFLOW_ID"), new WorkflowExecutionSummary(
                            rs.getString("EXECUTION_ID"),
                            ExecutionState.valueOf(rs.getString("STATE")),
                            OracleBind.instant(rs, "STARTED_AT"),
                            OracleBind.instant(rs, "COMPLETED_AT")));
                },
                groupId);
        return out;
    }
}
