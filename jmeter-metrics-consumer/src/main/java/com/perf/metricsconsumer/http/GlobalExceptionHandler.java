package com.perf.metricsconsumer.http;

import com.perf.metricsconsumer.util.RateLimitedLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every outcome of {@code /api/v1/ingest} is an {@link IngestResponse}: a
 * database failure is a retryable {@code 503 ORACLE_UNAVAILABLE}, framework
 * rejections keep their status with the hosted code names, and anything else
 * is {@code 500 INTERNAL_ERROR} with a fixed message — no SQL, ORA code or
 * stack trace ever reaches the producer.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final RateLimitedLogger RL_LOG = new RateLimitedLogger(LOG, 1000L);

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<IngestResponse> oracleUnavailable(Exception e) {
        RL_LOG.warn("INGEST_DB_DOWN", "Rejected /ingest envelope: database unavailable: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new IngestResponse(0, "ORACLE_UNAVAILABLE", "database unavailable; retry"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<IngestResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new IngestResponse(0, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<IngestResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            response.allow(e.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(new IngestResponse(0, "METHOD_NOT_ALLOWED", e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<IngestResponse> missingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(new IngestResponse(0, "MISSING_PARAMETER", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IngestResponse> internal(Exception e) {
        LOG.error("Unhandled /ingest failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new IngestResponse(0, "INTERNAL_ERROR", "internal error"));
    }
}
