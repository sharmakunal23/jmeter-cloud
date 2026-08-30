package com.perf.documentservice.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code storage} readiness contributor for the local-filesystem backend:
 * DOWN when the blob root is missing, not writable, or has less than
 * {@code documentService.localFs.minFreeBytes} usable space — so a broken or
 * full mount takes the pod out of the Service before writes start failing.
 * Liveness never includes it.
 */
@Component("storage")
@ConditionalOnProperty(name = "documentService.backend", havingValue = "local", matchIfMissing = true)
public class StorageHealthIndicator implements HealthIndicator {

    private final Path rootPath;
    private final long minFreeBytes;

    public StorageHealthIndicator(
            @Value("${documentService.localFs.rootPath:/var/lib/document-service/blobs}") String rootPath,
            @Value("${documentService.localFs.minFreeBytes:1073741824}") long minFreeBytes) {
        this.rootPath = Path.of(rootPath);
        this.minFreeBytes = minFreeBytes;
    }

    @Override
    public Health health() {
        Health.Builder h = Health.unknown().withDetail("rootPath", rootPath.toString());
        if (!Files.isDirectory(rootPath)) {
            return h.down().withDetail("reason", "rootPath is not a directory").build();
        }
        if (!Files.isWritable(rootPath)) {
            return h.down().withDetail("reason", "rootPath is not writable").build();
        }
        try {
            long usable = Files.getFileStore(rootPath).getUsableSpace();
            h.withDetail("usableBytes", usable).withDetail("minFreeBytes", minFreeBytes);
            return usable < minFreeBytes
                    ? h.down().withDetail("reason", "usable space below minFreeBytes").build()
                    : h.up().build();
        } catch (IOException e) {
            return h.down().withDetail("reason", "cannot stat the file store: " + e.getMessage()).build();
        }
    }
}
