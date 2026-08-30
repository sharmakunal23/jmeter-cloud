package com.perf.globalorchestrator.repo;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Oracle bindings every repository shares, plus the IN-list chunking the purge relies on. */
class OracleBindTest {

    @Test
    void instants_bind_as_utc_offsets_and_null_stays_null() {
        Instant t = Instant.parse("2026-08-28T20:00:00.123Z");
        assertEquals(ZoneOffset.UTC, OracleBind.ts(t).getOffset());
        assertEquals(t, OracleBind.ts(t).toInstant());
        assertNull(OracleBind.ts(null));
    }

    @Test
    void json_binds_as_a_clob_parameter() {
        SqlParameterValue v = OracleBind.clob("{\"a\":1}");
        assertEquals(Types.CLOB, v.getSqlType());
        assertNotNull(v.getValue());
        assertNull(OracleBind.clob(null).getValue());
    }

    @Test
    void column_labels_become_the_api_keys() {
        assertEquals("totalThroughput", OracleBind.camel("TOTAL_THROUGHPUT"));
        assertEquals("avgP50Ms", OracleBind.camel("AVG_P50_MS"));
        assertEquals("httpErrorRate", OracleBind.camel("HTTP_ERROR_RATE"));
        assertEquals("n", OracleBind.camel("N"));
        assertEquals("http2xx", OracleBind.camel("HTTP_2XX"));   // a digit takes the capital
        assertEquals("", OracleBind.camel(""));
    }

    @Test
    void in_lists_are_chunked_under_oracles_cap() {
        List<String> ids = java.util.stream.IntStream.range(0, 1201).mapToObj(i -> "r" + i).toList();
        List<List<String>> chunks = MetricsPurgeRepository.chunks(ids);
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).size());
        assertEquals(201, chunks.get(2).size());
        assertEquals("?,?,?", MetricsPurgeRepository.marks(List.of("a", "b", "c")));
    }
}
