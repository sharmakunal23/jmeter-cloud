package com.perf.documentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HTTP gateway that abstracts blob storage for the jmeter-cloud platform.
 *
 * <p>Step 7 skeleton — endpoints return 501 NOT_IMPLEMENTED. The
 * {@code LocalFsBlobStore} backend (default) and the cloud-only
 * {@code S3BlobStore} (under {@code -Pcloud}) materialize alongside the
 * orchestrator's open follow-up #3 (storage backend implementations).
 */
@SpringBootApplication
public class DocumentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}
