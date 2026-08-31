package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.service.WorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A running or finished workflow execution: its tasks, the runs its load tests
 * launched, and the two decisions an operator can make — cancel it, or answer
 * an approval.
 *
 * <p>Cancelling settles the tasks; it deliberately does not abort a run a load
 * test already started, because stopping someone's load test is its own
 * decision and the run page is where it is made.
 */
@RestController
@RequestMapping("/api/v1/workflowExecutions")
public class WorkflowExecutionController {

    private final WorkflowService service;

    public WorkflowExecutionController(WorkflowService service) {
        this.service = service;
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<WorkflowExecution> get(@PathVariable String executionId) {
        return ResponseEntity.ok(service.requireExecution(executionId));
    }

    /** The execution's run ids, in node order — what the metrics panel charts, split by application. */
    @GetMapping("/{executionId}/runs")
    public ResponseEntity<List<String>> runs(@PathVariable String executionId) {
        service.requireExecution(executionId);
        return ResponseEntity.ok(service.runIdsOf(executionId));
    }

    @PostMapping("/{executionId}/cancel")
    public ResponseEntity<WorkflowExecution> cancel(@PathVariable String executionId,
                                                     @RequestHeader(value = "X-Actor", required = false)
                                                     String actorHeader) {
        return ResponseEntity.ok(service.cancel(executionId, Actor.fromHeader(actorHeader)));
    }

    @PostMapping("/{executionId}/tasks/{taskId}/approve")
    public ResponseEntity<WorkflowExecution> approve(@PathVariable String executionId,
                                                      @PathVariable String taskId,
                                                      @RequestBody(required = false) ApprovalDecision body,
                                                      @RequestHeader(value = "X-Actor", required = false)
                                                      String actorHeader) {
        return ResponseEntity.ok(service.decideApproval(executionId, taskId, true,
                body == null ? null : body.note(), Actor.fromHeader(actorHeader)));
    }

    @PostMapping("/{executionId}/tasks/{taskId}/reject")
    public ResponseEntity<WorkflowExecution> reject(@PathVariable String executionId,
                                                     @PathVariable String taskId,
                                                     @RequestBody(required = false) ApprovalDecision body,
                                                     @RequestHeader(value = "X-Actor", required = false)
                                                     String actorHeader) {
        return ResponseEntity.ok(service.decideApproval(executionId, taskId, false,
                body == null ? null : body.note(), Actor.fromHeader(actorHeader)));
    }

    /** Why, in the approver's words; shown on the task and in any summary email. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApprovalDecision(String note) {}
}
