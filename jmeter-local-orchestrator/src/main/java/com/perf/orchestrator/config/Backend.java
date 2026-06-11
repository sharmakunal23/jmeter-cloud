package com.perf.orchestrator.config;

/**
 * Pluggable storage backends used as either an {@code ARTIFACT_SOURCE}
 * (where test plans / data files come from) or a {@code RESULT_SINK}
 * (where the JTL goes after a test completes).
 *
 * <p>Not all values are valid in both roles — {@code S3} is intentionally
 * not supported as a sink because results auto-upload targets the Document
 * Service only (one HTTP gateway hides the underlying cloud storage). Use
 * {@link #parseArtifactSource(String)} / {@link #parseResultSink(String)}
 * so the validation lives in one place.
 */
public enum Backend {

    HTTP_UPLOAD,
    S3,
    DOCUMENT_SERVICE;

    static Backend parseArtifactSource(String value) {
        return switch (value) {
            case "HTTP_UPLOAD"      -> HTTP_UPLOAD;
            case "S3"               -> S3;
            case "DOCUMENT_SERVICE" -> DOCUMENT_SERVICE;
            default -> throw new OrchestratorConfigException(
                    "'ARTIFACT_SOURCE' must be one of HTTP_UPLOAD, S3, DOCUMENT_SERVICE; got: '" + value + "'");
        };
    }

    static Backend parseResultSink(String value) {
        return switch (value) {
            case "HTTP_UPLOAD"      -> HTTP_UPLOAD;
            case "DOCUMENT_SERVICE" -> DOCUMENT_SERVICE;
            case "S3" -> throw new OrchestratorConfigException(
                    "'RESULT_SINK=S3' is not supported. Auto-upload of results targets the " +
                    "Document Service only — set RESULT_SINK to HTTP_UPLOAD or DOCUMENT_SERVICE.");
            default -> throw new OrchestratorConfigException(
                    "'RESULT_SINK' must be one of HTTP_UPLOAD, DOCUMENT_SERVICE; got: '" + value + "'");
        };
    }
}
