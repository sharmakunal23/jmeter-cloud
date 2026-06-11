package com.perf.globalorchestrator.email;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AUTOMATION Phase E — default email backend: JavaMailSender over SMTP. In dev
 * compose this points at a MailHog container ({@code spring.mail.host=mailhog},
 * port 1025) so the full email path — compose, render, send, receive — is
 * exercisable locally without AWS.
 *
 * <p>Spring Boot auto-configures the {@link JavaMailSender} from the
 * {@code spring.mail.*} properties; this bean only wires the
 * {@code from} address + the HTML send.
 */
@Component
@ConditionalOnProperty(name = "globalOrchestrator.automation.email.backend",
                       havingValue = "local", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail,
                           @Value("${globalOrchestrator.automation.email.from:jmeter-cloud@localhost}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(List<String> recipients, String subject, String htmlBody) {
        try {
            MimeMessage msg = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mail.send(msg);
        } catch (Exception e) {
            throw new EmailException("SMTP send to " + recipients + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String backend() {
        return "smtp";
    }
}
