package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowExecutionSummary;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.service.WorkflowService;
import com.perf.globalorchestrator.service.WorkflowValidation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Workflows, scoped to an application group. {@code GET /groups} is the
 * landing surface — every group with its workflow count and who owns it — and
 * everything else works on one group's workflows or one workflow.
 *
 * <p>{@code POST /validate} runs the same checks a save does without saving,
 * so the builder can show errors and the capacity picture while the operator
 * draws.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final int MAX_NAME = 255;
    private static final int DEFAULT_HISTORY = 25;
    private static final int MAX_HISTORY = 200;

    private final WorkflowService service;
    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final ApplicationGroupRepository groups;

    public WorkflowController(WorkflowService service, WorkflowRepository workflows,
                              WorkflowExecutionRepository executions, ApplicationGroupRepository groups) {
        this.service = service;
        this.workflows = workflows;
        this.executions = executions;
        this.groups = groups;
    }

    /** The Workflows landing page: one row per group, with its workflow count and owning team. */
    @GetMapping("/groups")
    public ResponseEntity<List<WorkflowGroupSummary>> listGroups() {
        Map<String, Integer> counts = workflows.countsByGroup();
        List<WorkflowGroupSummary> out = groups.findAll().stream()
                .map(g -> new WorkflowGroupSummary(g.groupId(), g.name(), g.description(), g.teamName(),
                        counts.getOrDefault(g.groupId(), 0), g.notifyTo(), g.notifyCc(), g.notifyBcc()))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping
    public ResponseEntity<List<Workflow>> list(@RequestParam String groupId) {
        service.requireGroup(groupId);
        return ResponseEntity.ok(service.listByGroup(groupId));
    }

    /**
     * One workflow, carrying its most recent execution — the UI disables Run
     * and Edit while that one is still going, and only this tells it so.
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<Workflow> get(@PathVariable String workflowId) {
        Workflow workflow = service.requireWorkflow(workflowId);
        return ResponseEntity.ok(workflow.withLastExecution(
                executions.findByWorkflow(workflowId, 1).stream()
                        .findFirst()
                        .map(e -> new WorkflowExecutionSummary(
                                e.executionId(), e.state(), e.startedAt(), e.completedAt()))
                        .orElse(null)));
    }

    /** Validate a draft without saving it — errors, warnings and the peak-workers picture. */
    @PostMapping("/validate")
    public ResponseEntity<WorkflowValidation> validate(@RequestBody ValidateWorkflowRequest req) {
        return ResponseEntity.ok(service.validate(req.groupId(), req.graph()));
    }

    @PostMapping
    public ResponseEntity<Workflow> create(@RequestBody SaveWorkflowRequest req,
                                           @RequestHeader(value = "X-Actor", required = false) String actorHeader) {
        Workflow created = service.create(req.groupId(), name(req.name()), req.description(), req.graph(),
                req.enabled() == null || req.enabled(), Actor.fromHeader(actorHeader));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Save over the revision the caller holds. A stale revision is
     * {@code 409 WORKFLOW_REVISION_CONFLICT} — two operators on one canvas
     * never silently overwrite each other.
     */
    @PutMapping("/{workflowId}")
    public ResponseEntity<Workflow> update(@PathVariable String workflowId,
                                           @RequestBody SaveWorkflowRequest req,
                                           @RequestHeader(value = "X-Actor", required = false) String actorHeader) {
        if (req.revision() == null) {
            throw new IllegalArgumentException("revision is required — send the one you loaded");
        }
        return ResponseEntity.ok(service.update(workflowId, req.revision(), name(req.name()), req.description(),
                req.graph(), req.enabled() == null || req.enabled(), Actor.fromHeader(actorHeader)));
    }

    /** Idempotent; refused while an execution is running. */
    @DeleteMapping("/{workflowId}")
    public ResponseEntity<Void> delete(@PathVariable String workflowId) {
        service.delete(workflowId);
        return ResponseEntity.noContent().build();
    }

    /** Start it. The capacity pre-flight refuses the whole execution before any task runs. */
    @PostMapping("/{workflowId}/executions")
    public ResponseEntity<WorkflowExecution> launch(@PathVariable String workflowId,
                                                     @RequestHeader(value = "X-Actor", required = false)
                                                     String actorHeader) {
        WorkflowExecution execution = service.launch(workflowId, Actor.fromHeader(actorHeader));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(execution);
    }

    /** Newest first. Task rows are omitted — the execution's own endpoint carries those. */
    @GetMapping("/{workflowId}/executions")
    public ResponseEntity<List<WorkflowExecution>> history(@PathVariable String workflowId,
                                                            @RequestParam(required = false) Integer limit) {
        service.requireWorkflow(workflowId);
        int rows = limit == null ? DEFAULT_HISTORY : Math.clamp(limit, 1, MAX_HISTORY);
        return ResponseEntity.ok(executions.findByWorkflow(workflowId, rows));
    }

    private static String name(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String value = raw.trim();
        if (value.length() > MAX_NAME) {
            throw new IllegalArgumentException("name > " + MAX_NAME + " chars");
        }
        return value;
    }

    // ── Bodies ─────────────────────────────────────────────────────

    /** A group as the Workflows tab lists it: identity, owner, and how many workflows it holds. */
    public record WorkflowGroupSummary(String groupId, String name, String description, String teamName,
                                       int workflowCount, List<String> notifyTo, List<String> notifyCc,
                                       List<String> notifyBcc) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaveWorkflowRequest(String groupId, String name, String description, WorkflowGraph graph,
                                      Boolean enabled,
                                      /** Required on PUT: the revision the editor loaded. Ignored on POST. */
                                      Integer revision) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidateWorkflowRequest(String groupId, WorkflowGraph graph) {}

    /** A duplicate name inside one group. */
    @org.springframework.web.bind.annotation.ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> duplicateName(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "WORKFLOW_NAME_TAKEN",
                "message", "a workflow with that name already exists in this group"));
    }
}
