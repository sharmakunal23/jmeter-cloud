package com.perf.metricsconsumer.jdbc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps a producer's strings to the surrogate keys of the shared dimensions —
 * {@code RUN (GROUP_ID, RUN_KEY)}, {@code WORKER (RUN_ID, WORKER_KEY)},
 * {@code LABEL (GROUP_ID, LABEL_KEY)} — by get-or-create: read the cache,
 * {@code SELECT}, {@code INSERT}, swallow only a duplicate-key race, re-{@code SELECT}.
 * Every statement autocommits, so a row never holds a lock another writer waits
 * on. {@code REGION}, {@code JOINED_AT_SECOND}, {@code FIRST_SEEN} and
 * {@code APPLICATION} are written once, on creation.
 */
@Component
public class DimensionResolver {

    private static final String SELECT_RUN =
            "SELECT RUN_ID FROM RUN WHERE GROUP_ID = ? AND RUN_KEY = ?";
    private static final String INSERT_RUN =
            "INSERT INTO RUN (GROUP_ID, RUN_KEY, FIRST_SEEN) VALUES (?, ?, ?)";
    private static final String SELECT_WORKER =
            "SELECT WORKER_ID FROM WORKER WHERE RUN_ID = ? AND WORKER_KEY = ?";
    private static final String INSERT_WORKER =
            "INSERT INTO WORKER (RUN_ID, GROUP_ID, WORKER_KEY, REGION, JOINED_AT_SECOND) VALUES (?, ?, ?, ?, ?)";
    private static final String SELECT_LABEL =
            "SELECT LABEL_ID FROM LABEL WHERE GROUP_ID = ? AND LABEL_KEY = ?";
    private static final String INSERT_LABEL =
            "INSERT INTO LABEL (GROUP_ID, LABEL_KEY, FIRST_SEEN) VALUES (?, ?, ?)";
    /** The classifier is a validated identifier (GroupRegistry); its argument is bound. */
    private static final String INSERT_LABEL_CLASSIFIED =
            "INSERT INTO LABEL (GROUP_ID, LABEL_KEY, APPLICATION, FIRST_SEEN) VALUES (?, ?, %s(?), ?)";

    private final JdbcTemplate jdbc;
    private final ExpiringCache<String, Long> runIds;
    private final ExpiringCache<String, Long> workerIds;
    private final ExpiringCache<String, Long> labelIds;

    public DimensionResolver(JdbcTemplate jdbc,
                             @Value("${metricsConsumer.dimCache.ttl:24h}") String ttl,
                             @Value("${metricsConsumer.dimCache.maxSize:100000}") int maxSize) {
        this.jdbc = jdbc;
        long ttlMillis = DurationStyle.detectAndParse(ttl).toMillis();
        this.runIds = new ExpiringCache<>(ttlMillis, maxSize);
        this.workerIds = new ExpiringCache<>(ttlMillis, maxSize);
        this.labelIds = new ExpiringCache<>(ttlMillis, maxSize);
    }

    public long runId(String prefix, String runKey, long firstSeen) {
        return runIds.get(prefix + '\0' + runKey, k -> getOrCreate(
                SELECT_RUN, new Object[] {prefix, runKey},
                INSERT_RUN, new Object[] {prefix, runKey, firstSeen}));
    }

    public long workerId(long runId, String prefix, String workerKey, String region, long joinedAtSecond) {
        return workerIds.get(runId + "\0" + workerKey, k -> getOrCreate(
                SELECT_WORKER, new Object[] {runId, workerKey},
                INSERT_WORKER, new Object[] {runId, prefix, workerKey, region, joinedAtSecond}));
    }

    /** {@code classifyFn} null = the group has no classifier; {@code APPLICATION} stays null. */
    public long labelId(String prefix, String label, String classifyFn, long firstSeen) {
        return labelIds.get(prefix + '\0' + label, k -> classifyFn == null
                ? getOrCreate(SELECT_LABEL, new Object[] {prefix, label},
                              INSERT_LABEL, new Object[] {prefix, label, firstSeen})
                : getOrCreate(SELECT_LABEL, new Object[] {prefix, label},
                              INSERT_LABEL_CLASSIFIED.formatted(classifyFn),
                              new Object[] {prefix, label, label, firstSeen}));
    }

    private long getOrCreate(String select, Object[] selectArgs, String insert, Object[] insertArgs) {
        Long id = selectId(select, selectArgs);
        if (id != null) {
            return id;
        }
        try {
            jdbc.update(insert, insertArgs);
        } catch (DuplicateKeyException race) {
            // Another thread, replica or application won the unique key: read the winner's id.
        }
        id = selectId(select, selectArgs);
        if (id == null) {
            throw new IllegalStateException("dimension row neither found nor created: " + select);
        }
        return id;
    }

    private Long selectId(String select, Object[] args) {
        List<Long> ids = jdbc.queryForList(select, Long.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
