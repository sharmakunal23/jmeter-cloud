package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupRegistryRepository;
import com.perf.globalorchestrator.repo.GroupRegistryRepository.GroupRow;
import com.perf.globalorchestrator.repo.MetricsTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** run → application → group → registry row → RUN_ID, with the empties that mean "no rows anywhere". */
class MetricsGroupResolverTest {

    private ApplicationRepository apps;
    private GroupRegistryRepository registry;
    private MetricsGroupResolver resolver;

    private static Run run(String application) {
        return new Run("01J0RUN", "na-east", "b", null, application, "t", RunState.RUNNING, null,
                Instant.now(), Instant.now(), null, false, null);
    }

    private static Application app(String name, String groupId) {
        return new Application("id", name, null, null, List.of(), Instant.now(), null, null, null, groupId, groupId == null ? null : name.toUpperCase());
    }

    @BeforeEach
    void setUp() {
        apps = mock(ApplicationRepository.class);
        registry = mock(GroupRegistryRepository.class);
        resolver = new MetricsGroupResolver(apps, registry);
        when(apps.findByName("cps-pci")).thenReturn(Optional.of(app("cps-pci", "cps")));
        when(apps.findByName("loose")).thenReturn(Optional.of(app("loose", null)));
        when(registry.findGroup("cps")).thenReturn(Optional.of(new GroupRow("cps", "CPS", "CPS_METRICS", "CPS_METRICS_H")));
        when(registry.findRunId("CPS", "01J0RUN")).thenReturn(Optional.of(4711L));
    }

    @Test
    void resolves_the_group_tables_and_the_runs_surrogate_key() {
        Optional<MetricsTarget> t = resolver.resolve(run("cps-pci"));
        assertThat(t).contains(new MetricsTarget("cps", "CPS", "CPS_METRICS", "CPS_METRICS_H", 4711L));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("only the RUN_ID is held — it is immutable, so replicas cannot disagree about it")
    void keeps_the_run_id_and_forgets_it_on_demand() {
        resolver.resolve(run("cps-pci"));
        resolver.resolve(run("cps-pci"));
        // A run key is a ULID that is never reused and its numeric id never
        // changes, so holding the mapping is safe at any replica count.
        verify(registry, times(1)).findRunId("CPS", "01J0RUN");
        // ...and a purge that removed the dimension row drops it.
        resolver.forgetRun("01J0RUN");
        resolver.resolve(run("cps-pci"));
        verify(registry, times(2)).findRunId("CPS", "01J0RUN");
    }

    @Test
    void untagged_ungrouped_unregistered_or_not_yet_landed_runs_resolve_to_empty() {
        assertThat(resolver.resolve(run(null))).isEmpty();
        assertThat(resolver.resolve(run("loose"))).isEmpty();
        when(apps.findByName("ghost")).thenReturn(Optional.empty());
        assertThat(resolver.resolve(run("ghost"))).isEmpty();
        when(apps.findByName("orphan")).thenReturn(Optional.of(app("orphan", "gone")));
        when(registry.findGroup("gone")).thenReturn(Optional.empty());
        assertThat(resolver.resolve(run("orphan"))).isEmpty();
        when(registry.findRunId("CPS", "01J0RUN")).thenReturn(Optional.empty());
        MetricsGroupResolver fresh = new MetricsGroupResolver(apps, registry);
        assertThat(fresh.resolve(run("cps-pci"))).isEmpty();
        // The registry row is read per call, so registering the group resolves at once.
        when(registry.findGroup("gone")).thenReturn(Optional.of(new GroupRow("gone", "GONE", "GONE_METRICS", null)));
        when(registry.findRunId("GONE", "01J0RUN")).thenReturn(Optional.of(1L));
        assertThat(fresh.resolve(run("orphan"))).isPresent();
    }

    @Test
    void the_group_recorded_on_the_run_wins_over_the_applications_current_group() {
        // The app moved to another group after this run — its rows are still in CPS_METRICS.
        when(apps.findByName("moved")).thenReturn(Optional.of(app("moved", "demo")));
        Run run = new Run("01J0RUN", "na-east", "b", null, "moved", "t", RunState.COMPLETED, null,
                Instant.now(), Instant.now(), Instant.now(), false, List.of(), "cps");
        assertThat(resolver.resolve(run)).contains(new MetricsTarget("cps", "CPS", "CPS_METRICS", "CPS_METRICS_H", 4711L));
        verify(apps, org.mockito.Mockito.never()).findByName("moved");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the group's registry row is re-read every call — N replicas must not diverge on mutable shared state")
    void the_group_row_is_never_held_in_memory() {
        assertThat(resolver.resolve(run("cps-pci"))).isPresent();
        assertThat(resolver.resolve(run("cps-pci"))).isPresent();
        assertThat(resolver.resolve(run("cps-pci"))).isPresent();
        verify(registry, org.mockito.Mockito.times(3)).findGroup("cps");

        // A repointed group takes effect on the very next call, not after a TTL.
        when(registry.findGroup("cps")).thenReturn(
                Optional.of(new GroupRow("cps", "NEW", "NEW_METRICS", "NEW_METRICS_H")));
        when(registry.findRunId("NEW", "01J0RUN")).thenReturn(Optional.of(99L));
        assertThat(resolver.resolve(run("cps-pci")))
                .contains(new MetricsTarget("cps", "NEW", "NEW_METRICS", "NEW_METRICS_H", 99L));
    }
}
