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
 * Wires the {@link S3Client} used by
 * {@link com.perf.documentservice.store.S3BlobStore}, active only when
 * {@code documentService.backend=s3} — which requires the {@code -Pcloud} build,
 * since the default JAR carries no AWS SDK.
 *
 * <p>Credentials come from the SDK's default provider chain (env vars →
 * {@code ~/.aws/credentials} → IAM role). Setting {@code endpoint} plus
 * {@code pathStyle} targets a non-AWS implementation such as LocalStack, MinIO
 * or R2, whose DNS usually cannot serve virtual-host-style URLs.
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
