package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupRegistryRepository;
import com.perf.globalorchestrator.repo.GroupRegistryRepository.GroupRow;
import com.perf.globalorchestrator.repo.MetricsTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds where a run's metrics are: run → application (by name) →
 * {@code application.metricsGroupId} → the group's {@code GROUP_REGISTRY} row →
 * the run's {@code RUN_ID} there. Empty when the run is untagged, its
 * application is ungrouped, or the consumer has not created the run's
 * dimension row yet (nothing has landed) — the readers then answer with empty
 * series rather than an error.
 *
 * <p>The group row is cached per instance for {@code metrics.groupCacheSeconds}
 * (a repointed group takes effect after that); a resolved {@code RUN_ID} never
 * changes and is cached until the map is trimmed.
 */
@Service
public class MetricsGroupResolver {

    private record CachedGroup(GroupRow row, long expiresAt) { }

    private static final int MAX_RUN_IDS = 20_000;

    private final ApplicationRepository applications;
    private final GroupRegistryRepository registry;
    private final long groupTtlMillis;
    private final ConcurrentHashMap<String, CachedGroup> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> runIds = new ConcurrentHashMap<>();

    public MetricsGroupResolver(ApplicationRepository applications,
                                GroupRegistryRepository registry,
                                @Value("${metrics.groupCacheSeconds:300}") long groupCacheSeconds) {
        this.applications = applications;
        this.registry = registry;
        this.groupTtlMillis = Math.max(1, groupCacheSeconds) * 1000L;
    }

    public Optional<MetricsTarget> resolve(Run run) {
        // The group recorded at launch wins: the rows live in that group's table
        // whatever the application's group is today. Legacy rows fall back to
        // the application (findByName sees archived applications too, so a purge
        // of a hidden app's runs still resolves).
        Optional<String> groupId = run.metricsGroupId() != null && !run.metricsGroupId().isBlank()
                ? Optional.of(run.metricsGroupId())
                : (run.application() == null || run.application().isBlank())
                    ? Optional.empty()
                    : applications.findByName(run.application()).map(Application::metricsGroupId);
        if (groupId.isEmpty() || groupId.get() == null || groupId.get().isBlank()) {
            return Optional.empty();
        }
        GroupRow group = group(groupId.get());
        if (group == null) {
            return Optional.empty();
        }
        Long runId = runId(group.prefix(), run.runId());
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.of(new MetricsTarget(group.groupId(), group.prefix(), group.metricsTable(),
                group.historyTable(), runId));
    }

    /** Drops a run's cached {@code RUN_ID} after a purge recreated or removed it. */
    public void forgetRun(String runKey) {
        runIds.keySet().removeIf(k -> k.endsWith("\0" + runKey));
    }

    private GroupRow group(String groupId) {
        long now = System.currentTimeMillis();
        CachedGroup cached = groups.get(groupId);
        if (cached != null && cached.expiresAt > now) {
            return cached.row;
        }
        GroupRow row = registry.findGroup(groupId).orElse(null);
        if (row != null) {
            groups.put(groupId, new CachedGroup(row, now + groupTtlMillis));
        } else {
            groups.remove(groupId);   // an unknown group is never cached
        }
        return row;
    }

    private Long runId(String prefix, String runKey) {
        String key = prefix + '\0' + runKey;
        Long id = runIds.get(key);
        if (id != null) {
            return id;
        }
        id = registry.findRunId(prefix, runKey).orElse(null);
        if (id != null) {
            if (runIds.size() >= MAX_RUN_IDS) {
                runIds.clear();
            }
            runIds.put(key, id);
        }
        return id;
    }
}
