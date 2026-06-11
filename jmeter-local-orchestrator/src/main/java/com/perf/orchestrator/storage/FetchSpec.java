package com.perf.orchestrator.storage;

import java.util.Map;
import java.util.Objects;

/**
 * Opaque parameter bundle passed to {@link ArtifactSource} implementations.
 *
 * <p>Shape is intentionally backend-neutral: an S3 source might look at
 * {@code params.get("s3Url")}, a Document Service source at
 * {@code params.get("documentName")}. The orchestrator core never inspects
 * the inner keys — it only forwards what the caller provided in the
 * {@code POST /test} body.
 */
public record FetchSpec(String runId, Map<String, String> params) {

    public FetchSpec {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(params, "params cannot be null (use Map.of() for none)");
        params = Map.copyOf(params);
    }

    public static FetchSpec of(String runId) {
        return new FetchSpec(runId, Map.of());
    }
}
