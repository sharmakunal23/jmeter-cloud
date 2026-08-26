package com.perf.orchestrator.http;

import com.perf.orchestrator.lifecycle.ArtifactValidationException;
import com.perf.orchestrator.lifecycle.TestRunManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised exception → HTTP-status mapping for every {@code @RestController}
 * served by Spring MVC.
 *
 * <p>The {@code { error, message }} JSON envelope matches {@code ErrorResponse}
 * in {@code api/openapi.yaml}, so CLI / UI clients can parse responses
 * uniformly. Each controller throws plain Java exceptions; this advice
 * resolves them into the right status + envelope. That keeps the
 * controllers small and makes the error contract grep-able in one place.
 *
 * <p><b>Mapping table:</b>
 * <ul>
 *   <li>{@link ArtifactValidationException} with code {@code "PAYLOAD_TOO_LARGE"}
 *       → 413 Payload Too Large.</li>
 *   <li>{@link ArtifactValidationException} with any other code → 400
 *       Bad Request (the validator's code goes into the response envelope
 *       so clients can branch on it without parsing prose).</li>
 *   <li>Anything else → 500 Internal Server Error with envelope
 *       {@code {error: "INTERNAL_ERROR", message: "Unexpected server error."}}.
 *       The original throwable goes to the log at ERROR with the request URI;
 *       the response body deliberately does not include stack traces.</li>
 * </ul>
 */
@RestControllerAdvice
public final class GlobalErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalErrorHandler.class);

    /**
     * Maps {@link TestRunManager.StartRejection} to its carried HTTP status
     * + stable error code (e.g. {@code TEST_RUNNING} → 409,
     * {@code NO_TEST_PLAN} → 412, {@code SHUTTING_DOWN} → 503). Lets the
     * lifecycle layer carry status logic without leaking Javalin or Spring
     * types up.
     */
    @ExceptionHandler(TestRunManager.StartRejection.class)
    public ResponseEntity<Map<String, String>> handleStartRejection(TestRunManager.StartRejection e) {
        // INFO not WARN — these are operator-visible 4xx-class outcomes
        // (no test plan uploaded, test already running, etc.), not server faults.
        LOG.info("Rejected POST /test: {} — {}", e.code(), e.getMessage());
        return ResponseEntity.status(HttpStatusCode.valueOf(e.status()))
                .body(envelope(e.code(), e.getMessage()));
    }

    /**
     * 400 BAD_REQUEST when JSON deserialization fails (malformed body) or
     * a record's compact constructor / validating method throws
     * {@link IllegalArgumentException} (e.g. an unparseable
     * {@code scheduledStartAt}). The wrapped cause's message goes into the
     * envelope so the operator sees the exact field error.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause instanceof IllegalArgumentException iae && iae.getMessage() != null
                ? iae.getMessage()
                : "Request body is not valid JSON: " + e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("BAD_REQUEST", message));
    }

    @ExceptionHandler(ArtifactValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(ArtifactValidationException e) {
        HttpStatus status = "PAYLOAD_TOO_LARGE".equals(e.code())
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;
        // INFO not WARN — validation rejections are normal operator
        // mistakes (uploaded the wrong file, hit a size cap), not server
        // faults. Operators see them in the request log already.
        LOG.info("Rejected artifact upload: {} — {}", e.code(), e.getMessage());
        return ResponseEntity.status(status).body(envelope(e.code(), e.getMessage()));
    }

    /**
     * 404 NOT_FOUND for URLs that match no controller or static resource.
     * Without this, Spring MVC's {@link NoResourceFoundException} fell
     * through to {@link #handleUnexpected} and every unknown path (a typo,
     * a probe against the SLIMDOWN-removed {@code /actuator/prometheus})
     * returned a 500 + ERROR stacktrace — a client-side miss misreported
     * as a server fault. Found during the SLIMDOWN smoke (2026-07-21);
     * the misclassification predates it.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(envelope("NOT_FOUND", "No such path: /" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e, WebRequest req) {
        LOG.error("Unhandled exception serving request {}", req.getDescription(false), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope("INTERNAL_ERROR", "Unexpected server error."));
    }

    private static Map<String, String> envelope(String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
