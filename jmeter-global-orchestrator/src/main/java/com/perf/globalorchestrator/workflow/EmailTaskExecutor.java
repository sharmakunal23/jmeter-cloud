package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.EmailNode;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.email.EmailSender;
import com.perf.globalorchestrator.report.WorkflowEmailComposer;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the node's mail, inheriting any address list it leaves empty from the
 * group's {@code notifyTo} / {@code notifyCc} / {@code notifyBcc}.
 *
 * <p>Sends once and settles — there is no retry, because a resend that duplicates
 * "the load test has started" is worse than a missed notice, and the task's
 * failure is visible on the execution either way.
 */
@Component
public class EmailTaskExecutor implements WorkflowTaskExecutor<EmailNode> {

    private final EmailSender emailSender;
    private final WorkflowEmailComposer composer;
    private final WorkflowTaskRepository tasks;

    public EmailTaskExecutor(EmailSender emailSender, WorkflowEmailComposer composer,
                             WorkflowTaskRepository tasks) {
        this.emailSender = emailSender;
        this.composer = composer;
        this.tasks = tasks;
    }

    @Override
    public TaskOutcome start(EmailNode node, TaskContext ctx) {
        List<String> to  = node.to().isEmpty()  ? ctx.group().notifyTo()  : node.to();
        List<String> cc  = node.cc().isEmpty()  ? ctx.group().notifyCc()  : node.cc();
        List<String> bcc = node.bcc().isEmpty() ? ctx.group().notifyBcc() : node.bcc();

        // Read siblings now so ${task.…} reflects what has happened up to this
        // point — the whole reason an email node can sit mid-graph.
        List<WorkflowTask> siblings = tasks.findByExecution(ctx.execution().executionId());
        String subject = composer.renderText(node.subject(), ctx.execution(), ctx.group(), siblings);
        String body = composer.renderBody(node.body(), node.includeSummary(),
                ctx.execution(), ctx.group(), siblings);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", subject);
        result.put("to", to);
        result.put("cc", cc);
        result.put("bcc", bcc);

        EmailSender.EmailMessage message = new EmailSender.EmailMessage(to, cc, bcc, subject, body);
        if (!message.hasRecipients()) {
            return TaskOutcome.failed(
                    "no recipients: the node names none and the group has no notification defaults", result);
        }
        try {
            emailSender.send(message);
            result.put("sent", true);
            return TaskOutcome.succeeded(result);
        } catch (RuntimeException e) {
            result.put("sent", false);
            return TaskOutcome.failed("email send failed: " + e.getMessage(), result);
        }
    }

    /**
     * {@link #start} always settles, so a task reaching here was interrupted
     * between being claimed and its result being written. Report that honestly
     * rather than assume the send happened: an {@code ON_FAILURE} link is how a
     * workflow handles it, and silently claiming a notice went out is worse
     * than saying it might not have.
     */
    @Override
    public TaskOutcome poll(EmailNode node, TaskContext ctx) {
        return TaskOutcome.failed(
                "interrupted before the result was recorded — the message may or may not have been sent",
                ctx.task().result());
    }
}
