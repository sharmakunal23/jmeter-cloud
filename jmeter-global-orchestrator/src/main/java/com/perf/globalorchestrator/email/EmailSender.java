package com.perf.globalorchestrator.email;

import java.util.List;

/**
 * Send a (HTML) email. {@link SmtpEmailSender} is the
 * single implementation (K8S-SLIMDOWN C-A removed the {@code -Pcloud} SES
 * backend): JavaMailSender over SMTP — a MailHog container in dev compose,
 * the deploy environment's relay in production (spring.mail env vars).
 *
 * <p>Used by the daily-report cron kinds (INFRA_READINESS now; DAILY_REPORT in
 * Phase D). Failures raise {@link EmailException} so the fire records FAILED.
 */
public interface EmailSender {

    /** Send one HTML email to all recipients. Throws {@link EmailException} on failure. */
    default void send(List<String> recipients, String subject, String htmlBody) {
        send(new EmailMessage(recipients, List.of(), List.of(), subject, htmlBody));
    }

    /** Send one HTML email with carbon copies. Throws {@link EmailException} on failure. */
    void send(EmailMessage message);

    /**
     * One addressed message. At least one of {@code to} / {@code cc} /
     * {@code bcc} must be non-empty — a message with no recipient at all is a
     * misconfigured workflow, not a silent no-op.
     */
    record EmailMessage(List<String> to, List<String> cc, List<String> bcc, String subject, String htmlBody) {

        public EmailMessage {
            to  = to  == null ? List.of() : List.copyOf(to);
            cc  = cc  == null ? List.of() : List.copyOf(cc);
            bcc = bcc == null ? List.of() : List.copyOf(bcc);
        }

        public boolean hasRecipients() {
            return !to.isEmpty() || !cc.isEmpty() || !bcc.isEmpty();
        }

        /** Every address the message reaches — for logs and the task's recorded result. */
        public List<String> allRecipients() {
            List<String> all = new java.util.ArrayList<>(to.size() + cc.size() + bcc.size());
            all.addAll(to);
            all.addAll(cc);
            all.addAll(bcc);
            return List.copyOf(all);
        }
    }

    /** A short label ("smtp" / "ses") for logs + the report preview. */
    String backend();

    /** Delivery failure — the caller records the fire as FAILED. */
    class EmailException extends RuntimeException {
        public EmailException(String message, Throwable cause) { super(message, cause); }
    }
}
