package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupRegistryRepository;
import com.perf.globalorchestrator.repo.GroupRegistryRepository.GroupRow;
import com.perf.globalorchestrator.repo.MetricsTarget;
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
 * <p><b>Nothing mutable is held in memory.</b> The group's registry row is read
 * per query, because it is shared state and N replicas holding their own copies
 * would resolve the same run to different tables. Only the resolved
 * {@code RUN_ID} is kept, and only because that mapping is immutable.
 */
@Service
public class MetricsGroupResolver {

    private static final int MAX_RUN_IDS = 20_000;

    private final ApplicationRepository applications;
    private final GroupRegistryRepository registry;

    /**
     * {@code prefix\0runKey → RUN_ID}. The <b>only</b> thing this service holds
     * in memory, and it is safe to because the mapping is immutable: a run key
     * is a ULID that is never reused, and the dimension row's numeric id never
     * changes once the consumer has written it. Two replicas therefore cannot
     * disagree. Bounded and cleared wholesale rather than evicted — this is a
     * lookup table, not a working set.
     */
    private final ConcurrentHashMap<String, Long> runIds = new ConcurrentHashMap<>();

    public MetricsGroupResolver(ApplicationRepository applications,
                                GroupRegistryRepository registry) {
        this.applications = applications;
        this.registry = registry;
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

    /**
     * The group's registry row, read every time.
     *
     * <p>It used to be held for five minutes per instance. That is exactly the
     * shape a multi-replica hub cannot have: {@code GROUP_REGISTRY} is mutable
     * shared state, so each replica would answer from its own copy and two of
     * them could resolve the same run to different tables for minutes after an
     * operator repointed a group. The row is a handful of columns behind an
     * indexed key, so reading it per query costs nothing worth having a
     * consistency bug for.
     *
     * <p>The amortisation this gave up is small by construction: every caller
     * resolves <b>once per metrics API call</b>, not once per series, so a
     * run-detail page polling every 5 s adds one indexed lookup per poll. A
     * short per-instance TTL would buy that back and put the replicas out of
     * step again — don't.
     */
    private GroupRow group(String groupId) {
        return registry.findGroup(groupId).orElse(null);
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
