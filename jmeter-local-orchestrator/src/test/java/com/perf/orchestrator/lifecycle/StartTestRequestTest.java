package com.perf.orchestrator.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two Track 5 fields are validated at the edge: a bad group id or window width is a 400, never a bad URL. */
class StartTestRequestTest {

    private static StartTestRequest req(String groupId, Integer windowSeconds) {
        return new StartTestRequest("01J0RUN", "na-east", null, null, null, List.of(), List.of(), Map.of(),
                null, null, null, null, null, null, groupId, windowSeconds, List.of());
    }

    @Test
    @DisplayName("a consumer group id and a positive window width are accepted; both are optional")
    void valid() {
        assertThat(req("cps", 15).metricsGroupId()).isEqualTo("cps");
        assertThat(req("cps_pci2", 1).windowSeconds()).isEqualTo(1);
        assertThat(req(null, null).metricsGroupId()).isNull();
    }

    @Test
    @DisplayName("upper case, punctuation or a zero window are rejected")
    void invalid() {
        assertThatThrownBy(() -> req("CPS", 15)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metricsGroupId");
        assertThatThrownBy(() -> req("cps;drop", 15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req("cps", 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windowSeconds");
    }
}
