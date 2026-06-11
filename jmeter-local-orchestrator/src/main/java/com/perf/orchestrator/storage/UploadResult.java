package com.perf.orchestrator.storage;

import java.util.Objects;

/**
 * Outcome of a {@link ResultSink#upload} call.
 *
 * <p>{@code target} is a backend-specific identifier the orchestrator surfaces
 * via {@code GET /api/v1/results} and {@code GET /api/v1/test} so operators
 * can locate the uploaded artifact (e.g. {@code "doc-service://documents/d-123"}).
 *
 * <p>{@link #noUpload()} represents the {@code AUTO_UPLOAD_RESULTS=false}
 * terminal — a normal "did nothing on purpose" outcome, not a failure.
 * Inspect this state with {@link #skipped()}.
 */
public record UploadResult(String target, long sizeBytes, long durationMs, boolean skipped) {

    public UploadResult {
        Objects.requireNonNull(target, "target cannot be null (use empty string when skipped)");
        if (sizeBytes < 0)  throw new IllegalArgumentException("sizeBytes must be >= 0");
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must be >= 0");
    }

    public static UploadResult noUpload() {
        return new UploadResult("", 0L, 0L, true);
    }

    public static UploadResult uploaded(String target, long sizeBytes, long durationMs) {
        return new UploadResult(target, sizeBytes, durationMs, false);
    }
}
