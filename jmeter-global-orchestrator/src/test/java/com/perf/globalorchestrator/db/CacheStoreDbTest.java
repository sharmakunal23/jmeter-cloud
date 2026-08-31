package com.perf.globalorchestrator.db;

import com.perf.globalorchestrator.cache.CacheValueCodec;
import com.perf.globalorchestrator.cache.OracleCacheManager;
import com.perf.globalorchestrator.cache.OracleCacheStore;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.RunSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ORCH_CACHE} against the real V8 migration on Oracle Free: the round
 * trip, the expiry filter that makes the reaper a space job rather than a
 * freshness one, {@code clear} scoped to one cache name, concurrent writers of
 * the same key, and the reaper procedure itself.
 */
@SpringBootTest(properties = {
        "globalOrchestrator.pod.sweepInitialDelayMs=3600000",
        "globalOrchestrator.pod.lostAfterMs=3600000",
        // The point of this class: exercise the real store, not the test map.
        "globalOrchestrator.cache.provider=oracle"
})
@DisplayName("ORCH_CACHE on Oracle — round trip, expiry, clear, reaper")
class CacheStoreDbTest extends OracleDbTestSupport {

    @Autowired @Qualifier("runStateJdbcTemplate") JdbcTemplate writer;

    private final JdbcTemplate owner = owner();
    private final CacheValueCodec codec = new CacheValueCodec();

    private OracleCacheStore store;

    @BeforeEach
    void clean() {
        owner.update("DELETE FROM ORCH_CACHE");
        store = new OracleCacheStore(writer, 1 << 20);
    }

    @Test
    @DisplayName("a record round-trips through the BLOB, and VALUE_BYTES records the stored size")
    void roundTrips() {
        RunSummary summary = new RunSummary("01JRUNCACHE0000000000000001", 1000L, 1300L,
                new RunSummary.Stats(null, 1320, 16, 22.0, 1.2121, 132.1, 198.2, 320.5, 640.0, 900.0, 10),
                List.of());
        byte[] encoded = codec.encode(summary);

        store.put("runSummary::k1", "runSummary", encoded, Duration.ofMinutes(10));

        assertThat(codec.decode(store.get("runSummary::k1"))).isEqualTo(summary);
        Integer bytes = owner.queryForObject(
                "SELECT VALUE_BYTES FROM ORCH_CACHE WHERE CACHE_KEY = ?", Integer.class, "runSummary::k1");
        assertThat(bytes).isEqualTo(encoded.length);
    }

    @Test
    @DisplayName("an expired row is never served, even while it is still in the table")
    void expiredRowIsNotServed() {
        store.put("runSummary::stale", "runSummary", codec.encode("v"), Duration.ofMinutes(10));
        // Age it past its TTL without deleting it — exactly the window between
        // expiry and the next ORCH_CACHE_REAP_JOB run.
        owner.update("UPDATE ORCH_CACHE SET EXPIRES_AT = SYSTIMESTAMP - INTERVAL '1' SECOND "
                     + "WHERE CACHE_KEY = ?", "runSummary::stale");

        assertThat(store.get("runSummary::stale")).isNull();
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM ORCH_CACHE WHERE CACHE_KEY = ?",
                Integer.class, "runSummary::stale")).isEqualTo(1);
    }

    @Test
    @DisplayName("a re-put of a live key overwrites it instead of raising a duplicate key")
    void putOverwrites() {
        store.put("runSummary::k2", "runSummary", codec.encode("first"), Duration.ofMinutes(10));
        store.put("runSummary::k2", "runSummary", codec.encode("second"), Duration.ofMinutes(10));

        assertThat(codec.decode(store.get("runSummary::k2"))).isEqualTo("second");
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM ORCH_CACHE", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("clear removes one cache's rows and leaves every other cache alone")
    void clearIsScopedToOneCache() {
        store.put("groupCapacity::cps", "groupCapacity", codec.encode("a"), Duration.ofMinutes(10));
        store.put("groupCapacity::all", "groupCapacity", codec.encode("b"), Duration.ofMinutes(10));
        store.put("runSummary::keep", "runSummary", codec.encode("c"), Duration.ofMinutes(10));

        store.clear("groupCapacity");

        assertThat(store.get("groupCapacity::cps")).isNull();
        assertThat(store.get("groupCapacity::all")).isNull();
        assertThat(codec.decode(store.get("runSummary::keep"))).isEqualTo("c");
    }

    @Test
    @DisplayName("eight threads writing the same key all succeed — a MERGE race is not an error")
    void concurrentPutsOfOneKey() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    // Each writer builds its own store so they do not share the
                    // passive-circuit counter — a tripped circuit would hide a
                    // real failure behind a silent no-op.
                    new OracleCacheStore(writer, 1 << 20)
                            .put("runTimeseries::hot", "runTimeseries",
                                 codec.encode("v"), Duration.ofMinutes(10));
                    return true;
                }));
            }
            go.countDown();
            for (Future<Boolean> f : futures) assertThat(f.get(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(owner.queryForObject("SELECT COUNT(*) FROM ORCH_CACHE WHERE CACHE_KEY = ?",
                Integer.class, "runTimeseries::hot")).isEqualTo(1);
        assertThat(codec.decode(store.get("runTimeseries::hot"))).isEqualTo("v");
    }

    @Test
    @DisplayName("ORCH_CACHE_REAP deletes expired rows in chunks and leaves live ones")
    void reaperReclaimsExpiredRowsOnly() {
        for (int i = 0; i < 25; i++) {
            store.put("runSummary::dead" + i, "runSummary", codec.encode("x"), Duration.ofMinutes(10));
        }
        store.put("runSummary::live", "runSummary", codec.encode("x"), Duration.ofMinutes(10));
        owner.update("UPDATE ORCH_CACHE SET EXPIRES_AT = SYSTIMESTAMP - INTERVAL '1' SECOND "
                     + "WHERE CACHE_KEY <> ?", "runSummary::live");

        // A chunk size below the backlog proves the loop iterates rather than
        // stopping after one statement.
        owner.execute("BEGIN ORCH_CACHE_REAP(p_chunkRows => 10, p_maxChunks => 200); END;");

        assertThat(owner.queryForObject("SELECT COUNT(*) FROM ORCH_CACHE", Integer.class)).isEqualTo(1);
        assertThat(codec.decode(store.get("runSummary::live"))).isEqualTo("x");
    }

    @Test
    @DisplayName("the reaper job is registered and scheduled, so expired rows are reclaimed unattended")
    void reaperJobIsScheduled() {
        Map<String, Object> job = owner.queryForMap(
                "SELECT ENABLED, STATE, REPEAT_INTERVAL FROM USER_SCHEDULER_JOBS WHERE JOB_NAME = ?",
                "ORCH_CACHE_REAP_JOB");
        assertThat(job.get("ENABLED")).isEqualTo("TRUE");
        assertThat(job.get("STATE")).isEqualTo("SCHEDULED");
        assertThat(String.valueOf(job.get("REPEAT_INTERVAL"))).contains("MINUTELY");
    }

    @Test
    @DisplayName("the cache manager serves the declared caches end to end, records included")
    void cacheManagerRoundTripsThroughTheTable() {
        OracleCacheManager manager = new OracleCacheManager(
                store, codec, Duration.ofHours(1),
                Map.of("groupCapacity", Duration.ofMinutes(10)));
        Cache cache = manager.getCache("groupCapacity");
        assertThat(cache).isNotNull();

        Map<String, List<GroupCapacity>> grid = Map.of("cps", List.of(
                new GroupCapacity("cps", "na-east", 4,
                        Instant.parse("2026-08-31T10:00:00Z"), Instant.parse("2026-08-31T11:00:00Z"))));
        cache.put("all", grid);

        Cache.ValueWrapper hit = cache.get("all");
        assertThat(hit).isNotNull();
        assertThat(hit.get()).isEqualTo(grid);

        cache.clear();
        assertThat(cache.get("all")).isNull();
    }
}
