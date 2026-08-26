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
    void send(List<String> recipients, String subject, String htmlBody);

    /** A short label ("smtp" / "ses") for logs + the report preview. */
    String backend();

    /** Delivery failure — the caller records the fire as FAILED. */
    class EmailException extends RuntimeException {
        public EmailException(String message, Throwable cause) { super(message, cause); }
    }
}
