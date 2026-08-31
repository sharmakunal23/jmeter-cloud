package com.perf.globalorchestrator.email;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default email backend: JavaMailSender over SMTP. In dev
 * compose this points at a MailHog container ({@code spring.mail.host=mailhog},
 * port 1025) so the full email path — compose, render, send, receive — is
 * exercisable locally without AWS.
 *
 * <p>Spring Boot auto-configures the {@link JavaMailSender} from the
 * {@code spring.mail.*} properties; this bean only wires the
 * {@code from} address + the HTML send.
 */
// K8S-SLIMDOWN C-A — unconditional: SMTP is the single email backend (the
// SES + backend-selector machinery is gone). This also retires a latent
// D-2 escape: the old @ConditionalOnProperty still read the renamed
// globalOrchestrator.* key and only matched via matchIfMissing.
@Component
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail,
                           @Value("${globalOrchestrator.automation.email.from:jmeter-cloud@localhost}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        if (!message.hasRecipients()) {
            throw new EmailException("no recipients: nothing to send '" + message.subject() + "' to", null);
        }
        try {
            MimeMessage msg = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            if (!message.to().isEmpty())  helper.setTo(message.to().toArray(new String[0]));
            if (!message.cc().isEmpty())  helper.setCc(message.cc().toArray(new String[0]));
            if (!message.bcc().isEmpty()) helper.setBcc(message.bcc().toArray(new String[0]));
            helper.setSubject(message.subject());
            helper.setText(message.htmlBody(), true);
            mail.send(msg);
        } catch (EmailException e) {
            throw e;
        } catch (Exception e) {
            // Bcc stays out of the message: an exception string is logged, and a
            // blind-copied address must not surface in a log line.
            throw new EmailException("SMTP send to " + message.to() + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String backend() {
        return "smtp";
    }
}
