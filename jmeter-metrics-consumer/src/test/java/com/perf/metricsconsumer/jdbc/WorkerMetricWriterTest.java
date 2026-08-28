package com.perf.metricsconsumer.jdbc;

import com.perf.metricsconsumer.model.WireBounds;
import com.perf.metricsconsumer.model.WorkerMetricBatch;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure row-shaping half of the writer: explode, dedupe, bounds, status unrolling. */
class WorkerMetricWriterTest {

    private static WorkerMetricEntry entry(String label, long throughput, Map<String, Long> codes) {
        return new WorkerMetricEntry(label, throughput, 0, 0.0, 250.0, throughput * 250L,
                200, 400, 500, 900, 10, 1500, 1500, 10_000, 2_000, codes, 10);
    }

    private static WorkerMetricBatch envelope(String workerId, long windowSecond, WorkerMetricEntry... entries) {
        return new WorkerMetricBatch(windowSecond, "t", "na-east", workerId, "run-A", 0, List.of(entries));
    }

    @Test
    void explode_projects_envelope_identity_onto_every_entry() {
        List<WorkerMetricWriter.Row> rows = WorkerMetricWriter.explode(List.of(
                envelope("w1", 10, entry("login", 5, Map.of()), entry("search", 7, Map.of())),
                envelope("w2", 10, entry("login", 3, Map.of()))));
        assertEquals(3, rows.size());
        assertEquals("w2", rows.get(2).workerId());
        assertEquals("run-A", rows.get(2).runId());
        assertEquals(10L, rows.get(2).windowSecond());
        assertEquals("na-east", rows.get(2).region());
    }

    @Test
    void dedupe_keeps_the_first_row_of_each_primary_key_and_sorts() {
        List<WorkerMetricWriter.Row> rows = new ArrayList<>(WorkerMetricWriter.explode(List.of(
                envelope("w2", 11, entry("login", 1, Map.of())),
                envelope("w1", 10, entry("login", 5, Map.of())),
                envelope("w1", 10, entry("login", 999, Map.of())))));   // replayed key, different content
        List<WorkerMetricWriter.Row> unique = WorkerMetricWriter.dedupe(rows);
        assertEquals(2, unique.size());
        assertEquals("w1", unique.get(0).workerId());
        assertEquals(5L, unique.get(0).entry().throughput());            // first wins
        assertEquals("w2", unique.get(1).workerId());
    }

    @Test
    void labels_and_codes_are_truncated_to_the_schema_bounds_not_rejected() {
        String longLabel = "L".repeat(WireBounds.LABEL_CHARS + 50);
        assertEquals(WireBounds.LABEL_CHARS, WorkerMetricWriter.boundedLabel(longLabel).length());
        assertEquals("short", WorkerMetricWriter.boundedLabel("short"));
        assertEquals(WireBounds.CODE_CHARS, WorkerMetricWriter.boundedCode("C".repeat(500)).length());
        assertEquals(WorkerMetricWriter.BLANK_CODE, WorkerMetricWriter.boundedCode(""));
        assertEquals(WorkerMetricWriter.BLANK_CODE, WorkerMetricWriter.boundedCode(null));
        assertEquals("Non HTTP response code: java.net.SocketTimeoutException",
                WorkerMetricWriter.boundedCode("Non HTTP response code: java.net.SocketTimeoutException"));
    }

    @Test
    void status_rows_unroll_the_map_skipping_empty_and_non_positive_counts() {
        Map<String, Long> codes = new LinkedHashMap<>();
        codes.put("200", 95L);
        codes.put("", 3L);          // blank JMeter responseCode → "(none)"
        codes.put("500", 0L);       // nothing happened → no row
        codes.put("503", -1L);
        List<WorkerMetricWriter.Row> rows = WorkerMetricWriter.explode(List.of(
                envelope("w1", 10, entry("login", 100, codes)),
                envelope("w1", 11, entry("login", 100, null))));
        List<WorkerMetricWriter.StatusRow> statuses = WorkerMetricWriter.statusRows(rows);
        assertEquals(2, statuses.size());
        assertEquals("200", statuses.get(0).code());
        assertEquals(95L, statuses.get(0).n());
        assertEquals(WorkerMetricWriter.BLANK_CODE, statuses.get(1).code());
        assertEquals(3L, statuses.get(1).n());
    }
}
