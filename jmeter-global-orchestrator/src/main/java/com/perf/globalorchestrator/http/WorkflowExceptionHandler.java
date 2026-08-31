package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.service.WorkflowService.ExecutionNotCancellableException;
import com.perf.globalorchestrator.service.WorkflowService.ExecutionNotFoundException;
import com.perf.globalorchestrator.service.WorkflowService.GroupMissingException;
import com.perf.globalorchestrator.service.WorkflowService.TaskNotAwaitingApprovalException;
import com.perf.globalorchestrator.service.WorkflowService.TaskNotFoundException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowAlreadyRunningException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowBusyException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowCapacityExceededException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowDisabledException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowInvalidException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowNotFoundException;
import com.perf.globalorchestrator.service.WorkflowService.WorkflowRevisionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workflow errors as codes a UI can branch on. The two that carry a body
 * beyond the message are the ones an operator has to act on:
 * {@code WORKFLOW_INVALID} returns every violation so the builder can mark all
 * the bad tasks at once, and {@code WORKFLOW_CAPACITY_EXCEEDED} names the
 * clusters, the peak and the tasks that make it up.
 */
@RestControllerAdvice(assignableTypes = {WorkflowController.class, WorkflowExecutionController.class})
public class WorkflowExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExceptionHandler.class);

    @ExceptionHandler({WorkflowNotFoundException.class, ExecutionNotFoundException.class,
            TaskNotFoundException.class, GroupMissingException.class})
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
    }

    @ExceptionHandler(WorkflowInvalidException.class)
    public ResponseEntity<Map<String, Object>> invalid(WorkflowInvalidException e) {
        return body(HttpStatus.BAD_REQUEST, "WORKFLOW_INVALID", e.getMessage(),
                Map.of("validation", e.validation()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), null);
    }

    @ExceptionHandler(WorkflowCapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> capacity(WorkflowCapacityExceededException e) {
        LOG.info("workflow launch refused on capacity: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, "WORKFLOW_CAPACITY_EXCEEDED", e.getMessage(),
                Map.of("clusters", e.over()));
    }

    @ExceptionHandler(WorkflowRevisionConflictException.class)
    public ResponseEntity<Map<String, Object>> revision(WorkflowRevisionConflictException e) {
        return body(HttpStatus.CONFLICT, "WORKFLOW_REVISION_CONFLICT", e.getMessage(), null);
    }

    @ExceptionHandler(WorkflowBusyException.class)
    public ResponseEntity<Map<String, Object>> busy(WorkflowBusyException e) {
        return body(HttpStatus.CONFLICT, "WORKFLOW_RUNNING", e.getMessage(),
                Map.of("runningExecutions", e.running()));
    }

    @ExceptionHandler(WorkflowAlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> alreadyRunning(WorkflowAlreadyRunningException e) {
        return body(HttpStatus.CONFLICT, "WORKFLOW_ALREADY_RUNNING", e.getMessage(), null);
    }

    @ExceptionHandler(WorkflowDisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(WorkflowDisabledException e) {
        return body(HttpStatus.CONFLICT, "WORKFLOW_DISABLED", e.getMessage(), null);
    }

    @ExceptionHandler(ExecutionNotCancellableException.class)
    public ResponseEntity<Map<String, Object>> notCancellable(ExecutionNotCancellableException e) {
        return body(HttpStatus.CONFLICT, "EXECUTION_NOT_CANCELLABLE", e.getMessage(), null);
    }

    @ExceptionHandler(TaskNotAwaitingApprovalException.class)
    public ResponseEntity<Map<String, Object>> notAwaiting(TaskNotAwaitingApprovalException e) {
        return body(HttpStatus.CONFLICT, "TASK_NOT_AWAITING_APPROVAL", e.getMessage(), null);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                            Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        if (extra != null) out.putAll(extra);
        return ResponseEntity.status(status).body(out);
    }
}
