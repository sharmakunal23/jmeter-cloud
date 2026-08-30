package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AI-1 / AI-2 — durable cache for Claude responses, backed
 * by {@code ORCH_AI_RESPONSE}. Writes go through the
 * least-privilege {@code GLOBAL_ORCHESTRATOR_WRITER} role (the run-state
 * datasource); the metrics datasource is read-only.
 *
 * <p>The 30-day TTL is enforced on read: {@link #find} only returns rows newer
 * than the cutoff, so an expired entry reads as a miss and the caller re-bills +
 * upserts. {@link #pruneOlderThan} reclaims the dead rows on a schedule.
 */
@Repository
public class AiResponseRepository {

    private final JdbcTemplate jdbc;

    public AiResponseRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cache lookup. Returns empty when there is no row for the key OR the row is
     * older than {@code ttl} (an expired entry is a miss, not a stale hit).
     */
    public Optional<CachedAiResponse> find(String kind, String cacheKey, String promptVersion, Duration ttl) {
        List<CachedAiResponse> rows = jdbc.query(
                "SELECT RESPONSE, MODEL, TOKENS_IN, TOKENS_OUT, CREATED_AT "
                        + "FROM ORCH_AI_RESPONSE "
                        + "WHERE KIND = ? AND CACHE_KEY = ? AND PROMPT_VERSION = ? AND CREATED_AT > ?",
                (rs, n) -> new CachedAiResponse(
                        rs.getString("RESPONSE"),
                        rs.getString("MODEL"),
                        rs.getInt("TOKENS_IN"),
                        rs.getInt("TOKENS_OUT"),
                        OracleBind.instant(rs, "CREATED_AT")),
                kind, cacheKey, promptVersion, OracleBind.ts(Instant.now().minus(ttl)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Cache-on-write. Upsert so a {@code ?fresh=true} regenerate (or a re-bill
     * after TTL expiry) replaces the prior row and resets {@code createdAt}.
     */
    public void upsert(String kind, String cacheKey, String promptVersion,
                       String responseJson, String model, int tokensIn, int tokensOut) {
        jdbc.update(
                "MERGE INTO ORCH_AI_RESPONSE t "
                        + "USING (SELECT ? AS KIND, ? AS CACHE_KEY, ? AS PROMPT_VERSION FROM dual) s "
                        + "ON (t.KIND = s.KIND AND t.CACHE_KEY = s.CACHE_KEY AND t.PROMPT_VERSION = s.PROMPT_VERSION) "
                        + "WHEN MATCHED THEN UPDATE SET "
                        + "  t.RESPONSE = ?, t.MODEL = ?, t.TOKENS_IN = ?, t.TOKENS_OUT = ?, t.CREATED_AT = SYSTIMESTAMP "
                        + "WHEN NOT MATCHED THEN INSERT "
                        + "(KIND, CACHE_KEY, PROMPT_VERSION, RESPONSE, MODEL, TOKENS_IN, TOKENS_OUT, CREATED_AT) "
                        + "VALUES (s.KIND, s.CACHE_KEY, s.PROMPT_VERSION, ?, ?, ?, ?, SYSTIMESTAMP)",
                kind, cacheKey, promptVersion,
                OracleBind.clob(responseJson), model, tokensIn, tokensOut,
                OracleBind.clob(responseJson), model, tokensIn, tokensOut);
    }

    /**
     * HARD-DELETE / purge — drop every cached AI response that involves
     * {@code runId}. The cache key for single-run insights IS the runId; a
     * two-run comparison's key is a sorted {@code "idA|idB"}, so a run can appear
     * on either side. We match the exact runId plus the two pipe-delimited
     * comparison forms. {@code runId} is a ULID (no LIKE wildcards), so the
     * {@code LIKE} patterns are safe. Returns the row count removed.
     */
    public int deleteForRun(String runId) {
        return jdbc.update(
                "DELETE FROM ORCH_AI_RESPONSE "
                        + "WHERE CACHE_KEY = ? OR CACHE_KEY LIKE ? OR CACHE_KEY LIKE ?",
                runId, runId + "|%", "%|" + runId);
    }

    /** Housekeeping: drop entries past the TTL. Returns the row count removed. */
    public int pruneOlderThan(Duration ttl) {
        return jdbc.update(
                "DELETE FROM ORCH_AI_RESPONSE WHERE CREATED_AT <= ?",
                OracleBind.ts(Instant.now().minus(ttl)));
    }

    /**
     * A cached row: the raw {@code {summary, findings}} JSON plus the columns the
     * service needs to reconstruct the response DTO ({@code model}, token counts,
     * and the original {@code createdAt} used as {@code cachedAt}).
     */
    public record CachedAiResponse(String responseJson, String model,
                                   int tokensIn, int tokensOut, Instant createdAt) { }
}
