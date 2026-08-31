package com.perf.globalorchestrator.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowTask;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC access for {@code ORCH_WORKFLOW_TASK} — one row per node of one
 * execution, written in a batch when the execution opens so the engine only
 * ever updates.
 *
 * <p>{@link #attachRun} is deliberately conditional on the task still holding no
 * run: paired with the unique index on {@code ORCH_RUN.WORKFLOW_TASK_ID}, that
 * makes "one task, one run" true on both sides of the crash window.
 */
@Repository
public class WorkflowTaskRepository {

    private static final String COLS =
            "TASK_ID,EXECUTION_ID,NODE_ID,TYPE,NAME,STATE,ATTEMPT,APPLICATION_NAME,RUN_ID,"
            + "STARTED_AT,COMPLETED_AT,DUE_AT,RESULT,ERROR_REASON";
    private static final TypeReference<Map<String, Object>> RESULT_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<WorkflowTask> row;

    public WorkflowTaskRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, n) -> mapRow(rs, json);
    }

    private static WorkflowTask mapRow(ResultSet rs, ObjectMapper json) throws SQLException {
        return new WorkflowTask(
                rs.getString("TASK_ID"),
                rs.getString("EXECUTION_ID"),
                rs.getString("NODE_ID"),
                NodeType.valueOf(rs.getString("TYPE")),
                rs.getString("NAME"),
                TaskState.valueOf(rs.getString("STATE")),
                rs.getInt("ATTEMPT"),
                rs.getString("APPLICATION_NAME"),
                rs.getString("RUN_ID"),
                OracleBind.instant(rs, "STARTED_AT"),
                OracleBind.instant(rs, "COMPLETED_AT"),
                OracleBind.instant(rs, "DUE_AT"),
                readResult(OracleBind.json(rs, "RESULT"), json),
                rs.getString("ERROR_REASON"));
    }

    private static Map<String, Object> readResult(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readValue(raw, RESULT_TYPE);
        } catch (Exception e) {
            return Map.of("unreadable", true);
        }
    }

    private String writeResult(Map<String, Object> result) {
        if (result == null) return null;
        try {
            return json.writeValueAsString(result);
        } catch (Exception e) {
            return null;
        }
    }

    /** One batch for the whole execution — the task set never grows after this. */
    public void insertAll(List<WorkflowTask> tasks) {
        jdbc.batchUpdate("INSERT INTO ORCH_WORKFLOW_TASK (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tasks.stream().map(t -> new Object[]{
                        t.taskId(), t.executionId(), t.nodeId(), t.type().name(),
                        OracleBind.text(t.name(), OracleBind.NAME_CHARS), t.state().name(), t.attempt(),
                        OracleBind.text(t.applicationName(), OracleBind.NAME_CHARS), t.runId(),
                        OracleBind.ts(t.startedAt()), OracleBind.ts(t.completedAt()), OracleBind.ts(t.dueAt()),
                        OracleBind.clob(writeResult(t.result())),
                        OracleBind.text(t.errorReason(), OracleBind.TEXT_CHARS)}).toList());
    }

    public List<WorkflowTask> findByExecution(String executionId) {
        return jdbc.query("SELECT " + COLS + " FROM ORCH_WORKFLOW_TASK WHERE EXECUTION_ID=? ORDER BY NODE_ID",
                row, executionId);
    }

    public Optional<WorkflowTask> findById(String taskId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM ORCH_WORKFLOW_TASK WHERE TASK_ID=?", row, taskId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Take a PENDING task for execution, as a compare-and-set. Returns false
     * when someone else already took it, which is what stops two replicas
     * running one task: a lease that expires mid-execution lets a sibling
     * re-claim the execution, and a task still PENDING would otherwise be
     * started again — a second email, a second probe.
     */
    public boolean claimForStart(String taskId, int attempt, Instant startedAt) {
        return jdbc.update(
                "UPDATE ORCH_WORKFLOW_TASK SET STATE='RUNNING', ATTEMPT=?, STARTED_AT=? "
                + "WHERE TASK_ID=? AND STATE='PENDING'",
                attempt, OracleBind.ts(startedAt), taskId) > 0;
    }

    /**
     * Every mutable field of a task in one statement — the engine writes a task
     * at most once per tick.
     *
     * <p>A CANCELLED task is never moved: an operator's cancel can commit while
     * a tick is mid-advance, and without this guard the tick's write would
     * resurrect the task it had already settled.
     */
    public void update(WorkflowTask t) {
        jdbc.update("UPDATE ORCH_WORKFLOW_TASK SET STATE=?, ATTEMPT=?, RUN_ID=?, STARTED_AT=?, COMPLETED_AT=?, "
                + "DUE_AT=?, RESULT=?, ERROR_REASON=? WHERE TASK_ID=? AND STATE <> 'CANCELLED'",
                t.state().name(), t.attempt(), t.runId(), OracleBind.ts(t.startedAt()),
                OracleBind.ts(t.completedAt()), OracleBind.ts(t.dueAt()),
                OracleBind.clob(writeResult(t.result())),
                OracleBind.text(t.errorReason(), OracleBind.TEXT_CHARS), t.taskId());
    }

    /**
     * Record the run a load-test task launched, only while it holds none.
     *
     * @return true when this call attached it; false means a run was already
     *         recorded, which is the signal to adopt that one rather than launch again
     */
    public boolean attachRun(String taskId, String runId) {
        return jdbc.update("UPDATE ORCH_WORKFLOW_TASK SET RUN_ID=? WHERE TASK_ID=? AND RUN_ID IS NULL",
                runId, taskId) > 0;
    }

    /** The workflow task a run belongs to — the run page's "part of" link; an indexed exact match. */
    public Optional<WorkflowTask> findByRunId(String runId) {
        List<WorkflowTask> rows = jdbc.query(
                "SELECT " + COLS + " FROM ORCH_WORKFLOW_TASK WHERE RUN_ID=?", row, runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Load-test run ids of one execution, in node order — what the execution's metrics panel charts. */
    public List<String> runIdsOfExecution(String executionId) {
        return jdbc.queryForList(
                "SELECT RUN_ID FROM ORCH_WORKFLOW_TASK WHERE EXECUTION_ID=? AND RUN_ID IS NOT NULL ORDER BY NODE_ID",
                String.class, executionId);
    }

    /** Cancel every task that has not settled — the execution-level cancel. */
    public int cancelUnfinished(String executionId, Instant at, String reason) {
        return jdbc.update(
                "UPDATE ORCH_WORKFLOW_TASK SET STATE='CANCELLED', COMPLETED_AT=?, DUE_AT=NULL, ERROR_REASON=? "
                + "WHERE EXECUTION_ID=? AND STATE IN ('PENDING','RUNNING','AWAITING_APPROVAL')",
                OracleBind.ts(at), OracleBind.text(reason, OracleBind.TEXT_CHARS), executionId);
    }
}
