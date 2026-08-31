package com.perf.globalorchestrator.report;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowTask;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a workflow email node's subject and body: {@code ${…}} placeholders
 * against the execution, then the optional task-summary table in the shared
 * report shell.
 *
 * <p>An unknown placeholder renders as empty text rather than failing the send —
 * a typo in a subject must not be why nobody hears the load test finished.
 *
 * <p>Use {@code ${execution.outcome}} in a result email, not
 * {@code ${execution.state}}: the state is whatever it is at send time, and a
 * final report is sent while the execution is still RUNNING by definition.
 * {@code outcome} is FAILED whenever any task failed — it reports the work, not
 * the orchestration. The execution's own verdict forgives a failure that a node
 * handles with an {@code ON_FAILURE} branch, which is right for the run's state
 * chip and wrong for a subject line saying how the test went.
 */
@Component
public class WorkflowEmailComposer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.\\-]{1,120})}");

    /** Substitute placeholders in a one-line field (a subject). */
    public String renderText(String template, WorkflowExecution execution, ApplicationGroup group,
                             List<WorkflowTask> tasks) {
        if (template == null) return "";
        Map<String, String> vars = variables(execution, group, tasks);
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The full HTML body: the rendered message, then the task table when asked for. */
    public String renderBody(String template, boolean includeSummary, WorkflowExecution execution,
                             ApplicationGroup group, List<WorkflowTask> tasks) {
        String message = renderText(template, execution, group, tasks);
        StringBuilder body = new StringBuilder();
        body.append("<div style=\"white-space:pre-wrap\">")
            .append(EmailLayout.escape(message))
            .append("</div>");
        if (includeSummary) {
            body.append(EmailLayout.h2("Tasks"));
            body.append(taskTable(tasks));
        }
        String subtitle = execution.workflowName() + " · " + group.name()
                + (group.teamName() == null ? "" : " · " + group.teamName());
        return EmailLayout.shell(execution.workflowName(), subtitle, null, body.toString());
    }

    private static String taskTable(List<WorkflowTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"").append(EmailLayout.TABLE).append("\">");
        sb.append("<tr><th style=\"").append(EmailLayout.TH).append("\">Task</th>")
          .append("<th style=\"").append(EmailLayout.TH).append("\">Type</th>")
          .append("<th style=\"").append(EmailLayout.TH).append("\">State</th>")
          .append("<th style=\"").append(EmailLayout.TH).append("\">Detail</th></tr>");
        for (WorkflowTask t : tasks) {
            String detail = t.errorReason() != null ? t.errorReason()
                    : t.runId() != null ? "run " + t.runId() : "";
            sb.append("<tr><td style=\"").append(EmailLayout.TD).append("\">")
              .append(EmailLayout.escape(t.name())).append("</td>")
              .append("<td style=\"").append(EmailLayout.TD).append("\">")
              .append(EmailLayout.escape(t.type().name())).append("</td>")
              .append("<td style=\"").append(EmailLayout.TD).append("\">")
              .append(stateChip(t.state().name())).append("</td>")
              .append("<td style=\"").append(EmailLayout.TD).append("\">")
              .append(EmailLayout.escape(detail)).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static String stateChip(String state) {
        String color = switch (state) {
            case "SUCCEEDED" -> "#137333";
            case "FAILED", "CANCELLED" -> "#b00020";
            case "SKIPPED" -> "#6b7280";
            default -> "#8a6d00";
        };
        return "<span style=\"color:" + color + ";font-weight:600\">" + EmailLayout.escape(state) + "</span>";
    }

    /**
     * The placeholder vocabulary. Per-task values are addressed by node id
     * ({@code ${task.healthA.state}}) because that is what the builder shows on
     * the canvas.
     */
    private static Map<String, String> variables(WorkflowExecution execution, ApplicationGroup group,
                                                 List<WorkflowTask> tasks) {
        Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("workflow.name", nullSafe(execution.workflowName()));
        v.put("workflow.id", nullSafe(execution.workflowId()));
        v.put("execution.id", nullSafe(execution.executionId()));
        v.put("execution.state", execution.state().name());
        // `outcome` answers "did the work succeed", which is what a result
        // email is about — NOT the execution's own verdict, which deliberately
        // forgives a failure whose node declares an ON_FAILURE branch. Wiring a
        // result email from both outcomes (what the builder's warning
        // recommends) is exactly such a branch, so reusing the execution's
        // verdict here would have every handled failure mail "SUCCEEDED".
        List<WorkflowTask> failed = tasks.stream()
                .filter(t -> t.state() == TaskState.FAILED)
                .toList();
        v.put("execution.outcome", failed.isEmpty() ? "SUCCEEDED" : "FAILED");
        v.put("execution.failedTasks",
                failed.stream().map(WorkflowTask::name).collect(java.util.stream.Collectors.joining(", ")));
        v.put("execution.startedAt", execution.startedAt() == null ? "" : execution.startedAt().toString());
        v.put("execution.triggeredBy", nullSafe(execution.triggeredBy()));
        v.put("group.id", nullSafe(group.groupId()));
        v.put("group.name", nullSafe(group.name()));
        v.put("group.team", nullSafe(group.teamName()));
        v.put("now", Instant.now().toString());

        Set<String> applications = new LinkedHashSet<>();
        Set<String> loadTests = new LinkedHashSet<>();
        for (WorkflowTask t : tasks) {
            v.put("task." + t.nodeId() + ".name", nullSafe(t.name()));
            v.put("task." + t.nodeId() + ".state", t.state().name());
            v.put("task." + t.nodeId() + ".application", nullSafe(t.applicationName()));
            v.put("task." + t.nodeId() + ".runId", nullSafe(t.runId()));
            v.put("task." + t.nodeId() + ".error", nullSafe(t.errorReason()));
            if (t.applicationName() != null) applications.add(t.applicationName());
            if (t.type() == com.perf.globalorchestrator.domain.NodeType.LOAD_TEST) loadTests.add(t.name());
        }
        v.put("applications", String.join(", ", applications));
        v.put("loadTests", String.join(", ", loadTests));
        return v;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
