package com.perf.globalorchestrator.db;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobKind;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.Plugin;
import com.perf.globalorchestrator.domain.PluginRef;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.repo.AiResponseRepository;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.PluginRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control-plane schema's contract against the real migration on Oracle
 * Free: two claimers never share a worker, the cron claim returns due rows in
 * order, JSON and timestamps round-trip, NULLs inside expressions bind, and the
 * metrics pools resolve the hosted schema's unqualified names.
 */
@SpringBootTest(properties = {
        "globalOrchestrator.pod.sweepInitialDelayMs=3600000",
        "globalOrchestrator.pod.lostAfterMs=3600000"
})
@DisplayName("control-plane schema on Oracle — claims, JSON, purge")
class GlobalRunDbTest extends OracleDbTestSupport {

    @Autowired ApplicationRepository applications;
    @Autowired ApplicationGroupRepository groups;
    @Autowired GroupCapacityRepository groupCapacity;
    @Autowired com.perf.globalorchestrator.repo.RegionRepository regionRepo;
    @Autowired com.perf.globalorchestrator.service.GroupReservationService reservations;
    @Autowired PodRepository pods;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired CronJobRepository cronJobs;
    @Autowired AiResponseRepository aiResponses;
    @Autowired PluginRepository pluginLibrary;
    @Autowired @Qualifier("metricsJdbcTemplate") JdbcTemplate metricsReader;
    @Autowired @Qualifier("metricsPurgeJdbcTemplate") JdbcTemplate metricsPurge;
    @Autowired PlatformTransactionManager txManager;

    private final JdbcTemplate owner = owner();

    /** A registered cluster (created on first use) — reservations FK ORCH_REGION since V4. */
    private void ensureRegion(String region, int maxWorkers) {
        if (regionRepo.find(region).isEmpty()) {
            regionRepo.insert(region, region + " DC", "http://" + region + ":30088", maxWorkers);
        }
    }

    /** An application in the {@code cps} group (created on first use) — every application has a group. */
    private Application app(String id, String name) {
        return app(id, name, "cps");
    }

    private Application app(String id, String name, String groupId) {
        if (groups.findById(groupId).isEmpty()) {
            groups.insert(new ApplicationGroup(groupId, "Group " + groupId, "db contract test", Instant.now(), null));
        }
        return applications.insert(new Application(id, name, null, "db contract test",
                List.of("http://" + name + "/health"), Instant.now(), null, null, null,
                groupId, name.toUpperCase()));
    }

    /** One ORCH_RUN_EVENT row straight through the owner pool (payload is the workerId envelope). */
    private void insertEvent(String runId, String type, String workerId) {
        owner.update(
                "INSERT INTO ORCH_RUN_EVENT (EVENT_ID, RUN_ID, EVENT_TYPE, ACTOR, ACTOR_SOURCE, PAYLOAD, RESULT, OCCURRED_AT) "
                + "VALUES (?, ?, ?, 'orchestrator', 'system', ?, 'ok', SYSTIMESTAMP)",
                com.perf.globalorchestrator.domain.Ulid.generate(), runId, type,
                "{\"workerId\":\"" + workerId + "\"}");
    }

    private void insertCleanMember(String runId, String workerId) {
        runs.insertFleetMember(new RunFleetMember(runId, workerId, "na-east", MemberState.COMPLETED,
                null, 202, "http://" + workerId + ":8080", Instant.now(), Instant.now(), Instant.now(),
                Map.of(), null, null));
    }

    @Test
    void post_run_events_sweep_awaits_BOTH_events_and_only_clean_members_count() {
        app("app-postrun", "postrun");
        Instant lookback = Instant.now().minus(Duration.ofHours(1));
        String r1 = "run-postrun-1";
        runs.insertRun(new Run(r1, "na-east", "blob-pr", null, "postrun", "tester",
                RunState.COMPLETED, null, Instant.now(), Instant.now(), Instant.now(), true, null));
        // insertRun never writes COMPLETED_AT (production stamps it on the
        // terminal claim) — stamp it so the sweep's lookback window sees us.
        owner.update("UPDATE ORCH_RUN SET COMPLETED_AT=SYSTIMESTAMP WHERE RUN_ID=?", r1);
        insertCleanMember(r1, "pr-w1");
        insertCleanMember(r1, "pr-w2");
        // A FAILED member never uploads — it must not keep the run in the sweep.
        runs.insertFleetMember(new RunFleetMember(r1, "pr-w3", "na-east", MemberState.FAILED,
                "boom", 0, "http://pr-w3:8080", Instant.now(), Instant.now(), Instant.now(),
                Map.of(), null, null));

        // No events at all → awaiting.
        assertThat(runs.runIdsAwaitingPostRunEvents(lookback)).contains(r1);
        // Both RESULTS_SAVED but only one ARTIFACTS_CLEARED → still awaiting
        // (LEAST of the two distinct-worker counts governs).
        insertEvent(r1, "RESULTS_SAVED", "pr-w1");
        insertEvent(r1, "RESULTS_SAVED", "pr-w2");
        insertEvent(r1, "ARTIFACTS_CLEARED", "pr-w1");
        assertThat(runs.runIdsAwaitingPostRunEvents(lookback)).contains(r1);
        // The durable read-sets see exactly what landed.
        assertThat(runEvents.workerIdsWithEvent(r1, "RESULTS_SAVED")).containsExactlyInAnyOrder("pr-w1", "pr-w2");
        assertThat(runEvents.workerIdsWithEvent(r1, "ARTIFACTS_CLEARED")).containsExactly("pr-w1");
        // Second clean member cleared too → done, FAILED member ignored.
        insertEvent(r1, "ARTIFACTS_CLEARED", "pr-w2");
        assertThat(runs.runIdsAwaitingPostRunEvents(lookback)).doesNotContain(r1);
    }

    @Test
    void orphan_delete_guard_sees_run_snapshot_blob_references() {
        app("app-blobref", "blobref");
        String blob = "01HXBLOBREF00000000000000B";
        runs.insertRun(new Run("run-blobref-1", "na-east", "blob-x", null, "blobref", "tester",
                RunState.RUNNING, null, Instant.now(), Instant.now(), null, false, null,
                null, List.of(new PluginRef("pg-1", "demo-noop", "1.0.0", blob, "demoNoopPlugin.jar"))));
        assertThat(pluginLibrary.activeRunsReferencingBlob(blob)).isEqualTo(1);
        assertThat(pluginLibrary.activeRunsReferencingBlob("01HXBLOBREF0000000000OTHER")).isZero();
        // A terminal run no longer pins the bytes.
        owner.update("UPDATE ORCH_RUN SET STATE='COMPLETED' WHERE RUN_ID=?", "run-blobref-1");
        assertThat(pluginLibrary.activeRunsReferencingBlob(blob)).isZero();
    }

    @Test
    void all_migrations_applied_and_every_object_is_valid() {
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID'", Integer.class)).isZero();
        // 13 control-plane tables from V2 + ORCH_PLUGIN (V3) + ORCH_REGION (V4)
        // + the three workflow tables (V5) + ORCH_CACHE (V8).
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM user_tables WHERE table_name LIKE 'ORCH\\_%' ESCAPE '\\'", Integer.class)).isEqualTo(19);
        // One convention for the whole schema: nothing quoted-case except Flyway's own history table.
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM user_objects WHERE object_name <> UPPER(object_name) AND object_name NOT LIKE 'flyway\\_schema\\_history%' ESCAPE '\\'", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM user_tab_columns WHERE column_name <> UPPER(column_name) AND table_name <> 'flyway_schema_history'", Integer.class)).isZero();
    }

    @Test
    void run_lifecycle_round_trips_json_timestamps_and_null_binds() {
        Application a = app("app-life", "lifecycle");
        assertThat(a.healthEndpoints()).containsExactly("http://lifecycle/health");   // CLOB JSON → list

        pods.declareStatic("life-w1", "na-east", "http://w1:8080", a.metricsGroupId());
        pods.register("life-w1", "na-east", "http://w1-self:8080", a.metricsGroupId());   // MERGE matched: declared address wins
        Pod p = pods.findByPodId("life-w1").orElseThrow();
        assertThat(p.baseUrl()).isEqualTo("http://w1:8080");
        assertThat(p.source()).isEqualTo(PodSource.STATIC);
        assertThat(p.lastHeartbeat()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));

        // NULLs inside COALESCE(?, col) — ORA-17004 unless the bind is typed.
        assertThat(pods.recordProvisionMetadata("life-w1", null, null)).isEqualTo(1);
        Instant provisioned = Instant.parse("2026-08-28T20:00:00.123Z");
        pods.recordProvisionMetadata("life-w1", "sha256:abc", provisioned);
        Pod stamped = pods.findByPodId("life-w1").orElseThrow();
        assertThat(stamped.provisionedAt()).isEqualTo(provisioned);   // offset kept whatever the JVM zone
        assertThat(stamped.imageDigest()).isEqualTo("sha256:abc");

        // TIMESTAMP(3) rounds a microsecond instant, so start from a millisecond one.
        Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        runs.insertRun(new Run("run-life-1", "na-east", "blob-1", null, "lifecycle", "tester",
                RunState.STARTING, null, created, null, null, false, null));
        runs.insertFleetMember(new RunFleetMember("run-life-1", "life-w1", "na-east", MemberState.PENDING,
                null, null, "http://w1:8080", created, null, null, Map.of("threads", "5", "rampUp", "10"), null, null));
        runs.updateMemberState("run-life-1", "life-w1", MemberState.REQUESTED, null, null);   // null fanoutStatusCode
        runs.updateMemberState("run-life-1", "life-w1", MemberState.ACCEPTED, "accepted", 202);
        assertThat(pods.incrementRunsServed("life-w1")).isEqualTo(1);

        Run run = runs.findByRunId("run-life-1").orElseThrow();
        assertThat(run.createdAt()).isEqualTo(created);
        RunFleetMember m = run.fleetMembers().get(0);
        assertThat(m.properties()).containsEntry("threads", "5").containsEntry("rampUp", "10");
        assertThat(m.fanoutStatusCode()).isEqualTo(202);
        assertThat(m.state()).isEqualTo(MemberState.ACCEPTED);
        assertThat(m.startedAt()).isNotNull();
        assertThat(m.runsServed()).isEqualTo(1L);
        assertThat(m.joinedAtSecond()).isNull();

        runs.updateRunState("run-life-1", RunState.RUNNING, null);
        assertThat(runs.findByRunId("run-life-1").orElseThrow().startedAt()).isNotNull();
        assertThat(runs.updateRunStateClaimingTerminal("run-life-1", RunState.COMPLETED, "done")).isEqualTo(1);
        assertThat(runs.updateRunStateClaimingTerminal("run-life-1", RunState.FAILED, "late")).isZero();   // winner-only
        assertThat(runs.listRuns(new RunRepository.ListRunsCriteria(false, "lifecycle", false, false, 0, 10)).total()).isEqualTo(1);
    }

    @Test
    void concurrent_claims_never_share_a_worker() throws Exception {
        Application a = app("app-claim", "claims");
        for (int i = 1; i <= 5; i++) {
            pods.declareStatic("claim-w" + i, "na-east", "http://claim-w" + i + ":8080", a.metricsGroupId());
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<List<String>> first = pool.submit(() -> tx.execute(status -> {
            List<String> ids = idsOf(pods.claimIdleByGroup("na-east", a.metricsGroupId(), 2));
            firstHolds.countDown();
            try { secondDone.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ids;     // commit only after the second claimer has run
        }));
        Future<List<String>> second = pool.submit(() -> {
            firstHolds.await();
            try {
                return tx.execute(status -> idsOf(pods.claimIdleByGroup("na-east", a.metricsGroupId(), 5)));
            } finally {
                secondDone.countDown();
            }
        });
        List<String> held = first.get();
        List<String> rest = second.get();
        pool.shutdown();

        assertThat(held).hasSize(2);
        assertThat(rest).hasSize(3);
        assertThat(rest).doesNotContainAnyElementsOf(held);
        assertThat(pods.claimIdleByGroup("na-west", a.metricsGroupId(), 5)).isEmpty();   // region filter
        assertThat(pods.claimIdleByGroup("na-east", "demo", 5)).isEmpty();               // group filter
    }

    @Test
    void two_applications_in_one_group_share_the_pool_and_the_ceiling() {
        Application pci = app("app-pool-pci", "pool-pci", "pool");
        Application cpp = app("app-pool-cpp", "pool-cpp", "pool");
        assertThat(cpp.metricsGroupId()).isEqualTo(pci.metricsGroupId());
        for (int i = 1; i <= 3; i++) {
            pods.declareStatic("pool-w" + i, "na-east", "http://pool-w" + i + ":8080", "pool");
        }
        assertThat(pods.countByGroupAndRegion("pool", "na-east")).isEqualTo(3);
        assertThat(pods.findPodIdsByGroupAndRegion("pool", "na-east")).containsExactlyInAnyOrder("pool-w1", "pool-w2", "pool-w3");

        // Each application claims from the same pool: the first app's run holds 2 (its members make them
        // non-claimable), so the second app — whichever it is — gets the 1 left.
        Instant now = Instant.now();
        List<Pod> first = pods.claimIdleByGroup("na-east", pci.metricsGroupId(), 2);
        assertThat(first).hasSize(2);
        runs.insertRun(new Run("01J0POOLRUNPCIAAAAAAAAAAAA", "na-east", "b", null, pci.name(), "t", RunState.RUNNING, null,
                now, now, null, false, null, "pool"));
        for (Pod p : first) {
            runs.insertFleetMember(new RunFleetMember("01J0POOLRUNPCIAAAAAAAAAAAA", p.podId(), "na-east", MemberState.RUNNING, null,
                    null, p.baseUrl(), now, null, null));
        }
        List<Pod> second = pods.claimIdleByGroup("na-east", cpp.metricsGroupId(), 5);
        assertThat(second).hasSize(1);
        assertThat(idsOf(second)).doesNotContainAnyElementsOf(idsOf(first));
        runs.insertRun(new Run("01J0POOLRUNCPPAAAAAAAAAAAA", "na-east", "b", null, cpp.name(), "t", RunState.RUNNING, null,
                now, now, null, false, null, "pool"));
        runs.insertFleetMember(new RunFleetMember("01J0POOLRUNCPPAAAAAAAAAAAA", second.get(0).podId(), "na-east", MemberState.RUNNING,
                null, null, second.get(0).baseUrl(), now, null, null));

        // The ceiling counts active members of BOTH applications' runs by the run's group.
        assertThat(groupCapacity.countActivePodsForGroupRegion("pool", "na-east")).isEqualTo(3);
        assertThat(groupCapacity.countActivePodsForGroupRegion("pool", "na-west")).isZero();
        assertThat(groupCapacity.countActivePodsForGroupRegion("cps", "na-east")).isZero();

        // The group's capacity rows: upsert, read back, the delete guard sees the pool.
        ensureRegion("na-east", 20);
        groupCapacity.upsert("pool", "na-east", 3);
        assertThat(groupCapacity.find("pool", "na-east").orElseThrow().maxAvailable()).isEqualTo(3);
        assertThat(groupCapacity.findByGroupId("pool")).extracting(GroupCapacity::region).containsExactly("na-east");
        assertThat(groupCapacity.countByGroupId("pool")).isEqualTo(1);
        assertThat(groups.countPods("pool")).isEqualTo(3);
    }

    private static List<String> idsOf(List<Pod> claimed) {
        List<String> ids = new ArrayList<>();
        for (Pod p : claimed) ids.add(p.podId());
        return ids;
    }

    @Test
    void cron_claim_returns_due_jobs_earliest_first_and_only_those() {
        Instant now = Instant.now();
        cronJobs.insert(cron("cj-due-2", now.minus(Duration.ofMinutes(1)), true));
        cronJobs.insert(cron("cj-due-1", now.minus(Duration.ofMinutes(2)), true));
        cronJobs.insert(cron("cj-later", now.plus(Duration.ofHours(1)), true));
        cronJobs.insert(cron("cj-off", now.minus(Duration.ofMinutes(3)), false));

        List<CronJob> due = new TransactionTemplate(txManager)
                .execute(status -> cronJobs.findDueForUpdate(now, 10));
        assertThat(due).extracting(CronJob::cronJobId).containsExactly("cj-due-1", "cj-due-2");
        assertThat(due.get(0).enabled()).isTrue();     // NUMBER(1) → boolean
    }

    private static CronJob cron(String id, Instant nextFireAt, boolean enabled) {
        return new CronJob(id, id, null, null, "0 * * * * *", "UTC", enabled, "tester", Instant.now(),
                null, null, null, nextFireAt, null, CronJobKind.INFRA_READINESS, null, "x@example.test", null, null);
    }

    @Test
    void metrics_schema_is_reachable_through_the_reader_pool_with_unqualified_names() {
        // CURRENT_SCHEMA = CARDZATE_DB_GRAF on the reader pool: the hosted consumer's unqualified names resolve.
        assertThat(metricsReader.queryForObject("SELECT COUNT(*) FROM GROUP_REGISTRY WHERE ENABLED = 1", Integer.class)).isEqualTo(2);
        assertThat(metricsReader.queryForObject("SELECT TABLE_PREFIX FROM GROUP_REGISTRY WHERE GROUP_ID = ?", String.class, "cps")).isEqualTo("CPS");
        assertThat(metricsReader.queryForObject("SELECT COUNT(*) FROM CPS_METRICS", Integer.class)).isZero();
        // The purge pool resolves the same names and holds DELETE on the facts.
        assertThat(metricsPurge.queryForObject("SELECT COUNT(*) FROM DEMO_METRICS_H", Integer.class)).isZero();
    }

    @Test
    void application_group_round_trips_and_the_fk_refuses_a_delete_while_apps_remain() {
        ApplicationGroup g = groups.insert(new ApplicationGroup("grp", "Group under test", "db contract test", Instant.now(), null));
        assertThat(g.groupId()).isEqualTo("grp");
        assertThat(g.recyclePolicy()).isEqualTo(RecyclePolicy.REUSE);
        assertThat(groups.countApplications("grp")).isZero();

        Application a = applications.insert(new Application("app-grp", "grp-pci", null, null, List.of(),
                Instant.now(), null, null, null, "grp", "GRP-PCI"));
        assertThat(a.metricsGroupId()).isEqualTo("grp");
        assertThat(a.metricsApplication()).isEqualTo("GRP-PCI");
        assertThat(groups.applicationCounts()).containsEntry("grp", 1);

        // The policy and the ownership fields round-trip through the group row.
        // The notify lists are stored comma-separated (V5), so this is also the
        // proof that the split/join at the repository boundary is lossless.
        ApplicationGroup tuned = groups.update("grp", "Group under test", null, null, null, 7,
                RecyclePolicy.MAX_RUNS, 5, null, true, "Payments Platform",
                List.of("lead@example.com", "oncall@example.com"), List.of("manager@example.com"), List.of());
        assertThat(tuned.recyclePolicy()).isEqualTo(RecyclePolicy.MAX_RUNS);
        assertThat(tuned.maxRunsPerPod()).isEqualTo(5);
        assertThat(tuned.alwaysOn()).isTrue();
        assertThat(tuned.teamName()).isEqualTo("Payments Platform");
        assertThat(tuned.notifyTo()).containsExactly("lead@example.com", "oncall@example.com");
        assertThat(tuned.notifyCc()).containsExactly("manager@example.com");
        assertThat(tuned.notifyBcc()).isEmpty();

        assertThatThrownBy(() -> groups.delete("grp")).isInstanceOf(DataIntegrityViolationException.class);   // ORA-02292

        // The group is required (NOT NULL): an application moves, it never becomes ungrouped.
        if (groups.findById("demo").isEmpty()) {
            groups.insert(new ApplicationGroup("demo", "Demo", null, Instant.now(), null));
        }
        applications.update(a.applicationId(), a.name(), null, null, List.of(), "demo", "GRP-PCI");
        assertThat(applications.findById(a.applicationId()).orElseThrow().metricsGroupId()).isEqualTo("demo");
        assertThatThrownBy(() -> applications.update(a.applicationId(), a.name(), null, null, List.of(), null, null))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);   // ORA-01407: NOT NULL
        assertThat(groups.delete("grp")).isTrue();
        assertThat(groups.findById("grp")).isEmpty();

        // Display counts exclude ARCHIVED apps; the FK delete guard still sees them.
        groups.insert(new ApplicationGroup("ghostgrp", "Ghost", null, Instant.now(), null));
        Application ghost = applications.insert(new Application("app-ghost", "ghost-app", null, null, List.of(),
                Instant.now(), null, null, null, "ghostgrp", "GHOST"));
        assertThat(groups.applicationCounts()).containsEntry("ghostgrp", 1);
        assertThat(applications.softDelete(ghost.applicationId(), "ghost-app__deleted__t")).isTrue();   // archive
        assertThat(groups.applicationCounts()).doesNotContainKey("ghostgrp");
        assertThat(groups.countVisibleApplications("ghostgrp")).isZero();
        assertThat(groups.countApplications("ghostgrp")).isEqualTo(1);
        assertThatThrownBy(() -> groups.delete("ghostgrp")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ai_response_cache_round_trips_the_clob_expires_on_read_and_purges_by_run() {
        Instant dbBefore = owner.queryForObject("SELECT SYSTIMESTAMP FROM dual", OffsetDateTime.class).toInstant();
        aiResponses.upsert("insights", "run-ai-1", "v1", "{\"summary\":\"ok\"}", "claude-sonnet-4-6", 10, 20);
        var hit = aiResponses.find("insights", "run-ai-1", "v1", Duration.ofDays(30)).orElseThrow();
        assertThat(hit.responseJson()).isEqualTo("{\"summary\":\"ok\"}");   // the CLOB read by its bare column label
        assertThat(hit.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(hit.tokensIn()).isEqualTo(10);
        assertThat(hit.tokensOut()).isEqualTo(20);
        assertThat(hit.createdAt()).isAfterOrEqualTo(dbBefore.minusSeconds(1));   // the database's clock, not the JVM's

        aiResponses.upsert("insights", "run-ai-1", "v1", "{\"summary\":\"again\"}", "claude-sonnet-4-6", 1, 2);   // MERGE replaces
        assertThat(aiResponses.find("insights", "run-ai-1", "v1", Duration.ofDays(30)).orElseThrow().responseJson()).isEqualTo("{\"summary\":\"again\"}");
        assertThat(aiResponses.find("insights", "run-ai-1", "v1", Duration.ofDays(-1))).isEmpty();   // cutoff a day ahead: expired = a miss, whatever the two clocks say

        aiResponses.upsert("compare", "run-ai-1|run-ai-2", "v1", "{}", "claude-sonnet-4-6", 0, 0);
        assertThat(aiResponses.deleteForRun("run-ai-1")).isEqualTo(2);   // the single-run row and the comparison it sits in
        assertThat(aiResponses.find("compare", "run-ai-1|run-ai-2", "v1", Duration.ofDays(30))).isEmpty();
    }

    @Test
    void plugin_registry_rejects_duplicate_name_and_duplicate_content() {
        Instant now = Instant.now();
        pluginLibrary.insert(new Plugin("01T3PLGAAAAAAAAAAAAAAAAAA1", "casutg", "3.1",
                "01T3BLOBAAAAAAAAAAAAAAAA01", "sha-t3-1", 1024, "casutg.jar", null, "tester", now));
        assertThatThrownBy(() -> pluginLibrary.insert(new Plugin("01T3PLGAAAAAAAAAAAAAAAAAA2", "casutg", "2.0",
                "01T3BLOBAAAAAAAAAAAAAAAA02", "sha-t3-2", 1024, "casutg2.jar", null, "tester", now)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);   // ORCH_PLUGIN_NAME_UQ
        assertThatThrownBy(() -> pluginLibrary.insert(new Plugin("01T3PLGAAAAAAAAAAAAAAAAAA3", "renamed", "3.1",
                "01T3BLOBAAAAAAAAAAAAAAAA03", "sha-t3-1", 1024, "renamed.jar", null, "tester", now)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);   // ORCH_PLUGIN_SHA256_UQ
        assertThat(pluginLibrary.existsByBlobId("01T3BLOBAAAAAAAAAAAAAAAA01")).isTrue();
        assertThat(pluginLibrary.findByName("casutg").orElseThrow().version()).isEqualTo("3.1");
        pluginLibrary.delete("01T3PLGAAAAAAAAAAAAAAAAAA1");   // idempotent registry delete
        assertThat(pluginLibrary.findByName("casutg")).isEmpty();
    }

    @Test
    void member_properties_update_round_trips() {
        // UX-DYNAMICS T5 — the runtime push persists the merged map (JSON CLOB).
        Application a = app("app-props", "propsapp", "props_t5");   // own group — a RUNNING member must not pollute the cps ceiling test
        pods.declareStatic("props-w1", "na-east", "http://w1:8080", a.metricsGroupId());
        Run run = new Run("01T5PRPS0AAAAAAAAAAAAAAAA", "na-east", "plan-blob", null, a.name(),
                "tester", RunState.RUNNING, null, Instant.now(), Instant.now(), null,
                false, List.of(), a.metricsGroupId());
        runs.insertRun(run);
        runs.insertFleetMember(new RunFleetMember(run.runId(), "props-w1", "na-east",
                MemberState.RUNNING, null, null, "http://w1:8080", Instant.now(), Instant.now(), null,
                Map.of("USER_OFFSET", "0"), null, null));

        runs.updateMemberProperties(run.runId(), "props-w1",
                Map.of("USER_OFFSET", "0", "rampSeconds", "60"));

        RunFleetMember m = runs.findByRunId(run.runId()).orElseThrow().fleetMembers().get(0);
        assertThat(m.properties())
                .containsEntry("USER_OFFSET", "0")
                .containsEntry("rampSeconds", "60");
    }

    @Test
    void run_plugins_snapshot_round_trips_the_clob_and_gates_the_delete() {
        List<PluginRef> refs = List.of(
                new PluginRef("01T3PLGBBBBBBBBBBBBBBBBBB1", "casutg", "3.1", "01T3BLOBBBBBBBBBBBBBBBB01", "casutg.jar"),
                new PluginRef("01T3PLGBBBBBBBBBBBBBBBBBB2", "tst", "2.6", "01T3BLOBBBBBBBBBBBBBBBB02", "tst.zip"));
        runs.insertRun(new Run("01T3RUNPLUGINSAAAAAAAAAAA1", "na-east", "b", null, "pluginsApp", "t",
                RunState.PREPARING, null, Instant.now(), null, null, false, List.of(), null, refs));
        assertThat(runs.findByRunId("01T3RUNPLUGINSAAAAAAAAAAA1").orElseThrow().plugins())
                .containsExactlyElementsOf(refs);   // the CLOB round-trips in order
        // The delete gate's JSON_EXISTS probe sees the non-terminal snapshot…
        assertThat(pluginLibrary.countActiveRunsReferencing("01T3PLGBBBBBBBBBBBBBBBBBB1")).isEqualTo(1);
        assertThat(pluginLibrary.countActiveRunsReferencing("01T3PLGUNKNOWNAAAAAAAAAAAA")).isZero();
        // …and a pre-T3 constructor writes the '[]' default and reads back empty.
        runs.insertRun(new Run("01T3RUNPLUGINSAAAAAAAAAAA2", "na-east", "b", null, "pluginsApp", "t",
                RunState.PREPARING, null, Instant.now(), null, null, false, List.of()));
        assertThat(runs.findByRunId("01T3RUNPLUGINSAAAAAAAAAAA2").orElseThrow().plugins()).isEmpty();
    }

    @Test
    @DisplayName("V4 cluster registry: the reservation FK holds, the region-row lock serialises "
            + "concurrent reservations (no oversubscription window), and the probe verdict round-trips")
    void cluster_registry_fk_lock_and_probe() throws Exception {
        // FK: a reservation on an unregistered cluster is refused by the schema itself.
        if (groups.findById("v4grp").isEmpty()) {
            groups.insert(new ApplicationGroup("v4grp", "Group v4grp", "db contract test", Instant.now(), null));
        }
        if (groups.findById("v4grp2").isEmpty()) {
            groups.insert(new ApplicationGroup("v4grp2", "Group v4grp2", "db contract test", Instant.now(), null));
        }
        assertThatThrownBy(() -> groupCapacity.upsert("v4grp", "never-registered", 1))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The schema itself holds the three-way uniqueness (id is the PK) and
        // the 20-worker cap.
        ensureRegion("v4uniq", 20);
        assertThatThrownBy(() -> regionRepo.insert("v4uniq2", "v4uniq DC", "http://other:30088", 20))
                .as("duplicate LABEL").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> regionRepo.insert("v4uniq2", "other DC", "http://v4uniq:30088", 20))
                .as("duplicate REGIONAL_URL").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> regionRepo.insert("v4over", "v4over DC", "http://v4over:30088", 21))
                .as("MAX_WORKERS above the 20 cap").isInstanceOf(DataIntegrityViolationException.class);

        // Probe verdict round-trip on the row.
        ensureRegion("v4lock", 10);
        regionRepo.recordProbe("v4lock", false, "probe worker did not become ready — Unschedulable");
        var probed = regionRepo.find("v4lock").orElseThrow();
        assertThat(probed.lastProbeStatus()).isEqualTo("FAIL");
        assertThat(probed.lastProbeDetail()).contains("Unschedulable");
        assertThat(probed.lastProbeAt()).isNotNull();

        // Serialisation: transaction A locks the region row and reserves 6 of 10 for
        // v4grp before committing; a concurrent reserve of 5 for v4grp2 must WAIT on
        // the row lock, then see A's 6 and refuse (6 + 5 > 10). Without the FOR UPDATE
        // it would have read 0 reserved and oversubscribed the cluster.
        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> bOutcome = new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
                assertThat(regionRepo.lockMaxWorkers("v4lock")).contains(10);
                groupCapacity.upsert("v4grp", "v4lock", 6);
                aHoldsLock.countDown();
                try {
                    // Hold the lock long enough for B to be provably blocked on it.
                    assertThat(bFinished.await(1, java.util.concurrent.TimeUnit.SECONDS))
                            .as("B must still be waiting on A's region-row lock")
                            .isFalse();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            Future<?> b = pool.submit(() -> {
                try {
                    aHoldsLock.await();
                    reservations.reserve("v4grp2", "v4lock", 5);
                } catch (Throwable t) {
                    bOutcome.set(t);
                } finally {
                    bFinished.countDown();
                }
            });
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(bOutcome.get())
                .isInstanceOf(com.perf.globalorchestrator.service.GroupReservationService.ClusterCapacityExceededException.class)
                .hasMessageContaining("other groups hold 6 of its 10");
        assertThat(groupCapacity.find("v4grp2", "v4lock")).isEmpty();

        // A reservation that fits goes through, and the rollup sees both dimensions.
        var written = reservations.reserve("v4grp2", "v4lock", 4);
        assertThat(written.maxAvailable()).isEqualTo(4);
        assertThat(groupCapacity.reservedByRegion().get("v4lock")).isEqualTo(10);
        assertThat(groupCapacity.countByRegion("v4lock")).isEqualTo(2);
    }
}
