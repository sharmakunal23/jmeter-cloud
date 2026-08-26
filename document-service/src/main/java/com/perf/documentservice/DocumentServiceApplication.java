package com.perf.documentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the document-service — the platform's blob-storage
 * gateway on port 8084.
 *
 * <p>{@code documentService.backend} picks the active store:
 * {@link com.perf.documentservice.store.LocalFsBlobStore} (default) or
 * {@code S3BlobStore}, which is only on the classpath under {@code -Pcloud}.
 */
@SpringBootApplication
public class DocumentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}
