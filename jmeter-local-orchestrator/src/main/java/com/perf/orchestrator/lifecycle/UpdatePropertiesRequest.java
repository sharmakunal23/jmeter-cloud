package com.perf.orchestrator.lifecycle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Body of {@code POST /api/v1/test/properties} (UX-DYNAMICS T5) — runtime
 * JMeter property values, pushed to the RUNNING child via the BeanShell
 * server. Same per-entry rules as launch properties ({@link JmeterProperties});
 * at most 50 entries per push.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdatePropertiesRequest(Map<String, String> properties) {

    private static final int MAX_ENTRIES = 50;

    public UpdatePropertiesRequest {
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties is required and must be non-empty");
        }
        if (properties.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("at most " + MAX_ENTRIES + " properties per update");
        }
        properties = Map.copyOf(JmeterProperties.validate(properties));
    }
}
