package com.perf.globalorchestrator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic eventId must always fit {@code ORCH_RUN_EVENT.EVENT_ID}
 * ({@code VARCHAR2(64 CHAR)}) — the raw semantic key does not (ORA-12899).
 */
@DisplayName("RunAuditWriter.deterministicId")
class RunAuditWriterTest {

    @Test
    @DisplayName("64 lowercase hex chars, stable for equal keys, distinct for different keys")
    void shape() {
        String key = "resultsSaved:01M1AA2GH8D2X2C8TMHWVNDWZ6:smokeapp-na-east-worker-1";
        String id = RunAuditWriter.deterministicId(key);
        assertThat(id).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(RunAuditWriter.deterministicId(key)).isEqualTo(id);
        assertThat(RunAuditWriter.deterministicId("artifactsCleared:x:y")).isNotEqualTo(id);
    }
}
