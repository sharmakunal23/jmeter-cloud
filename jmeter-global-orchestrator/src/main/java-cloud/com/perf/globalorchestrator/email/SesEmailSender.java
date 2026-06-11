package com.perf.globalorchestrator.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

import jakarta.annotation.PreDestroy;
import java.util.List;

/**
 * AUTOMATION Phase E — cloud email backend (AWS SES). Only compiled + wired on
 * the {@code -Pcloud} build ({@code src/main/java-cloud}); selected at runtime
 * by {@code automation.email.backend=ses}. Mirrors document-service's
 * {@code S3BlobStore} (profile-gated, instance-profile / IRSA credentials via
 * the default provider chain — no static keys in config).
 *
 * <p>Region comes from {@code AWS_REGION} (the standard SDK env var) or the
 * {@code automation.email.ses.region} property; the SDK's default credentials
 * provider supplies credentials (EC2 instance profile / EKS IRSA / env).
 */
@Component
@ConditionalOnProperty(name = "globalOrchestrator.automation.email.backend", havingValue = "ses")
public class SesEmailSender implements EmailSender {

    private final SesClient ses;
    private final String from;

    public SesEmailSender(
            @Value("${globalOrchestrator.automation.email.from:jmeter-cloud@example.com}") String from,
            @Value("${globalOrchestrator.automation.email.ses.region:${AWS_REGION:us-east-1}}") String region) {
        this.from = from;
        this.ses = SesClient.builder().region(Region.of(region)).build();
    }

    @Override
    public void send(List<String> recipients, String subject, String htmlBody) {
        try {
            SendEmailRequest req = SendEmailRequest.builder()
                    .source(from)
                    .destination(Destination.builder().toAddresses(recipients).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                                    .build())
                            .build())
                    .build();
            ses.sendEmail(req);
        } catch (Exception e) {
            throw new EmailException("SES send to " + recipients + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String backend() {
        return "ses";
    }

    @PreDestroy
    void close() {
        ses.close();
    }
}
