package com.perf.orchestrator.lifecycle;

import java.time.Instant;
import java.util.List;

/**
 * Response shape for {@code GET /api/v1/dataFiles} — matches the
 * {@code DataFilesManifest} schema in {@code api/openapi.yaml}.
 *
 * <p>{@code files} is the sorted list of extracted entry names (relative to
 * {@code DATA_FILES_DIR}) so the caller can inspect what made it through
 * validation without enumerating the directory.
 *
 * <p>{@code blobId} (UX-DYNAMICS T4) is the document-service blob this bundle
 * was fetched from — the anchor of the worker's reuse check. Null for direct
 * {@code POST /api/v1/dataFiles} uploads and for legacy manifests, which
 * therefore never match and are always re-downloaded.
 */
public record DataFilesManifest(
        long zipSizeBytes,
        long extractedBytes,
        int fileCount,
        List<String> files,
        String sha256,
        Instant uploadedAt,
        String blobId) {

    public DataFilesManifest {
        files = List.copyOf(files);
    }
}
