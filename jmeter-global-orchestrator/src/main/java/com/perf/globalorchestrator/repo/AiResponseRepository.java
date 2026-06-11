package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AI-1 / AI-2 — durable cache for Claude responses, backed
 * by {@code "globalOrchestrator"."aiResponse"} (V26). Writes go through the
 * least-privilege {@code globalOrchestratorWriter} role (the run-state
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
        Timestamp cutoff = Timestamp.from(Instant.now().minus(ttl));
        List<CachedAiResponse> rows = jdbc.query(
                "SELECT \"response\"::text AS responseJson, \"model\", \"tokensIn\", \"tokensOut\", \"createdAt\" "
                        + "FROM \"globalOrchestrator\".\"aiResponse\" "
                        + "WHERE \"kind\" = ? AND \"cacheKey\" = ? AND \"promptVersion\" = ? AND \"createdAt\" > ?",
                (rs, n) -> new CachedAiResponse(
                        rs.getString("responseJson"),
                        rs.getString("model"),
                        rs.getInt("tokensIn"),
                        rs.getInt("tokensOut"),
                        rs.getTimestamp("createdAt").toInstant()),
                kind, cacheKey, promptVersion, cutoff);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Cache-on-write. Upsert so a {@code ?fresh=true} regenerate (or a re-bill
     * after TTL expiry) replaces the prior row and resets {@code createdAt}.
     */
    public void upsert(String kind, String cacheKey, String promptVersion,
                       String responseJson, String model, int tokensIn, int tokensOut) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"aiResponse\" "
                        + "(\"kind\", \"cacheKey\", \"promptVersion\", \"response\", \"model\", \"tokensIn\", \"tokensOut\", \"createdAt\") "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, now()) "
                        + "ON CONFLICT (\"kind\", \"cacheKey\", \"promptVersion\") DO UPDATE SET "
                        + "  \"response\"  = EXCLUDED.\"response\", "
                        + "  \"model\"     = EXCLUDED.\"model\", "
                        + "  \"tokensIn\"  = EXCLUDED.\"tokensIn\", "
                        + "  \"tokensOut\" = EXCLUDED.\"tokensOut\", "
                        + "  \"createdAt\" = now()",
                kind, cacheKey, promptVersion, responseJson, model, tokensIn, tokensOut);
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
                "DELETE FROM \"globalOrchestrator\".\"aiResponse\" "
                        + "WHERE \"cacheKey\" = ? OR \"cacheKey\" LIKE ? OR \"cacheKey\" LIKE ?",
                runId, runId + "|%", "%|" + runId);
    }

    /** Housekeeping: drop entries past the TTL. Returns the row count removed. */
    public int pruneOlderThan(Duration ttl) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(ttl));
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"aiResponse\" WHERE \"createdAt\" <= ?",
                cutoff);
    }

    /**
     * A cached row: the raw {@code {summary, findings}} JSON plus the columns the
     * service needs to reconstruct the response DTO ({@code model}, token counts,
     * and the original {@code createdAt} used as {@code cachedAt}).
     */
    public record CachedAiResponse(String responseJson, String model,
                                   int tokensIn, int tokensOut, Instant createdAt) { }
}
