package com.perf.orchestrator.lifecycle;

import java.time.Instant;

/**
 * Metadata response shape for {@code GET /api/v1/testPlan} — matches the
 * {@code TestPlanMetadata} schema in {@code api/openapi.yaml}.
 *
 * <p>{@code compressed=true} when the upload arrived as a zip and we
 * unwrapped a single {@code .jmx} from it; {@code false} for a raw
 * {@code .jmx} body.
 */
public record PlanMetadata(
        String filename,
        long sizeBytes,
        String sha256,
        Instant uploadedAt,
        boolean compressed) {
}
