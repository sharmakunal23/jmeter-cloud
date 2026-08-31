package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.client.TemplateBody;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.LoadTestSuccess;
import com.perf.globalorchestrator.domain.RegionCount;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowOrigin;
import com.perf.globalorchestrator.http.FleetAllocationEntry;
import com.perf.globalorchestrator.http.StartRunRequest;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Launches a run from the node's template and watches it to a terminal state.
 *
 * <p><b>Launching is idempotent.</b> Every attempt first looks the task up in
 * {@code ORCH_RUN.WORKFLOW_TASK_ID}: a run already there is adopted, so a crash
 * between the run's insert and the task's update costs a tick, not a duplicate
 * fleet. The unique index makes the second launch impossible even if this check
 * were skipped.
 *
 * <p>The node's {@code fleetAllocation} wins over the template's. When the
 * counts differ or the node overrides properties, the per-worker property
 * snapshots are rebuilt from the template's {@code globalProperties} — those
 * snapshots are where a run's {@code -J} values live, so reusing the template's
 * allocation with different counts would silently drop them.
 */
@Component
public class LoadTestTaskExecutor implements WorkflowTaskExecutor<LoadTestNode> {

    private static final Logger LOG = LoggerFactory.getLogger(LoadTestTaskExecutor.class);

    private final RunService runService;
    private final RunRepository runs;
    private final DocumentServiceClient documentService;
    private final int pollSeconds;

    public LoadTestTaskExecutor(RunService runService, RunRepository runs,
                                DocumentServiceClient documentService,
                                @Value("${globalOrchestrator.workflow.runPollSeconds:15}") int pollSeconds) {
        this.runService = runService;
        this.runs = runs;
        this.documentService = documentService;
        this.pollSeconds = pollSeconds;
    }

    @Override
    public TaskOutcome start(LoadTestNode node, TaskContext ctx) {
        String taskId = ctx.task().taskId();

        Optional<Run> already = runs.findByWorkflowTaskId(taskId);
        if (already.isPresent()) {
            LOG.info("workflow task {} already owns run {} — adopting", taskId, already.get().runId());
            return watching(node, ctx, already.get());
        }

        TemplateBody template;
        try {
            template = documentService.fetchTemplate(node.templateBlobId());
        } catch (RuntimeException e) {
            return TaskOutcome.failed("template unavailable: " + e.getMessage(), null);
        }

        try {
            Run run = runService.startRun(
                    toStartRunRequest(node, template), false,
                    Actor.system("workflow"),
                    new WorkflowOrigin(ctx.execution().executionId(), taskId));
            return watching(node, ctx, run);
        } catch (RuntimeException e) {
            // The run row may have landed before the failure. Adopt it rather
            // than failing the task while a fleet is running under it.
            Optional<Run> stranded = runs.findByWorkflowTaskId(taskId);
            if (stranded.isPresent()) {
                LOG.warn("workflow task {} launch threw but run {} exists — adopting", taskId,
                        stranded.get().runId(), e);
                return watching(node, ctx, stranded.get());
            }
            return TaskOutcome.failed("launch failed: " + e.getMessage(), null);
        }
    }

    @Override
    public TaskOutcome poll(LoadTestNode node, TaskContext ctx) {
        String runId = ctx.task().runId();
        if (runId == null) {
            // The start crashed before the run id was recorded; start() adopts or relaunches.
            return start(node, ctx);
        }

        Run run;
        try {
            run = runService.refreshAndGet(runId);
        } catch (RuntimeException e) {
            return TaskOutcome.failed("run " + runId + " could not be read: " + e.getMessage(),
                    ctx.task().result());
        }

        if (!run.state().isTerminal()) {
            Instant started = ctx.task().startedAt() == null ? ctx.now() : ctx.task().startedAt();
            Duration cap = Duration.ofMinutes(node.maxDurationMinutes());
            if (ctx.now().isAfter(started.plus(cap))) {
                abortQuietly(runId, node);
                return TaskOutcome.failed(
                        "run " + runId + " exceeded its " + node.maxDurationMinutes() + " minute limit",
                        describe(run));
            }
            return TaskOutcome.runningWithRun(ctx.now().plusSeconds(pollSeconds), describe(run), runId);
        }

        boolean passed = node.successWhen() == LoadTestSuccess.ANY_TERMINAL
                || run.state() == RunState.COMPLETED;
        return passed
                ? TaskOutcome.succeeded(describe(run))
                : TaskOutcome.failed("run " + runId + " ended " + run.state()
                        + (run.stateReason() == null ? "" : ": " + run.stateReason()), describe(run));
    }

    /**
     * Record the run on every outcome, terminal included. An adopted run that
     * has already finished still has to reach the task row, or the execution's
     * metrics panel and {@code ${task.<id>.runId}} have nothing to point at.
     */
    private TaskOutcome watching(LoadTestNode node, TaskContext ctx, Run run) {
        if (run.state().isTerminal()) {
            boolean passed = node.successWhen() == LoadTestSuccess.ANY_TERMINAL
                    || run.state() == RunState.COMPLETED;
            return passed
                    ? TaskOutcome.settledWithRun(TaskState.SUCCEEDED, describe(run), null, run.runId())
                    : TaskOutcome.settledWithRun(TaskState.FAILED, describe(run),
                            "run " + run.runId() + " ended " + run.state(), run.runId());
        }
        return TaskOutcome.runningWithRun(ctx.now().plusSeconds(pollSeconds), describe(run), run.runId());
    }

    /** Best effort: the task is failing either way, and an abort that loses a race must not mask that. */
    private void abortQuietly(String runId, LoadTestNode node) {
        try {
            runService.abortRun(runId, Actor.system("workflow"),
                    "workflow task exceeded " + node.maxDurationMinutes() + " minutes");
        } catch (RuntimeException e) {
            LOG.warn("workflow watchdog could not abort run {}: {}", runId, e.toString());
        }
    }

    private static Map<String, Object> describe(Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.runId());
        m.put("runState", run.state().name());
        m.put("application", run.application());
        if (run.stateReason() != null) m.put("stateReason", run.stateReason());
        if (run.fleetMembers() != null) m.put("workers", run.fleetMembers().size());
        return m;
    }

    /**
     * The node's fleet with the template's plan. Package-private so the mapping
     * has a test of its own — it is the one place a workflow run could quietly
     * differ from the run the same template launches by hand.
     */
    static StartRunRequest toStartRunRequest(LoadTestNode node, TemplateBody template) {
        Map<String, Integer> templateCounts = new LinkedHashMap<>();
        for (FleetAllocationEntry e : template.fleetAllocation()) {
            templateCounts.merge(e.region(), e.count(), Integer::sum);
        }

        List<FleetAllocationEntry> allocation;
        if (node.matchesTemplateFleet(templateCounts)) {
            // Same fleet, no overrides — keep the template's per-worker snapshots,
            // which may carry deliberate per-pod differences.
            allocation = template.fleetAllocation();
        } else {
            Map<String, String> properties = new LinkedHashMap<>();
            if (template.globalProperties() != null) properties.putAll(template.globalProperties());
            properties.putAll(node.properties());
            allocation = new ArrayList<>(node.fleetAllocation().size());
            for (RegionCount rc : node.fleetAllocation()) {
                List<Map<String, String>> perNode = new ArrayList<>(rc.count());
                for (int i = 0; i < rc.count(); i++) perNode.add(properties);
                allocation.add(new FleetAllocationEntry(rc.region(), rc.count(), perNode));
            }
        }

        return new StartRunRequest(
                template.testPlanBlobId(),
                template.dataFilesBlobId(),
                node.application(),
                0,                 // fleetSize — unused when fleetAllocation is present
                List.of(),         // regions — the legacy single-region path
                allocation,
                null,              // initiatedBy — the workflow actor drives attribution
                // The launch pre-flight already proved this graph's peak fits the
                // group's reservation, so filling a shortfall spends a budget the
                // operator has approved. Refusing here would instead make every
                // workflow depend on someone pre-warming the pool.
                Boolean.TRUE,
                node.saveResults() == null ? template.saveResults() : node.saveResults(),
                template.pluginIds(),
                null);             // refreshDataFiles — never forced from a workflow
    }
}
