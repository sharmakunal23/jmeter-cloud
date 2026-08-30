package com.perf.documentservice.observability;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code GET /actuator/keepalive} — the startup and liveness probe target on
 * the hosted platform: answers as soon as the servlet container is up and
 * never consults a dependency, so a database or storage blip can remove the
 * pod from the Service (readiness) without restarting it (liveness).
 */
@Component
@Endpoint(id = "keepalive")
public class KeepaliveEndpoint {

    @ReadOperation
    public Map<String, String> keepalive() {
        return Map.of("status", "UP");
    }
}
