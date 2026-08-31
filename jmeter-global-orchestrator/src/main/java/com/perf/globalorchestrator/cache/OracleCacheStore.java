package com.perf.globalorchestrator.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.support.SqlLobValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The rows behind the hub's cache: {@code ORCH_CACHE}, one row per entry, keyed
 * {@code <cacheName>::<key>}.
 *
 * <p>Two properties make a database acceptable as a cache store here. A get is
 * one {@code INDEX UNIQUE SCAN} on the primary key — the {@code INDEX} hint
 * pins that plan, because an empty or freshly-analysed table otherwise tempts
 * the optimizer onto {@code ORCH_CACHE_EXPIRES_AT_IDX} and range-scans every
 * live entry. And an expired row is filtered by the same statement, so
 * {@code ORCH_CACHE_REAP_JOB} reclaims space without ever being on the
 * freshness path.
 *
 * <p><b>The passive circuit is the load protection.</b> A cache read that
 * errors is followed by the caller's fall-through to the same database, so a
 * struggling Oracle would be asked twice per request at the exact moment it can
 * least afford it. After {@link #FAILURE_THRESHOLD} consecutive failures the
 * store goes quiet for {@link #PASSIVE_WINDOW}: every get misses and every put
 * is dropped, with no statement issued at all. The template handed in is the
 * short-bound {@code cacheJdbcTemplate}, so a <i>slow</i> Oracle trips the
 * circuit as surely as a failing one — a timeout is a failure.
 */
public class OracleCacheStore {

    private static final Logger LOG = LoggerFactory.getLogger(OracleCacheStore.class);

    /** Consecutive store failures that trip the passive circuit. */
    static final int FAILURE_THRESHOLD = 3;
    /** How long the store stays quiet once tripped. */
    static final Duration PASSIVE_WINDOW = Duration.ofSeconds(30);

    /** Width of {@code ORCH_CACHE.CACHE_KEY}; a longer key is hashed instead. */
    static final int KEY_CHARS = 512;

    private final JdbcTemplate jdbc;
    private final int maxValueBytes;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long passiveUntilEpochMs;

    public OracleCacheStore(JdbcTemplate jdbc, int maxValueBytes) {
        this.jdbc = jdbc;
        this.maxValueBytes = maxValueBytes;
    }

    /**
     * The stored bytes for a live entry, or null when the key is absent, the
     * entry has expired, or the circuit is passive.
     */
    public byte[] get(String cacheKey) {
        if (passive()) return null;
        try {
            List<byte[]> rows = jdbc.query(
                    "SELECT /*+ INDEX(ORCH_CACHE ORCH_CACHE_PK) */ CACHE_VALUE FROM ORCH_CACHE "
                    + "WHERE CACHE_KEY = ? AND EXPIRES_AT > SYSTIMESTAMP",
                    (rs, n) -> rs.getBytes("CACHE_VALUE"), cacheKey);
            succeeded();
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            failed("GET", cacheKey, e);
            return null;
        }
    }

    /**
     * Writes (or refreshes) one entry. A value over {@code maxValueBytes} is
     * <b>not</b> stored: an oversized log tail belongs in neither the redo log
     * nor a LOB segment, and not caching it costs one refetch from the source.
     */
    public void put(String cacheKey, String cacheName, byte[] value, Duration ttl) {
        if (passive()) return;
        if (value.length > maxValueBytes) {
            LOG.debug("cache PUT {} skipped — {} bytes exceeds maxValueBytes {}",
                    cacheKey, value.length, maxValueBytes);
            return;
        }
        OffsetDateTime expiresAt = Instant.now().plus(ttl).atOffset(ZoneOffset.UTC);
        try {
            // MERGE, not INSERT: a refresh of a live key must overwrite rather
            // than raise. Racing writers produce the same value for the same
            // key, so a lost update is not observable — but a duplicate-key
            // race still surfaces as a failure, which is only a missed cache
            // put.
            jdbc.update(
                    "MERGE INTO ORCH_CACHE t "
                    + "USING (SELECT ? AS CACHE_KEY FROM dual) s "
                    + "ON (t.CACHE_KEY = s.CACHE_KEY) "
                    + "WHEN MATCHED THEN UPDATE SET t.CACHE_VALUE = ?, t.VALUE_BYTES = ?, "
                    + "  t.CREATED_AT = SYSTIMESTAMP, t.EXPIRES_AT = CAST(? AS TIMESTAMP WITH TIME ZONE) "
                    + "WHEN NOT MATCHED THEN INSERT (CACHE_KEY, CACHE_NAME, CACHE_VALUE, VALUE_BYTES, EXPIRES_AT) "
                    + "  VALUES (s.CACHE_KEY, ?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE))",
                    cacheKey,
                    blob(value), value.length, expiresAt,
                    cacheName, blob(value), value.length, expiresAt);
            succeeded();
        } catch (RuntimeException e) {
            failed("PUT", cacheKey, e);
        }
    }

    /** Removes one entry. */
    public void evict(String cacheKey) {
        if (passive()) return;
        try {
            jdbc.update("DELETE FROM ORCH_CACHE WHERE CACHE_KEY = ?", cacheKey);
            succeeded();
        } catch (RuntimeException e) {
            failed("EVICT", cacheKey, e);
        }
    }

    /** Removes every entry of one cache — the {@code allEntries} evict. */
    public void clear(String cacheName) {
        if (passive()) return;
        try {
            jdbc.update("DELETE FROM ORCH_CACHE WHERE CACHE_NAME = ?", cacheName);
            succeeded();
        } catch (RuntimeException e) {
            failed("CLEAR", cacheName, e);
        }
    }

    /**
     * {@code <cacheName>::<key>}, hashed past the column's width so a long
     * label prefix cannot overflow it. The hash is of the key alone, so the
     * cache name stays readable in the table and {@link #clear} is unaffected.
     */
    static String cacheKey(String cacheName, Object key) {
        String raw = String.valueOf(key);
        String composed = cacheName + "::" + raw;
        if (composed.length() <= KEY_CHARS) return composed;
        return cacheName + "::sha256:" + sha256Hex(raw);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }

    private static SqlParameterValue blob(byte[] value) {
        return new SqlParameterValue(Types.BLOB, new SqlLobValue(value));
    }

    // ── passive circuit ──────────────────────────────────────────────

    boolean passive() {
        return System.currentTimeMillis() < passiveUntilEpochMs;
    }

    private void succeeded() {
        if (consecutiveFailures.get() != 0) consecutiveFailures.set(0);
    }

    private void failed(String op, String key, RuntimeException e) {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            consecutiveFailures.set(0);
            passiveUntilEpochMs = System.currentTimeMillis() + PASSIVE_WINDOW.toMillis();
            LOG.warn("cache {} {} failed — cache passive for {}s so the database is asked once, not twice: {}",
                    op, key, PASSIVE_WINDOW.toSeconds(), e.toString());
        } else {
            LOG.warn("cache {} {} failed — serving from the source: {}", op, key, e.toString());
        }
    }
}
