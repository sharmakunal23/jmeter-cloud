package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code POST /api/v1/plugins}. The bytes were uploaded to
 * document-service first ({@code X-Type: plugin}); this call registers them —
 * sha256, size and file name come from the blob's server-computed metadata,
 * never from this body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterPluginRequest(
        String name,
        String version,
        String blobId,
        String description) {
}
