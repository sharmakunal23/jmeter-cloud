package com.perf.regionalorchestrator.http;

import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps the three failure kinds a pod operation has onto the platform's
 * {@code {code, message}} error body: bad input → {@code 400},
 * a missing pod on start/restart → {@code 404 POD_NOT_FOUND},
 * a cluster API failure → {@code 502 CLUSTER_API_ERROR}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
                       MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalStateException e) {
        return body(HttpStatus.NOT_FOUND, "POD_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(com.perf.regionalorchestrator.provision.CapacityExhaustedException.class)
    public ResponseEntity<Map<String, String>> capacity(com.perf.regionalorchestrator.provision.CapacityExhaustedException e) {
        LOG.warn("spin refused: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, "CAPACITY_EXHAUSTED", e.getMessage());
    }

    @ExceptionHandler(KubernetesClientException.class)
    public ResponseEntity<Map<String, String>> clusterApi(KubernetesClientException e) {
        LOG.warn("cluster API call failed: {}", e.toString());
        return body(HttpStatus.BAD_GATEWAY, "CLUSTER_API_ERROR", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message == null ? "" : message));
    }
}
