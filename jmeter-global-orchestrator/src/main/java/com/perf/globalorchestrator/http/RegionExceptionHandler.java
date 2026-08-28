package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.region.RegionUnavailableException;
import com.perf.globalorchestrator.region.RegionalCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * A region that cannot serve a provisioning call is {@code 503 REGION_UNREACHABLE};
 * a region that answered with an error is {@code 502 REGIONAL_CALL_FAILED}.
 * Applies to every controller that reaches a {@link com.perf.globalorchestrator.provision.PodProvisioner}.
 */
@RestControllerAdvice
public class RegionExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RegionExceptionHandler.class);

    @ExceptionHandler(RegionUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(RegionUnavailableException e) {
        return responseFor(e);
    }

    @ExceptionHandler(RegionalCallException.class)
    public ResponseEntity<Map<String, String>> failed(RegionalCallException e) {
        return responseFor(e);
    }

    public static ResponseEntity<Map<String, String>> responseFor(RegionUnavailableException e) {
        LOG.warn("region unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "REGION_UNREACHABLE", "message", e.getMessage()));
    }

    public static ResponseEntity<Map<String, String>> responseFor(RegionalCallException e) {
        LOG.warn("regional call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("code", "REGIONAL_CALL_FAILED", "message", e.getMessage()));
    }
}
