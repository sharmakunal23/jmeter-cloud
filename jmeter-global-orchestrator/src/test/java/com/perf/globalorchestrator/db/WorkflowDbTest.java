package com.perf.globalorchestrator.db;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.RegionCount;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The workflow tables' contract against the real V5 migration: the graph CLOB
 * survives a round trip with its node types intact, the revision lock rejects a
 * stale save, the claim leases an execution so a sibling tick cannot take it,
 * and the task/run link is one-way-only.
 */
@SpringBootTest(properties = {
        "globalOrchestrator.pod.sweepInitialDelayMs=3600000",
        "globalOrchestrator.workflow.sweepInitialDelayMs=3600000"
})
@DisplayName("workflow tables on Oracle — graph CLOB, revision lock, claim lease")
class WorkflowDbTest extends OracleDbTestSupport {

    @Autowired ApplicationGroupRepository groups;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowExecutionRepository executions;
    @Autowired WorkflowTaskRepository tasks;
    @Autowired PlatformTransactionManager txManager;

    private static final NodePosition P = new NodePosition(0, 0);

    private static WorkflowGraph sampleGraph() {
        List<WorkflowNode> nodes = List.of(
                new DelayNode("d1", "Settle", P, JoinPolicy.ALL, 30),
                new LoadTestNode("t1", "Peak load", P, JoinPolicy.ALL, "payments", "blob-1",
                        List.of(new RegionCount("na-east", 4)), Map.of("threads", "50"), true, null, 90));
        return new WorkflowGraph(WorkflowGraph.VERSION, nodes,
                List.of(new WorkflowEdge("e1", "d1", "t1", EdgeCondition.ON_SUCCESS)));
    }

    private String freshGroup() {
        String id = "wfg" + Long.toString(System.nanoTime(), 36).toLowerCase();
        groups.insert(new ApplicationGroup(id, "Group " + id, "workflow db test", Instant.now(), null));
        return id;
    }

    private Workflow freshWorkflow(String groupId, String name) {
        Instant now = Instant.now();
        return workflows.insert(new Workflow(Ulid.generate(), groupId, name, "a description",
                sampleGraph(), true, 1, "tester", now, "tester", now, null));
    }

    @Test
    @DisplayName("the graph CLOB round-trips with its node types and per-node config intact")
    void graphRoundTripsThroughTheClob() {
        String group = freshGroup();
        Workflow saved = freshWorkflow(group, "Nightly regression");

        WorkflowGraph back = workflows.findById(saved.workflowId()).orElseThrow().graph();
        assertThat(back.nodes()).hasSize(2);
        assertThat(back.nodeById("t1")).isInstanceOf(LoadTestNode.class);
        LoadTestNode lt = (LoadTestNode) back.nodeById("t1");
        assertThat(lt.workersIn("na-east")).isEqualTo(4);
        assertThat(lt.properties()).containsEntry("threads", "50");
        assertThat(lt.saveResults()).isTrue();
        assertThat(back.edges().get(0).condition()).isEqualTo(EdgeCondition.ON_SUCCESS);
    }

    @Test
    @DisplayName("two workflows in one group cannot share a name")
    void nameIsUniquePerGroup() {
        String group = freshGroup();
        freshWorkflow(group, "Nightly");
        assertThatThrownBy(() -> freshWorkflow(group, "Nightly"))
                .isInstanceOf(DuplicateKeyException.class);

        // ... but another group may use it.
        assertThat(freshWorkflow(freshGroup(), "Nightly").name()).isEqualTo("Nightly");
    }

    @Test
    @DisplayName("a save against a stale revision is refused, not applied")
    void revisionLockRefusesAStaleSave() {
        Workflow saved = freshWorkflow(freshGroup(), "Race");
        Instant now = Instant.now();

        Optional<Workflow> first = workflows.update(saved.workflowId(), saved.revision(), "Race v2", "d",
                sampleGraph(), true, "alice", now);
        assertThat(first).isPresent();
        assertThat(first.get().revision()).isEqualTo(saved.revision() + 1);

        // Bob still holds the revision Alice just superseded.
        Optional<Workflow> stale = workflows.update(saved.workflowId(), saved.revision(), "Race v3", "d",
                sampleGraph(), true, "bob", now);
        assertThat(stale).isEmpty();
        assertThat(workflows.findById(saved.workflowId()).orElseThrow().name()).isEqualTo("Race v2");
    }

    @Test
    @DisplayName("the claim leases an execution — a second tick at the same instant gets nothing")
    void claimLeasesTheExecution() {
        Workflow wf = freshWorkflow(freshGroup(), "Claimable");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(), wf.groupId(), wf.name(),
                sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now.minusSeconds(1), List.of()));

        TransactionTemplate tx = new TransactionTemplate(txManager);

        // A tick claims it and pushes the lease out, exactly as the engine does.
        List<String> firstPass = tx.execute(status -> {
            List<WorkflowExecution> due = executions.claimDue(now, 10);
            due.forEach(e -> executions.leaseUntil(e.executionId(), now.plusSeconds(60)));
            return due.stream().map(WorkflowExecution::executionId).toList();
        });
        assertThat(firstPass).hasSize(1);

        // The next tick at the same instant finds nothing: the lease moved.
        List<WorkflowExecution> secondPass = tx.execute(status -> executions.claimDue(now, 10));
        assertThat(secondPass).isEmpty();
    }

    @Test
    @DisplayName("a wake only ever brings the tick forward, and only while the execution is running")
    void nudgeIsForwardOnlyAndRunningOnly() {
        Workflow wf = freshWorkflow(freshGroup(), "Wakes");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now.plusSeconds(600), List.of()));

        // A run finished: pull the tick in from ten minutes out to now.
        assertThat(executions.nudge(ex.executionId(), now)).isTrue();
        assertThat(executions.findById(ex.executionId()).orElseThrow().nextTickAt())
                .isEqualTo(now);

        // A later wake must not push an already-due execution back out.
        assertThat(executions.nudge(ex.executionId(), now.plusSeconds(300))).isFalse();
        assertThat(executions.findById(ex.executionId()).orElseThrow().nextTickAt())
                .isEqualTo(now);

        // Nothing wakes a finished execution.
        executions.markTerminal(ex.executionId(), ExecutionState.SUCCEEDED, null, now);
        assertThat(executions.nudge(ex.executionId(), now)).isFalse();
        assertThat(executions.findById(ex.executionId()).orElseThrow().nextTickAt()).isNull();
    }

    @Test
    @DisplayName("a terminal execution clears its tick, and settling twice is a no-op")
    void markTerminalIsIdempotent() {
        Workflow wf = freshWorkflow(freshGroup(), "Settles");
        Instant now = Instant.now();
        WorkflowExecution open = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));

        assertThat(executions.markTerminal(open.executionId(), ExecutionState.SUCCEEDED, "all done", now))
                .isEqualTo(1);
        WorkflowExecution done = executions.findById(open.executionId()).orElseThrow();
        assertThat(done.state()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(done.nextTickAt()).isNull();
        assertThat(done.completedAt()).isNotNull();

        // A second replica racing the same transition changes nothing.
        assertThat(executions.markTerminal(open.executionId(), ExecutionState.FAILED, "late", now)).isZero();
        assertThat(executions.findById(open.executionId()).orElseThrow().state())
                .isEqualTo(ExecutionState.SUCCEEDED);
    }

    @Test
    @DisplayName("a task takes a run once; a second attach is refused so the engine adopts instead")
    void attachRunIsOneWay() {
        Workflow wf = freshWorkflow(freshGroup(), "Attaches");
        Instant now = Instant.now();
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        String taskId = Ulid.generate();
        tasks.insertAll(List.of(new WorkflowTask(taskId, ex.executionId(), "t1", NodeType.LOAD_TEST,
                "Peak load", TaskState.RUNNING, 1, "payments", null, now, null, null, null, null)));

        assertThat(tasks.attachRun(taskId, "run-1")).isTrue();
        assertThat(tasks.attachRun(taskId, "run-2")).isFalse();
        assertThat(tasks.findById(taskId).orElseThrow().runId()).isEqualTo("run-1");
        assertThat(tasks.findByRunId("run-1").orElseThrow().taskId()).isEqualTo(taskId);
        assertThat(tasks.runIdsOfExecution(ex.executionId())).containsExactly("run-1");
    }

    @Test
    @DisplayName("a task is claimed exactly once — the second claimer gets nothing, so no task runs twice")
    void claimForStartIsExactlyOnce() {
        Workflow wf = freshWorkflow(freshGroup(), "Claims");
        Instant now = Instant.now();
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        String taskId = Ulid.generate();
        tasks.insertAll(List.of(new WorkflowTask(taskId, ex.executionId(), "d1", NodeType.DELAY,
                "Settle", TaskState.PENDING, 0, null, null, null, null, null, null, null)));

        // Two ticks race the same PENDING task; only one may run it.
        assertThat(tasks.claimForStart(taskId, 1, now)).isTrue();
        assertThat(tasks.claimForStart(taskId, 1, now)).isFalse();

        WorkflowTask claimed = tasks.findById(taskId).orElseThrow();
        assertThat(claimed.state()).isEqualTo(TaskState.RUNNING);
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(claimed.startedAt()).isNotNull();
    }

    @Test
    @DisplayName("a cancel that lands mid-tick wins — the tick's write cannot resurrect the task")
    void cancelledTaskIsNeverResurrected() {
        Workflow wf = freshWorkflow(freshGroup(), "Cancels mid tick");
        Instant now = Instant.now();
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        String taskId = Ulid.generate();
        tasks.insertAll(List.of(new WorkflowTask(taskId, ex.executionId(), "d1", NodeType.DELAY,
                "Settle", TaskState.RUNNING, 1, null, null, now, null, now.plusSeconds(30), null, null)));

        // The operator cancels while a tick is still running the task.
        assertThat(tasks.cancelUnfinished(ex.executionId(), now, "cancelled by operator")).isEqualTo(1);

        // The in-flight tick now writes the outcome it computed before the cancel.
        tasks.update(new WorkflowTask(taskId, ex.executionId(), "d1", NodeType.DELAY, "Settle",
                TaskState.SUCCEEDED, 1, null, null, now, now, null, Map.of("waitSeconds", 30), null));

        WorkflowTask after = tasks.findById(taskId).orElseThrow();
        assertThat(after.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(after.errorReason()).isEqualTo("cancelled by operator");
    }

    @Test
    @DisplayName("the task result CLOB round-trips, and a cancel settles everything unfinished")
    void resultJsonAndBulkCancel() {
        Workflow wf = freshWorkflow(freshGroup(), "Cancels");
        Instant now = Instant.now();
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        String a = Ulid.generate();
        String b = Ulid.generate();
        tasks.insertAll(List.of(
                new WorkflowTask(a, ex.executionId(), "d1", NodeType.DELAY, "Settle", TaskState.RUNNING,
                        0, null, null, now, null, now.plusSeconds(30), null, null),
                new WorkflowTask(b, ex.executionId(), "t1", NodeType.LOAD_TEST, "Peak load", TaskState.PENDING,
                        0, "payments", null, null, null, null, null, null)));

        WorkflowTask withResult = tasks.findById(a).orElseThrow();
        tasks.update(new WorkflowTask(withResult.taskId(), withResult.executionId(), withResult.nodeId(),
                withResult.type(), withResult.name(), TaskState.SUCCEEDED, 1, null, null, now, now, null,
                Map.of("waitedSeconds", 30, "note", "settled"), null));
        assertThat(tasks.findById(a).orElseThrow().result())
                .containsEntry("waitedSeconds", 30)
                .containsEntry("note", "settled");

        assertThat(tasks.cancelUnfinished(ex.executionId(), now, "operator cancelled")).isEqualTo(1);
        assertThat(tasks.findById(b).orElseThrow().state()).isEqualTo(TaskState.CANCELLED);
        assertThat(tasks.findById(a).orElseThrow().state()).isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    @DisplayName("the bounded history reads bind their row limit — FETCH FIRST ? is not a literal")
    void boundedHistoryReadsBindTheLimit() {
        Workflow wf = freshWorkflow(freshGroup(), "History");
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(), wf.groupId(), wf.name(),
                    sampleGraph(), ExecutionState.RUNNING, null, "tester", now.plusMillis(i), null,
                    now.plusSeconds(3600), List.of()));
        }
        assertThat(executions.findByWorkflow(wf.workflowId(), 3, false)).hasSize(3);
        assertThat(executions.findByGroup(wf.groupId(), 2)).hasSize(2);
        assertThat(executions.countRunning(wf.workflowId())).isEqualTo(5);
    }

    @Test
    @DisplayName("archive hides a finished run from the history, restore brings it back, and delete needs the archive first")
    void archiveLifecycle() {
        Workflow wf = freshWorkflow(freshGroup(), "Archives");
        Instant now = Instant.now();
        WorkflowExecution done = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        executions.markTerminal(done.executionId(), ExecutionState.SUCCEEDED, null, now);
        WorkflowExecution live = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now.plusSeconds(600), List.of()));

        // A run still going is skipped, never archived out from under the engine.
        assertThat(executions.archive(wf.workflowId(), List.of(done.executionId(), live.executionId()), now))
                .isEqualTo(1);
        assertThat(executions.findByWorkflow(wf.workflowId(), 10, false))
                .extracting(WorkflowExecution::executionId).containsExactly(live.executionId());
        assertThat(executions.findByWorkflow(wf.workflowId(), 10, true))
                .extracting(WorkflowExecution::executionId).containsExactly(done.executionId());
        assertThat(executions.countArchived(wf.workflowId())).isEqualTo(1);

        // Deleting is only ever the second step: a live row is not archived, so
        // nothing about it is deletable.
        assertThat(executions.deleteArchived(wf.workflowId(), List.of(live.executionId()))).isZero();
        assertThat(executions.findById(live.executionId())).isPresent();

        assertThat(executions.restore(wf.workflowId(), List.of(done.executionId()))).isEqualTo(1);
        assertThat(executions.findByWorkflow(wf.workflowId(), 10, false)).hasSize(2);

        assertThat(executions.archive(wf.workflowId(), List.of(done.executionId()), now)).isEqualTo(1);
        assertThat(executions.deleteArchived(wf.workflowId(), List.of(done.executionId()))).isEqualTo(1);
        assertThat(executions.findById(done.executionId())).isEmpty();
    }

    @Test
    @DisplayName("deleting a workflow's executions takes their tasks with them")
    void deleteForWorkflowCascadesTasks() {
        Workflow wf = freshWorkflow(freshGroup(), "Purges");
        Instant now = Instant.now();
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        tasks.insertAll(List.of(new WorkflowTask(Ulid.generate(), ex.executionId(), "d1", NodeType.DELAY,
                "Settle", TaskState.PENDING, 0, null, null, null, null, null, null, null)));

        assertThat(executions.deleteForWorkflow(wf.workflowId())).isEqualTo(1);
        assertThat(executions.findById(ex.executionId())).isEmpty();
        assertThat(tasks.findByExecution(ex.executionId())).isEmpty();
    }

    @Test
    @DisplayName("deleting an execution takes its tasks with it; deleting a workflow leaves its history")
    void cascadeAndHistoryRules() {
        Workflow wf = freshWorkflow(freshGroup(), "Cascades");
        Instant now = Instant.now();
        WorkflowExecution ex = executions.insert(new WorkflowExecution(Ulid.generate(), wf.workflowId(),
                wf.groupId(), wf.name(), sampleGraph(), ExecutionState.RUNNING, null, "tester", now, null,
                now, List.of()));
        tasks.insertAll(List.of(new WorkflowTask(Ulid.generate(), ex.executionId(), "d1", NodeType.DELAY,
                "Settle", TaskState.PENDING, 0, null, null, null, null, null, null, null)));

        // The workflow row goes; the execution that ran stays readable.
        assertThat(workflows.delete(wf.workflowId())).isTrue();
        assertThat(executions.findById(ex.executionId())).isPresent();
        assertThat(tasks.findByExecution(ex.executionId())).hasSize(1);
    }
}
