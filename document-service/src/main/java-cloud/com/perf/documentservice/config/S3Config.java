package com.perf.documentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * Wires the {@link S3Client} singleton used by
 * {@link com.perf.documentservice.store.S3BlobStore}. Active only when
 * {@code documentService.backend=s3}, which is gated by the {@code -Pcloud}
 * Maven profile (the default JAR doesn't carry the AWS SDK on the
 * classpath).
 *
 * <p>Authentication is via the AWS SDK's default credentials provider
 * chain: env vars → ~/.aws/credentials → IAM role (EC2/EKS/ECS).
 * Local-dev / IT path uses LocalStack with explicit access key + secret
 * via {@code aws.accessKeyId} / {@code aws.secretAccessKey} system
 * properties (the SDK picks them up automatically).
 *
 * <p>{@code endpoint} + {@code pathStyle} accommodate non-AWS S3
 * implementations (LocalStack, MinIO, R2): a custom endpoint URL
 * overrides the default {@code s3.<region>.amazonaws.com}, and
 * {@code forcePathStyle(true)} switches from virtual-host-style URLs
 * (which require DNS that those implementations don't always provide)
 * to {@code <endpoint>/<bucket>/<key>}.
 */
@Configuration
@ConditionalOnProperty(name = "documentService.backend", havingValue = "s3")
public class S3Config {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(
            @Value("${documentService.s3.region}")            String region,
            @Value("${documentService.s3.endpoint:#{null}}")  String endpoint,
            @Value("${documentService.s3.pathStyle:false}")   boolean pathStyle) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (pathStyle) {
            builder.forcePathStyle(true);
        }
        return builder.build();
    }
}
