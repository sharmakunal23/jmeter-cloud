package com.perf.metricsconsumer.http;

import com.perf.metricsconsumer.model.WireBounds;
import com.perf.metricsconsumer.model.WorkerMetricBatch;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge validation must reject, terminally, every envelope the schema's
 * constraints would reject — otherwise the database answers with a 503 the
 * worker replays forever.
 */
class IngestValidationTest {

    private static WorkerMetricEntry entry(String label, long throughput, long errors) {
        return new WorkerMetricEntry(label, throughput, errors, 0.0, 250.0, throughput * 250L,
                200, 400, 500, 900, 10, 1500, 1500, 10_000, 2_000, Map.of("200", throughput - errors), 10);
    }

    private static WorkerMetricBatch envelope(String runId, long windowSecond, List<WorkerMetricEntry> entries) {
        return new WorkerMetricBatch(windowSecond, "2026-08-28T20:00:00Z", "na-east", "worker-1", runId, 0, entries);
    }

    @Test
    void a_well_formed_envelope_passes() {
        assertNull(IngestController.firstViolation(envelope("run-A", 1_787_948_162L, List.of(entry("login", 100, 5)))));
    }

    @Test
    void an_empty_entries_list_passes() {
        assertNull(IngestController.firstViolation(envelope("run-A", 1_787_948_162L, List.of())));
    }

    @Test
    void identity_fields_are_required_and_bounded() {
        assertEquals("runId is required", IngestController.firstViolation(envelope(" ", 1L, List.of())));
        String tooLong = "r".repeat(WireBounds.ID_CHARS + 1);
        assertTrue(IngestController.firstViolation(envelope(tooLong, 1L, List.of())).startsWith("runId exceeds 64 chars"));
        assertEquals("entries is required (may be empty, not absent)",
                IngestController.firstViolation(envelope("run-A", 1L, null)));
    }

    @Test
    void windowSecond_must_be_a_positive_epoch_second() {
        assertTrue(IngestController.firstViolation(envelope("run-A", 0L, List.of())).startsWith("windowSecond must be"));
        // milliseconds, not seconds — the classic producer bug
        assertTrue(IngestController.firstViolation(envelope("run-A", 1_787_948_162_000L, List.of())).startsWith("windowSecond must be"));
    }

    @Test
    void non_finite_and_out_of_range_numbers_are_rejected_not_clamped() {
        WorkerMetricEntry infinite = new WorkerMetricEntry("x", 1, 0, 0, 1, 1L, Double.POSITIVE_INFINITY, 1, 1, 1, 1, 1, 1, 0, 0, Map.of(), 1);
        assertEquals("entries[0] has a non-finite percentile",
                IngestController.firstViolation(envelope("run-A", 1L, List.of(infinite))));
        WorkerMetricEntry nan = new WorkerMetricEntry("x", 1, 0, 0, 1, 1L, 1, Double.NaN, 1, 1, 1, 1, 1, 0, 0, Map.of(), 1);
        assertEquals("entries[0] has a non-finite percentile",
                IngestController.firstViolation(envelope("run-A", 1L, List.of(nan))));
        WorkerMetricEntry huge = new WorkerMetricEntry("x", 1, 0, 0, 1, 1L, 1, 1, 1, 1, 1, 1, 5_000_000_000L, 0, 0, Map.of(), 1);
        assertTrue(IngestController.firstViolation(envelope("run-A", 1L, List.of(huge))).contains("beyond the column range"));
    }

    @Test
    void counts_mirror_the_schema_checks() {
        assertEquals("entries[0].label is required",
                IngestController.firstViolation(envelope("run-A", 1L, List.of(entry("", 1, 0)))));
        assertEquals("entries[0].throughput must be >= 0",
                IngestController.firstViolation(envelope("run-A", 1L, List.of(entry("x", -1, 0)))));
        assertEquals("entries[0].errorCount (6) exceeds throughput (5)",
                IngestController.firstViolation(envelope("run-A", 1L, List.of(entry("x", 5, 6)))));
        WorkerMetricEntry negativeBytes = new WorkerMetricEntry("x", 1, 0, 0, 1, 1L, 1, 1, 1, 1, 1, 1, 1, -1, 0, Map.of(), 1);
        assertEquals("entries[0].bytesReceived must be >= 0",
                IngestController.firstViolation(envelope("run-A", 1L, List.of(negativeBytes))));
    }
}
