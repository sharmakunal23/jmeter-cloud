package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.HealthCheckNode;
import com.perf.globalorchestrator.domain.HealthRequirement;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.service.HealthProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Probes an application's endpoints live and passes when enough of them answer
 * 2xx. Reads the registry every attempt rather than a snapshot, so an endpoint
 * added mid-workflow is honoured — and so a gate never passes on data the
 * background poller happened to collect a minute ago.
 *
 * <p>Retries occupy no thread: a failed attempt below {@code attempts} returns
 * RUNNING with {@code dueAt} one interval out.
 */
@Component
public class HealthCheckTaskExecutor implements WorkflowTaskExecutor<HealthCheckNode> {

    private final ApplicationRepository applications;
    private final HealthProbe probe;

    public HealthCheckTaskExecutor(ApplicationRepository applications, HealthProbe probe) {
        this.applications = applications;
        this.probe = probe;
    }

    @Override
    public TaskOutcome start(HealthCheckNode node, TaskContext ctx) {
        return attempt(node, ctx, 1);
    }

    @Override
    public TaskOutcome poll(HealthCheckNode node, TaskContext ctx) {
        if (ctx.task().dueAt() != null && ctx.now().isBefore(ctx.task().dueAt())) {
            return TaskOutcome.running(ctx.task().dueAt(), ctx.task().result());
        }
        return attempt(node, ctx, ctx.task().attempt() + 1);
    }

    private TaskOutcome attempt(HealthCheckNode node, TaskContext ctx, int attemptNo) {
        Optional<Application> app = applications.findVisibleByName(node.application());
        if (app.isEmpty()) {
            // Archived counts as gone: validation reads the visible registry
            // too, so an app archived mid-execution stops its gate rather than
            // quietly passing one the operator retired.
            return TaskOutcome.failed(
                    "application '" + node.application() + "' is not registered (or was archived)", null);
        }
        List<String> endpoints = app.get().healthEndpoints();
        if (endpoints.isEmpty()) {
            // An application with nothing to check cannot be a gate — saying so
            // beats passing and letting a load test hit a dead service.
            return TaskOutcome.failed(
                    "application '" + node.application() + "' has no health endpoints configured", null);
        }

        List<Map<String, Object>> details =
                probe.probeAll(endpoints, Duration.ofSeconds(node.timeoutSeconds()));
        int ok = HealthProbe.okCount(details);
        int required = requiredCount(node, endpoints.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", node.application());
        result.put("attempt", attemptNo);
        result.put("healthy", ok);
        result.put("total", endpoints.size());
        result.put("required", required);
        result.put("status", HealthProbe.aggregate(details).name());
        result.put("endpoints", details);

        // Every branch reports attemptNo: the engine cannot tell a real attempt
        // from a not-due-yet poll, so an unreported one retries forever.
        if (ok >= required) {
            return TaskOutcome.succeeded(result, attemptNo);
        }
        if (attemptNo < node.attempts()) {
            return TaskOutcome.retrying(ctx.now().plusSeconds(node.intervalSeconds()), result, attemptNo);
        }
        return TaskOutcome.failed(
                ok + " of " + endpoints.size() + " endpoint(s) healthy, " + required
                        + " required, after " + attemptNo + " attempt(s)", result, attemptNo);
    }

    /** AT_LEAST is clamped to the endpoint count: asking for 5 of 3 is a config error, not an unpassable gate. */
    private static int requiredCount(HealthCheckNode node, int endpointCount) {
        if (node.requirement() == HealthRequirement.ANY) return 1;
        if (node.requirement() == HealthRequirement.AT_LEAST) {
            return Math.min(node.minHealthy() == null ? 1 : node.minHealthy(), endpointCount);
        }
        return endpointCount;
    }
}
