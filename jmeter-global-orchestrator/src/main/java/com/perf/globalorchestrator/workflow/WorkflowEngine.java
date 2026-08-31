package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.ApprovalNode;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EmailNode;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.HealthCheckNode;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.observability.ErrorContext;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Advances workflow executions. One tick claims the due ones
 * ({@link #claimDue()}) and calls {@link #advance} on each **outside** that
 * transaction, so a slow health probe or run poll never holds a row lock.
 *
 * <p>The engine is a pure state machine over the task rows: it polls what is
 * open, starts what has become ready, skips what can no longer run, and either
 * settles the execution or records when to look again. Nothing is held in
 * memory between ticks, so any replica can pick up any execution and a restart
 * costs at most one tick.
 */
@Service
public class WorkflowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowExecutionRepository executions;
    private final WorkflowTaskRepository tasks;
    private final ApplicationGroupRepository groups;
    private final HealthCheckTaskExecutor healthCheck;
    private final LoadTestTaskExecutor loadTest;
    private final EmailTaskExecutor email;
    private final DelayTaskExecutor delay;
    private final ApprovalTaskExecutor approval;
    private final int leaseSeconds;
    private final int idlePollSeconds;
    private final int maxDueBatch;

    public WorkflowEngine(WorkflowExecutionRepository executions,
                          WorkflowTaskRepository tasks,
                          ApplicationGroupRepository groups,
                          HealthCheckTaskExecutor healthCheck,
                          LoadTestTaskExecutor loadTest,
                          EmailTaskExecutor email,
                          DelayTaskExecutor delay,
                          ApprovalTaskExecutor approval,
                          @Value("${globalOrchestrator.workflow.leaseSeconds:60}") int leaseSeconds,
                          @Value("${globalOrchestrator.workflow.idlePollSeconds:300}") int idlePollSeconds,
                          @Value("${globalOrchestrator.workflow.maxDueBatch:20}") int maxDueBatch) {
        this.executions = executions;
        this.tasks = tasks;
        this.groups = groups;
        this.healthCheck = healthCheck;
        this.loadTest = loadTest;
        this.email = email;
        this.delay = delay;
        this.approval = approval;
        this.leaseSeconds = leaseSeconds;
        this.idlePollSeconds = idlePollSeconds;
        this.maxDueBatch = maxDueBatch;
    }

    /**
     * Lock the due executions and push each lease out by {@code leaseSeconds}.
     * Advancing happens after this commits, so a slow probe or run poll never
     * holds a row lock, and a lease that expires lets another replica finish
     * the job if this one dies.
     *
     * <p><b>The lease limits duplicate work; it does not guarantee exclusivity.</b>
     * A run finishing mid-advance wakes its own execution
     * ({@code WorkflowRunCompletionListener}), which pulls {@code NEXT_TICK_AT}
     * back to now and so drops the lease this advance is holding — a sibling
     * sweep can then advance the same execution concurrently. That is safe
     * because correctness rests on the per-row guards, not on the lease:
     * {@code claimForStart} is a compare-and-set so exactly one replica ever
     * STARTS a task, {@code markTerminal} is conditional on RUNNING, and
     * settling an already-running task writes the same values from either
     * replica. Never move a side effect out from behind those guards on the
     * assumption that the lease makes an advance exclusive.
     */
    @Transactional
    public List<WorkflowExecution> claimDue() {
        Instant now = Instant.now();
        List<WorkflowExecution> due = executions.claimDue(now, maxDueBatch);
        for (WorkflowExecution e : due) {
            executions.leaseUntil(e.executionId(), now.plusSeconds(leaseSeconds));
        }
        return due;
    }

    /** Advance one execution by a tick. Never throws — a failure leaves the lease to expire and retries. */
    public void advance(WorkflowExecution execution) {
        try {
            advanceOrThrow(execution);
        } catch (RuntimeException e) {
            ErrorContext.logWarn(LOG, "workflow executionId=" + execution.executionId(),
                    "advance failed; the lease will expire and another tick will retry", e);
        }
    }

    private void advanceOrThrow(WorkflowExecution execution) {
        ApplicationGroup group = groups.findById(execution.groupId()).orElse(null);
        if (group == null) {
            executions.markTerminal(execution.executionId(), ExecutionState.FAILED,
                    "application group " + execution.groupId() + " no longer exists", Instant.now());
            return;
        }

        Instant now = Instant.now();
        WorkflowGraph graph = execution.graph();
        Map<String, WorkflowTask> byNode = new LinkedHashMap<>();
        for (WorkflowTask t : tasks.findByExecution(execution.executionId())) {
            byNode.put(t.nodeId(), t);
        }

        // An execution with tasks but no readable graph is a snapshot THIS
        // build cannot parse — a rollback past a node type it introduced.
        // Leave it alone: failing every task with "not in the graph" would be
        // both wrong and irreversible, where waiting costs only a lease.
        if (graph.nodes().isEmpty() && !byNode.isEmpty()) {
            LOG.error("workflow execution {} ({}) has {} task(s) but an unreadable graph — "
                            + "not advancing; roll forward to a build that can read it",
                    execution.executionId(), execution.workflowName(), byNode.size());
            return;
        }

        // 1. Everything already open gets a look.
        for (WorkflowTask t : List.copyOf(byNode.values())) {
            if (t.state() != TaskState.RUNNING && t.state() != TaskState.AWAITING_APPROVAL) continue;
            WorkflowNode node = graph.nodeById(t.nodeId());
            if (node == null) {
                byNode.put(t.nodeId(), settle(t, TaskOutcome.failed(
                        "task '" + t.nodeId() + "' is not in the execution's graph", t.result()), now, execution));
                continue;
            }
            TaskOutcome outcome = guard(() -> dispatchPoll(node, context(execution, group, t, now)), t);
            byNode.put(t.nodeId(), settle(t, outcome, now, execution));
        }

        // 2. Start what has become ready and skip what never can, to a fixpoint:
        //    a skip can decide a downstream join, which can decide the next one.
        boolean deferred = false;   // a task another tick took; its real state is unknown here
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (WorkflowTask t : List.copyOf(byNode.values())) {
                if (t.state() != TaskState.PENDING) continue;
                JoinDecision decision = decide(graph, t.nodeId(), byNode);
                if (decision == JoinDecision.WAIT) continue;
                if (decision == JoinDecision.SKIP) {
                    byNode.put(t.nodeId(), settle(t, new TaskOutcome(TaskState.SKIPPED, null, t.result(),
                            "upstream branch was not taken", null, null), now, execution));
                    progressed = true;
                    continue;
                }
                // Take the task in the database BEFORE running it. The lease can
                // expire mid-advance (a slow fan-out, a wedged SMTP relay), and a
                // sibling replica that re-claims the execution must not start a
                // task this one is already running.
                int attempt = t.attempt() + 1;
                if (!tasks.claimForStart(t.taskId(), attempt, now)) {
                    LOG.info("workflow {} task {} was taken by another tick — leaving it",
                            execution.executionId(), t.nodeId());
                    byNode.remove(t.nodeId());   // re-read on the next tick rather than guess its state
                    deferred = true;
                    progressed = true;
                    continue;
                }
                WorkflowNode node = graph.nodeById(t.nodeId());
                WorkflowTask starting = withAttempt(t, attempt, now);
                TaskOutcome outcome =
                        guard(() -> dispatchStart(node, context(execution, group, starting, now)), t);
                byNode.put(t.nodeId(), settle(starting, outcome, now, execution));
                progressed = true;
            }
        }

        // 3. Settle the execution, or say when to look again.
        List<WorkflowTask> finalTasks = List.copyOf(byNode.values());
        // A task another tick took is absent from the map, so this tick cannot
        // know the execution is finished; a later one will.
        boolean open = deferred || finalTasks.stream().anyMatch(t -> !t.state().isTerminal());
        if (open) {
            executions.leaseUntil(execution.executionId(), nextTick(finalTasks, now));
            return;
        }
        ExecutionState state = outcomeOf(finalTasks);
        String reason = terminalReason(finalTasks);
        if (executions.markTerminal(execution.executionId(), state, reason, now) == 1) {
            LOG.info("workflow execution {} ({}) → {}{}",
                    execution.executionId(), execution.workflowName(), state,
                    reason == null ? "" : " — " + reason);
        }
    }

    // ── Task plumbing ──────────────────────────────────────────────

    private static TaskContext context(WorkflowExecution ex, ApplicationGroup group,
                                       WorkflowTask task, Instant now) {
        return new TaskContext(ex, group, task, now);
    }

    /** An executor that throws fails its task rather than the whole tick. */
    private TaskOutcome guard(java.util.function.Supplier<TaskOutcome> attempt, WorkflowTask task) {
        try {
            return attempt.get();
        } catch (RuntimeException e) {
            LOG.warn("workflow task {} ({}) threw: {}", task.taskId(), task.name(), e.toString());
            return TaskOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage(), task.result());
        }
    }

    private TaskOutcome dispatchStart(WorkflowNode node, TaskContext ctx) {
        return switch (node) {
            case HealthCheckNode n -> healthCheck.start(n, ctx);
            case LoadTestNode n    -> loadTest.start(n, ctx);
            case EmailNode n       -> email.start(n, ctx);
            case DelayNode n       -> delay.start(n, ctx);
            case ApprovalNode n    -> approval.start(n, ctx);
        };
    }

    private TaskOutcome dispatchPoll(WorkflowNode node, TaskContext ctx) {
        return switch (node) {
            case HealthCheckNode n -> healthCheck.poll(n, ctx);
            case LoadTestNode n    -> loadTest.poll(n, ctx);
            case EmailNode n       -> email.poll(n, ctx);
            case DelayNode n       -> delay.poll(n, ctx);
            case ApprovalNode n    -> approval.poll(n, ctx);
        };
    }

    private static WorkflowTask withAttempt(WorkflowTask t, int attempt, Instant startedAt) {
        return new WorkflowTask(t.taskId(), t.executionId(), t.nodeId(), t.type(), t.name(),
                t.state(), attempt, t.applicationName(), t.runId(),
                t.startedAt() == null ? startedAt : t.startedAt(),
                t.completedAt(), t.dueAt(), t.result(), t.errorReason());
    }

    /**
     * Write an outcome onto its task row. A load test's run id is attached with
     * the conditional update, so a row that already names a run keeps it — the
     * task-side half of "one task, one run".
     */
    private WorkflowTask settle(WorkflowTask task, TaskOutcome outcome, Instant now, WorkflowExecution ex) {
        String runId = task.runId();
        if (runId == null && outcome.runId() != null) {
            if (tasks.attachRun(task.taskId(), outcome.runId())) {
                runId = outcome.runId();
            } else {
                // Someone else attached first. Their run is the task's; taking
                // ours would point the row at a run this task does not own —
                // the update below writes RUN_ID unconditionally.
                runId = tasks.findById(task.taskId()).map(WorkflowTask::runId).orElse(null);
                LOG.warn("workflow {} task {} already names run {} — keeping it",
                        ex.executionId(), task.nodeId(), runId);
            }
        }
        WorkflowTask updated = new WorkflowTask(
                task.taskId(), task.executionId(), task.nodeId(), task.type(), task.name(),
                outcome.state(),
                // An executor that retries across ticks owns its own counter;
                // anything else keeps the one start() stamped.
                outcome.attempt() == null ? task.attempt() : outcome.attempt(),
                task.applicationName(), runId,
                task.startedAt() == null ? now : task.startedAt(),
                outcome.isSettled() ? now : null,
                outcome.dueAt(),
                outcome.result() == null ? task.result() : outcome.result(),
                outcome.errorReason());
        tasks.update(updated);
        if (outcome.isSettled() && outcome.state() != task.state()) {
            LOG.info("workflow {} task {} ({}) → {}{}", ex.executionId(), updated.nodeId(), updated.name(),
                    outcome.state(), outcome.errorReason() == null ? "" : " — " + outcome.errorReason());
        }
        return updated;
    }

    // ── Graph rules ────────────────────────────────────────────────

    /**
     * Whether a pending node may start. A node with no inbound edge is a root
     * and always ready; otherwise its {@link JoinPolicy} reads the inbound edges
     * whose sources have settled. An edge whose source was SKIPPED or CANCELLED
     * counts as decided-unsatisfied — nothing ran, so nothing downstream should.
     */
    static JoinDecision decide(WorkflowGraph graph, String nodeId, Map<String, WorkflowTask> byNode) {
        List<WorkflowEdge> inbound = graph.inboundOf(nodeId);
        if (inbound.isEmpty()) return JoinDecision.READY;
        WorkflowNode node = graph.nodeById(nodeId);
        JoinPolicy policy = node == null ? JoinPolicy.ALL : node.joinPolicy();

        int satisfied = 0;
        int refused = 0;
        for (WorkflowEdge e : inbound) {
            WorkflowTask source = byNode.get(e.source());
            if (source == null || !source.state().isTerminal()) continue;   // still undecided
            if (e.condition().satisfiedBy(source.state())) satisfied++;
            else refused++;
        }
        int undecided = inbound.size() - satisfied - refused;

        if (policy == JoinPolicy.ANY) {
            if (satisfied > 0) return JoinDecision.READY;
            return undecided > 0 ? JoinDecision.WAIT : JoinDecision.SKIP;
        }
        if (refused > 0) return JoinDecision.SKIP;
        return satisfied == inbound.size() ? JoinDecision.READY : JoinDecision.WAIT;
    }

    /**
     * The execution's verdict: CANCELLED if any task was, else FAILED if any
     * task failed, else SUCCEEDED. A skipped task is not a failure — nothing
     * ran, so nothing failed.
     *
     * <p>An {@code ON_FAILURE} branch deliberately does <em>not</em> forgive
     * the failure it handles: forgiving made a run whose load test failed read
     * SUCCEEDED in the history because an alert email was wired up, while the
     * email that same branch sent said FAILED. This is the one verdict both
     * the chip and the email use, so they cannot disagree again.
     */
    public static ExecutionState outcomeOf(List<WorkflowTask> tasks) {
        for (WorkflowTask t : tasks) {
            if (t.state() == TaskState.CANCELLED) return ExecutionState.CANCELLED;
        }
        for (WorkflowTask t : tasks) {
            if (t.state() == TaskState.FAILED) return ExecutionState.FAILED;
        }
        return ExecutionState.SUCCEEDED;
    }

    /**
     * Why the execution ended as it did, in the operator's terms.
     *
     * <p>Skipped tasks are named even on a SUCCEEDED execution: a branch that
     * was not taken is exactly why an email someone expected never arrived, and
     * finishing green with nothing said about it is how that becomes a mystery.
     */
    static String terminalReason(List<WorkflowTask> tasks) {
        List<String> parts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (WorkflowTask t : tasks) {
            if (t.state() == TaskState.FAILED) {
                failures.add(t.name() + (t.errorReason() == null ? "" : ": " + t.errorReason()));
            } else if (t.state() == TaskState.SKIPPED) {
                skipped.add(t.name());
            }
        }
        if (!failures.isEmpty()) parts.add(String.join("; ", failures));
        if (!skipped.isEmpty()) {
            parts.add(skipped.size() + " task(s) did not run: " + String.join(", ", skipped));
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    /** The earliest moment any open task wants attention; an untimed wait still heartbeats. */
    private Instant nextTick(List<WorkflowTask> tasks, Instant now) {
        Instant earliest = null;
        for (WorkflowTask t : tasks) {
            if (t.state().isTerminal()) continue;
            Instant due = t.dueAt() == null ? now.plusSeconds(idlePollSeconds) : t.dueAt();
            if (earliest == null || due.isBefore(earliest)) earliest = due;
        }
        if (earliest == null) earliest = now.plusSeconds(idlePollSeconds);
        // Never schedule in the past: a due-now task would otherwise spin the
        // claim as fast as the sweep runs.
        Instant floor = now.plusSeconds(1);
        return earliest.isBefore(floor) ? floor : earliest;
    }

    /** Tasks whose type carries an application — the execution's metrics split keys. */
    static List<String> applicationsOf(List<WorkflowTask> tasks) {
        List<String> out = new ArrayList<>();
        for (WorkflowTask t : tasks) {
            if (t.type() == NodeType.LOAD_TEST && t.applicationName() != null
                    && !out.contains(t.applicationName())) {
                out.add(t.applicationName());
            }
        }
        return out;
    }
}
