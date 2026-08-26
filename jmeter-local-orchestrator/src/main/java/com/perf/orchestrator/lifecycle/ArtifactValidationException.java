package com.perf.orchestrator.lifecycle;

/**
 * Thrown by {@link ArtifactStager} when an upload fails the validation
 * rules documented in {@code docs/orchestratorPlan.md} §"Validation rules".
 *
 * <p>Carries a stable {@code code} (e.g. {@code INVALID_ARCHIVE},
 * {@code PAYLOAD_TOO_LARGE}, {@code NO_TEST_PLAN}) so the controller can
 * map directly to the {@code { "error", "message" }} response envelope
 * without inspecting the message text.
 */
public final class ArtifactValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ArtifactValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
