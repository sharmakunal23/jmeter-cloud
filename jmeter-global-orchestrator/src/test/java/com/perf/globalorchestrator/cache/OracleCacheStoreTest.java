package com.perf.globalorchestrator.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The store's own behaviour, without a database: the key shape, the oversize
 * guard, and the passive circuit that stops a failing cache from doubling the
 * load on the database it is meant to protect. The SQL itself is pinned by
 * {@code CacheStoreDbTest} against a real Oracle.
 */
@DisplayName("OracleCacheStore — key shape, size guard, passive circuit (CACHE-ORACLE)")
class OracleCacheStoreTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Test
    @DisplayName("the key is cacheName::key, so one cache's clear cannot touch another's rows")
    void keyIsNamespaced() {
        assertThat(OracleCacheStore.cacheKey("runSummary", "01JRUN:all"))
                .isEqualTo("runSummary::01JRUN:all");
    }

    @Test
    @DisplayName("a key wider than the column is hashed, and the same key always hashes the same")
    void oversizeKeyIsHashed() {
        String huge = "run:" + "x".repeat(OracleCacheStore.KEY_CHARS);

        String first = OracleCacheStore.cacheKey("runTimeseries", huge);
        String second = OracleCacheStore.cacheKey("runTimeseries", huge);

        assertThat(first).isEqualTo(second)
                .startsWith("runTimeseries::sha256:")
                .hasSizeLessThanOrEqualTo(OracleCacheStore.KEY_CHARS);
        // Still namespaced, so clear(cacheName) still reaches it.
        assertThat(first).startsWith("runTimeseries::");
        // A different key must not collide with it.
        assertThat(OracleCacheStore.cacheKey("runTimeseries", huge + "y")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a value over maxValueBytes is not written at all — no LOB, no statement")
    void oversizeValueIsSkipped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OracleCacheStore store = new OracleCacheStore(jdbc, 1024);

        store.put("memberLogs::k", "memberLogs", new byte[1025], TTL);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a value at the limit is written")
    void valueAtLimitIsWritten() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OracleCacheStore store = new OracleCacheStore(jdbc, 1024);

        store.put("memberLogs::k", "memberLogs", new byte[1024], TTL);

        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a get failure returns a miss rather than propagating — the caller falls through")
    @SuppressWarnings("unchecked")
    void getFailureIsAMiss() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));
        OracleCacheStore store = new OracleCacheStore(jdbc, 1 << 20);

        assertThat(store.get("runSummary::k")).isNull();
    }

    @Test
    @DisplayName("three consecutive failures go passive — no further statement until the window passes")
    @SuppressWarnings("unchecked")
    void repeatedFailuresGoPassive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("oracle is down"));
        OracleCacheStore store = new OracleCacheStore(jdbc, 1 << 20);

        for (int i = 0; i < OracleCacheStore.FAILURE_THRESHOLD; i++) {
            assertThat(store.get("runSummary::k" + i)).isNull();
        }
        assertThat(store.passive()).isTrue();

        // While passive the store issues nothing: this is the whole point —
        // a sick database is asked once (by the caller's fall-through), not
        // twice (cache read + fall-through).
        reset(jdbc);
        assertThat(store.get("runSummary::later")).isNull();
        store.put("runSummary::later", "runSummary", new byte[8], TTL);
        store.evict("runSummary::later");
        store.clear("runSummary");
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a success between failures resets the counter, so intermittent errors never trip it")
    @SuppressWarnings("unchecked")
    void successResetsTheFailureCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicInteger call = new AtomicInteger();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    // fail, fail, succeed, fail, fail — never three in a row.
                    int n = call.getAndIncrement();
                    if (n == 2) return List.of();
                    throw new DataAccessResourceFailureException("blip " + n);
                });
        OracleCacheStore store = new OracleCacheStore(jdbc, 1 << 20);

        for (int i = 0; i < 5; i++) store.get("runSummary::k" + i);

        assertThat(store.passive()).isFalse();
    }

    @Test
    @DisplayName("clear names the cache, not a key — one indexed delete per evicted cache")
    void clearIsByCacheName() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OracleCacheStore store = new OracleCacheStore(jdbc, 1 << 20);

        store.clear("groupCapacity");

        verify(jdbc).update(eq("DELETE FROM ORCH_CACHE WHERE CACHE_NAME = ?"), eq("groupCapacity"));
    }
}
