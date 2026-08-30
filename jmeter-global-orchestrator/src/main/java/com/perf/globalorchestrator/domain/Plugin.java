package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One row of {@code ORCH_PLUGIN} — the global JMeter plugin library. One
 * version per plugin name (an upgrade is delete + re-register); {@code sha256}
 * and {@code sizeBytes} come from document-service's server-side computation
 * at upload, never from the caller.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Plugin(
        String pluginId,
        String name,
        String version,
        String blobId,
        String sha256,
        long sizeBytes,
        /** The uploaded blob's file name — {@code .jar} = single plugin, {@code .zip} = bundle of jars. */
        String fileName,
        String description,
        String createdBy,
        Instant createdAt) {
}
