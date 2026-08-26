package com.perf.orchestrator.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;

@DisplayName("GlobalErrorHandler")
class GlobalErrorHandlerTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    /**
     * SLIMDOWN pin (2026-07-21): an unknown path — including the removed
     * {@code /actuator/prometheus} — must map to 404, not fall through to
     * the 500 catch-all. The 500 misclassification predated the slim-down
     * and surfaced during its smoke.
     */
    @Test
    @DisplayName("NoResourceFoundException (unknown path) → 404 NOT_FOUND, not 500")
    void unknown_path_maps_to_404() {
        var response = handler.handleNotFound(
                new NoResourceFoundException(GET, "actuator/prometheus"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .containsEntry("error", "NOT_FOUND")
                .containsEntry("message", "No such path: /actuator/prometheus");
    }
}
