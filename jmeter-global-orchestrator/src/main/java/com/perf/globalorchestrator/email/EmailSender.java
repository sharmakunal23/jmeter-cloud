package com.perf.globalorchestrator.email;

import java.util.List;

/**
 * AUTOMATION Phase E — send a (HTML) email. Two implementations select by the
 * {@code automation.email.backend} property, mirroring document-service's
 * local-FS-vs-S3 blob-store split:
 *
 * <ul>
 *   <li>{@link SmtpEmailSender} (default, {@code local}) — JavaMailSender → a
 *       MailHog container in dev compose; the full path is testable locally.</li>
 *   <li>{@code SesEmailSender} ({@code ses}, only on the {@code -Pcloud} build,
 *       in {@code src/main/java-cloud}) — AWS SES.</li>
 * </ul>
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
