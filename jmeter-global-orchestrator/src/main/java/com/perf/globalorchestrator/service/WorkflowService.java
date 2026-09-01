package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.ApprovalNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.HealthCheckNode;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import com.perf.globalorchestrator.service.WorkflowValidation.RegionDemand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The workflow surface behind the API: validate a graph, save it, launch it,
 * and settle the operator actions the engine cannot decide for itself.
 *
 * <p>Validation runs in two layers — {@link WorkflowGraphValidator} for the
 * shape and this class for the references only the registry can check (does
 * this application exist, is it in this group, is this cluster reserved).
 * Capacity <em>warns</em> here and <em>blocks</em> in {@link #launch}: a graph
 * is a draft that outlives today's reservation, but an execution that would
 * exceed it must never start half of itself.
 */
@Service
public class WorkflowService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final WorkflowTaskRepository tasks;
    private final ApplicationGroupRepository groups;
    private final ApplicationRepository applications;
    private final GroupCapacityRepository capacity;

    public WorkflowService(WorkflowRepository workflows, WorkflowExecutionRepository executions,
                           WorkflowTaskRepository tasks, ApplicationGroupRepository groups,
                           ApplicationRepository applications, GroupCapacityRepository capacity) {
        this.workflows = workflows;
        this.executions = executions;
        this.tasks = tasks;
        this.groups = groups;
        this.applications = applications;
        this.capacity = capacity;
    }

    // ── Reads ──────────────────────────────────────────────────────

    public ApplicationGroup requireGroup(String groupId) {
        return groups.findById(groupId).orElseThrow(() -> new GroupMissingException(groupId));
    }

    public Workflow requireWorkflow(String workflowId) {
        return workflows.findById(workflowId).orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }

    /** A group's workflows, each carrying its most recent execution — one extra statement, not one per row. */
    public List<Workflow> listByGroup(String groupId) {
        var last = workflows.lastExecutionsByWorkflow(groupId);
        return workflows.findByGroup(groupId).stream()
                .map(w -> w.withLastExecution(last.get(w.workflowId())))
                .toList();
    }

    public WorkflowExecution requireExecution(String executionId) {
        WorkflowExecution e = executions.findById(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
        return e.withTasks(tasks.findByExecution(executionId));
    }

    // ── Validation ─────────────────────────────────────────────────

    /**
     * Everything wrong with a graph, plus its capacity picture. Safe to call on
     * every keystroke-free save: it reads the registry and the group's
     * reservations, and touches no external service.
     */
    public WorkflowValidation validate(String groupId, WorkflowGraph graph) {
        List<String> errors = new ArrayList<>(WorkflowGraphValidator.validate(graph));
        ApplicationGroup group = groups.findById(groupId).orElse(null);
        if (group == null) {
            errors.add("application group '" + groupId + "' does not exist");
        }
        // Answer on the shape alone when the shape is already wrong: the
        // reference checks below read the graph, and a caller that sent none
        // deserves the 400 that says so rather than a 500.
        if (!errors.isEmpty()) {
            return WorkflowValidation.invalid(errors);
        }
        errors.addAll(referentialErrors(groupId, graph));
        if (!errors.isEmpty()) {
            return WorkflowValidation.invalid(errors);
        }

        List<RegionDemand> demand = capacityDemand(groupId, graph);
        List<String> warnings = new ArrayList<>(silencedNotifications(graph));
        for (RegionDemand d : demand) {
            if (!d.fits()) {
                warnings.add("cluster '" + d.region() + "': this workflow can want " + d.peakWorkers()
                        + " workers at once (" + String.join(" + ", d.tasks()) + ") but the group reserves "
                        + d.reserved() + " — it will be refused at launch");
            }
        }
        return WorkflowValidation.ok(warnings, demand);
    }

    /**
     * References the registry owns: the applications named, and the clusters
     * allocated. The registry is read <em>once</em> and indexed — the builder
     * validates on every pause in typing, and a lookup per node would be up to
     * 64 queries a keystroke.
     */
    private List<String> referentialErrors(String groupId, WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        Map<String, Integer> reserved = reservedByRegion(groupId);
        Map<String, Application> byName = new LinkedHashMap<>();
        for (Application a : applications.findAll()) byName.put(a.name(), a);
        for (WorkflowNode node : graph.nodes()) {
            String appName = node.applicationName();
            if (appName != null) {
                Optional<Application> app = Optional.ofNullable(byName.get(appName));
                if (app.isEmpty()) {
                    errors.add("task '" + node.name() + "': no application named '" + appName + "'");
                } else if (!groupId.equals(app.get().metricsGroupId())) {
                    // A workflow draws on ONE group's pool; an app from another
                    // group would spend capacity nobody reserved.
                    errors.add("task '" + node.name() + "': application '" + appName
                            + "' belongs to group '" + app.get().metricsGroupId() + "', not '" + groupId + "'");
                }
            }
            if (node instanceof LoadTestNode lt) {
                for (var rc : lt.fleetAllocation()) {
                    if (!reserved.containsKey(rc.region())) {
                        errors.add("task '" + node.name() + "': the group has no capacity reserved in cluster '"
                                + rc.region() + "'");
                    }
                }
            }
        }
        return errors;
    }

    /**
     * Warn where a failure would silence a notification.
     *
     * <p>A task whose outgoing links are all "on success" stops everything below
     * it the moment it fails — which is right, but it means the "email me the
     * result" at the end of a chain quietly never runs on the day it matters
     * most. Naming the pair at design time beats discovering it from an
     * execution that finished green with nothing sent.
     */
    private List<String> silencedNotifications(WorkflowGraph graph) {
        List<String> warnings = new ArrayList<>();
        for (WorkflowNode node : graph.nodes()) {
            List<WorkflowEdge> out = graph.outboundOf(node.id());
            if (out.isEmpty()) continue;
            // An ON_FAILURE or ALWAYS link carries a failure onward; only a node
            // with neither dead-ends its whole subtree.
            boolean carriesFailure = out.stream().anyMatch(
                    e -> e.condition() == EdgeCondition.ON_FAILURE || e.condition() == EdgeCondition.ALWAYS);
            if (carriesFailure) continue;

            List<String> silenced = reachableEmails(graph, node.id());
            if (!silenced.isEmpty()) {
                warnings.add("if '" + node.name() + "' fails, no email is sent by "
                        + String.join(", ", silenced.stream().map(n -> "'" + n + "'").toList())
                        + " — add an 'on failure' link from '" + node.name() + "', or set the email's"
                        + " join to 'any' and link it from both outcomes");
            }
        }
        return warnings;
    }

    /** Email tasks downstream of {@code fromId}, by name, in graph order. */
    private static List<String> reachableEmails(WorkflowGraph graph, String fromId) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(fromId));
        while (!queue.isEmpty()) {
            for (WorkflowEdge e : graph.outboundOf(queue.poll())) {
                if (seen.add(e.target())) queue.add(e.target());
            }
        }
        List<String> emails = new ArrayList<>();
        for (WorkflowNode n : graph.nodes()) {
            if (n.type() == NodeType.EMAIL && seen.contains(n.id())) emails.add(n.name());
        }
        return emails;
    }

    private Map<String, Integer> reservedByRegion(String groupId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (GroupCapacity c : capacity.findByGroupId(groupId)) {
            out.put(c.region(), c.maxAvailable());
        }
        return out;
    }

    /** Peak demand against reservation, per cluster the graph touches. */
    private List<RegionDemand> capacityDemand(String groupId, WorkflowGraph graph) {
        ConcurrencyAnalyzer analyzer = new ConcurrencyAnalyzer(graph);
        Map<String, Integer> reserved = reservedByRegion(groupId);
        List<RegionDemand> out = new ArrayList<>();
        for (String region : analyzer.regions()) {
            ConcurrencyAnalyzer.Peak peak = analyzer.peakFor(region);
            int budget = reserved.getOrDefault(region, 0);
            out.add(new RegionDemand(region, peak.total(), peak.tasks(), budget, peak.total() <= budget));
        }
        return out;
    }

    // ── Writes ─────────────────────────────────────────────────────

    public Workflow create(String groupId, String name, String description, WorkflowGraph graph,
                           boolean enabled, Actor actor) {
        requireGroup(groupId);
        WorkflowValidation validation = validate(groupId, graph);
        if (!validation.valid()) throw new WorkflowInvalidException(validation);
        Instant now = Instant.now();
        return workflows.insert(new Workflow(Ulid.generate(), groupId, name, description, graph, enabled,
                1, actor.name(), now, actor.name(), now, null));
    }

    public Workflow update(String workflowId, int revision, String name, String description,
                           WorkflowGraph graph, boolean enabled, Actor actor) {
        Workflow existing = requireWorkflow(workflowId);
        // An execution snapshots the graph, so an edit could not corrupt one —
        // but it would leave the canvas showing something the run in progress
        // is not doing, which is worse than refusing.
        //
        // Only the GRAPH is refused, though: PUT is the sole mutation route, so
        // guarding the whole request would also block disabling a workflow to
        // stop the next launch — exactly what an operator reaches for while one
        // is running.
        if (!Objects.equals(existing.graph(), graph)) {
            int running = executions.countRunning(workflowId);
            if (running > 0) {
                throw new WorkflowBusyException(workflowId, running);
            }
        }
        WorkflowValidation validation = validate(existing.groupId(), graph);
        if (!validation.valid()) throw new WorkflowInvalidException(validation);
        return workflows.update(workflowId, revision, name, description, graph, enabled, actor.name(),
                        Instant.now())
                .orElseThrow(() -> new WorkflowRevisionConflictException(workflowId, revision,
                        requireWorkflow(workflowId).revision()));
    }

    /** Refused while an execution is still running — deleting the definition mid-flight strands the engine. */
    @Transactional
    public DeleteResult delete(String workflowId, boolean cancelRunning, Actor actor) {
        Workflow existing = requireWorkflow(workflowId);
        int running = executions.countRunning(workflowId);
        if (running > 0 && !cancelRunning) {
            throw new WorkflowBusyException(workflowId, running);
        }
        int cancelled = 0;
        if (running > 0) {
            Instant now = Instant.now();
            String why = "workflow deleted by " + actor.name();
            for (WorkflowExecution e : executions.findRunning(workflowId)) {
                tasks.cancelUnfinished(e.executionId(), now, why);
                executions.markTerminal(e.executionId(), ExecutionState.CANCELLED, why, now);
                cancelled++;
            }
        }
        int removed = executions.deleteForWorkflow(workflowId);
        workflows.delete(existing.workflowId());
        LOG.info("workflow {} ({}) deleted by {} — {} execution(s) removed, {} cancelled first",
                workflowId, existing.name(), actor.name(), removed, cancelled);
        return new DeleteResult(cancelled, removed);
    }

    /** What a delete actually did, so the UI can say it rather than guess. */
    public record DeleteResult(int cancelledExecutions, int deletedExecutions) {}

    // ── Archiving a workflow's runs ────────────────────────────────

    /** Finished runs only; one still going is skipped rather than refused. */
    @Transactional
    public int archiveExecutions(String workflowId, List<String> executionIds) {
        requireWorkflow(workflowId);
        return executions.archive(workflowId, executionIds, Instant.now());
    }

    @Transactional
    public int restoreExecutions(String workflowId, List<String> executionIds) {
        requireWorkflow(workflowId);
        return executions.restore(workflowId, executionIds);
    }

    /** Permanent, and only for rows already archived — that is what makes it deliberate. */
    @Transactional
    public int deleteExecutions(String workflowId, List<String> executionIds) {
        requireWorkflow(workflowId);
        return executions.deleteArchived(workflowId, executionIds);
    }

    /**
     * Open an execution: validate, pre-flight the capacity, write the execution
     * and one task per node, and let the engine take it from the next tick.
     *
     * <p>The capacity pre-flight is the hard gate — a graph whose peak exceeds
     * the group's reservation is refused before any task runs, rather than
     * passing its health checks, sending its "starting now" mail and then
     * hitting a 409 on the third load test.
     */
    @Transactional
    public WorkflowExecution launch(String workflowId, Actor actor) {
        // Serialise launches of THIS workflow before reading how many are
        // running: the check below is a count-then-insert, so without the lock
        // the scheduler's tick and an operator's "Run now" both read zero and
        // both insert — two executions of one graph, each capacity-cleared
        // against the same reservation.
        if (!workflows.lockForLaunch(workflowId)) {
            throw new WorkflowNotFoundException(workflowId);
        }
        Workflow workflow = requireWorkflow(workflowId);
        if (!workflow.enabled()) {
            throw new WorkflowDisabledException(workflowId);
        }
        // One execution of a workflow at a time. The capacity pre-flight below
        // reasons about THIS graph in isolation, so a second concurrent copy
        // would be cleared against the same reservation and the two together
        // could exceed it — exactly the half-run the pre-flight exists to
        // prevent. (Two DIFFERENT workflows in one group can still overlap;
        // their per-run gate in RunService is the backstop.)
        int running = executions.countRunning(workflowId);
        if (running > 0) {
            throw new WorkflowAlreadyRunningException(workflowId, running);
        }
        WorkflowValidation validation = validate(workflow.groupId(), workflow.graph());
        if (!validation.valid()) throw new WorkflowInvalidException(validation);
        List<RegionDemand> over = validation.overSubscribed();
        if (!over.isEmpty()) throw new WorkflowCapacityExceededException(over);

        Instant now = Instant.now();
        WorkflowExecution execution = executions.insert(new WorkflowExecution(
                Ulid.generate(), workflow.workflowId(), workflow.groupId(), workflow.name(),
                workflow.graph(), ExecutionState.RUNNING, null, actor.name(), now, null,
                // Due immediately: the next tick starts the roots.
                now, List.of()));

        List<WorkflowTask> rows = new ArrayList<>(workflow.graph().nodes().size());
        for (WorkflowNode node : workflow.graph().nodes()) {
            rows.add(new WorkflowTask(Ulid.generate(), execution.executionId(), node.id(), node.type(),
                    node.name(), TaskState.PENDING, 0, node.applicationName(), null,
                    null, null, null, null, null));
        }
        tasks.insertAll(rows);

        LOG.info("workflow execution {} opened for {} ({}) with {} task(s) by {}",
                execution.executionId(), workflow.name(), workflow.workflowId(), rows.size(), actor.name());
        return execution.withTasks(rows);
    }

    /** Stop an execution: settle every open task, then the execution itself. */
    @Transactional
    public WorkflowExecution cancel(String executionId, Actor actor) {
        WorkflowExecution execution = executions.findById(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
        if (execution.state().isTerminal()) {
            throw new ExecutionNotCancellableException(executionId, execution.state());
        }
        Instant now = Instant.now();
        // Any run a load-test task started keeps going: aborting someone's load
        // test is a separate, explicit decision, and the run page is where it
        // is made. The task stops being watched, which is what cancel means here.
        tasks.cancelUnfinished(executionId, now, "cancelled by " + actor.name());
        executions.markTerminal(executionId, ExecutionState.CANCELLED, "cancelled by " + actor.name(), now);
        return requireExecution(executionId);
    }

    /**
     * Answer an approval task and pull the execution's next tick forward, so the
     * branch continues within a tick rather than at the idle poll.
     */
    @Transactional
    public WorkflowExecution decideApproval(String executionId, String taskId, boolean approved,
                                            String note, Actor actor) {
        WorkflowExecution execution = executions.findById(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
        WorkflowTask task = tasks.findById(taskId)
                .filter(t -> t.executionId().equals(executionId))
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.state() != TaskState.AWAITING_APPROVAL) {
            throw new TaskNotAwaitingApprovalException(taskId, task.state());
        }
        WorkflowNode node = execution.graph().nodeById(task.nodeId());
        String instructions = node instanceof ApprovalNode a ? a.instructions() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        if (instructions != null) result.put("instructions", instructions);
        result.put("decision", approved ? "APPROVED" : "REJECTED");
        result.put("decidedBy", actor.name());
        if (note != null && !note.isBlank()) result.put("note", note);

        Instant now = Instant.now();
        tasks.update(new WorkflowTask(task.taskId(), task.executionId(), task.nodeId(), task.type(),
                task.name(), approved ? TaskState.SUCCEEDED : TaskState.FAILED, task.attempt(),
                task.applicationName(), task.runId(), task.startedAt(), now, null, result,
                approved ? null : "rejected by " + actor.name()));
        // Bring the tick forward so the branch continues within a sweep rather
        // than at the idle poll; forward-only, so an execution already due is
        // left where it is.
        executions.nudge(executionId, now);
        return requireExecution(executionId);
    }

    /** Only load-test tasks and only the ones that launched — what the execution's metrics panel charts. */
    public List<String> runIdsOf(String executionId) {
        return tasks.runIdsOfExecution(executionId);
    }

    // ── Exceptions ─────────────────────────────────────────────────

    public static final class WorkflowNotFoundException extends RuntimeException {
        public WorkflowNotFoundException(String id) { super("workflow not found: " + id); }
    }

    public static final class ExecutionNotFoundException extends RuntimeException {
        public ExecutionNotFoundException(String id) { super("workflow execution not found: " + id); }
    }

    public static final class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String id) { super("workflow task not found: " + id); }
    }

    public static final class GroupMissingException extends RuntimeException {
        public GroupMissingException(String id) { super("application group not found: " + id); }
    }

    public static final class WorkflowDisabledException extends RuntimeException {
        public WorkflowDisabledException(String id) { super("workflow " + id + " is disabled"); }
    }

    public static final class WorkflowAlreadyRunningException extends RuntimeException {
        public WorkflowAlreadyRunningException(String id, int running) {
            super("workflow " + id + " already has " + running
                    + " running execution(s); cancel or wait for it before starting another");
        }
    }

    /** Something is running, so the workflow may be neither edited nor deleted. */
    public static final class WorkflowBusyException extends RuntimeException {
        private final int running;
        public WorkflowBusyException(String id, int running) {
            super("workflow " + id + " has " + running
                    + " running execution(s); wait for it to finish or cancel it first");
            this.running = running;
        }
        public int running() { return running; }
    }

    public static final class TaskNotAwaitingApprovalException extends RuntimeException {
        public TaskNotAwaitingApprovalException(String id, TaskState state) {
            super("task " + id + " is " + state + ", not awaiting approval");
        }
    }

    public static final class ExecutionNotCancellableException extends RuntimeException {
        public ExecutionNotCancellableException(String id, ExecutionState state) {
            super("execution " + id + " is already " + state);
        }
    }

    public static final class WorkflowRevisionConflictException extends RuntimeException {
        public WorkflowRevisionConflictException(String id, int sent, int current) {
            super("workflow " + id + " was saved by someone else (you have revision " + sent
                    + ", current is " + current + ")");
        }
    }

    public static final class WorkflowInvalidException extends RuntimeException {
        private final transient WorkflowValidation validation;
        public WorkflowInvalidException(WorkflowValidation validation) {
            super("workflow is not valid: " + String.join("; ", validation.errors()));
            this.validation = validation;
        }
        public WorkflowValidation validation() { return validation; }
    }

    public static final class WorkflowCapacityExceededException extends RuntimeException {
        private final transient List<RegionDemand> over;
        public WorkflowCapacityExceededException(List<RegionDemand> over) {
            super(over.stream()
                    .map(d -> "cluster '" + d.region() + "' needs " + d.peakWorkers()
                            + " workers at once but the group reserves " + d.reserved())
                    .reduce((a, b) -> a + "; " + b).orElse("capacity exceeded"));
            this.over = List.copyOf(over);
        }
        public List<RegionDemand> over() { return over; }
    }
}
