package com.perf.metricsconsumer.jdbc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Routes a producer's {@code ?groupId=} to its group's fact table through
 * {@code GROUP_REGISTRY}. A hit is cached for {@code metricsConsumer.groupCache.ttl}
 * (24 h) — so repointing or disabling a group takes effect only after the TTL or
 * a restart — while an unknown group is never cached, so a newly registered
 * group works on its next request.
 */
@Component
public class GroupRegistry {

    /** The only shape an identifier may have before it is spliced into SQL. */
    public static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,127}$");

    private static final String SQL =
            "SELECT GROUP_ID, TABLE_PREFIX, METRICS_TABLE, CLASSIFY_FN "
            + "FROM GROUP_REGISTRY WHERE GROUP_ID = ? AND ENABLED = 1";

    private final JdbcTemplate jdbc;
    private final ExpiringCache<String, GroupTarget> cache;

    public GroupRegistry(JdbcTemplate jdbc,
                         @Value("${metricsConsumer.groupCache.ttl:24h}") String ttl,
                         @Value("${metricsConsumer.groupCache.maxSize:1024}") int maxSize) {
        this.jdbc = jdbc;
        this.cache = new ExpiringCache<>(DurationStyle.detectAndParse(ttl).toMillis(), maxSize);
    }

    /** @throws UnknownGroupException when the id is blank, unknown or disabled (not cached) */
    public GroupTarget resolve(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new UnknownGroupException(groupId);
        }
        return cache.get(groupId.trim(), this::load);
    }

    public void invalidate(String groupId) {
        if (groupId != null) {
            cache.invalidate(groupId.trim());
        }
    }

    private GroupTarget load(String groupId) {
        List<GroupTarget> rows = jdbc.query(SQL, (rs, n) -> new GroupTarget(
                rs.getString("GROUP_ID"), rs.getString("TABLE_PREFIX"),
                rs.getString("METRICS_TABLE"), rs.getString("CLASSIFY_FN")), groupId);
        if (rows.isEmpty()) {
            throw new UnknownGroupException(groupId);
        }
        return validate(rows.get(0));
    }

    /** A registry row whose identifiers are not plain unqualified names is a 500, never spliced. */
    static GroupTarget validate(GroupTarget t) {
        requireIdentifier("TABLE_PREFIX", t.prefix());
        requireIdentifier("METRICS_TABLE", t.metricsTable());
        String fn = t.classifyFn() == null || t.classifyFn().isBlank() ? null : t.classifyFn().trim();
        if (fn != null) {
            requireIdentifier("CLASSIFY_FN", fn);
        }
        return new GroupTarget(t.groupId(), t.prefix(), t.metricsTable(), fn);
    }

    private static void requireIdentifier(String column, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException("GROUP_REGISTRY." + column + " is not an identifier: " + value);
        }
    }
}
