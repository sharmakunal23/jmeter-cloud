package com.perf.globalorchestrator.db;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobKind;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.MetricsPurgeRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control-plane schema's contract against the real migration on Oracle
 * Free: two claimers never share a worker, the cron claim returns due rows in
 * order, JSON and timestamps round-trip, NULLs inside expressions bind, and a
 * purge empties every metrics table for the run.
 */
@SpringBootTest(properties = {
        "globalOrchestrator.pod.sweepInitialDelayMs=3600000",
        "globalOrchestrator.pod.lostAfterMs=3600000"
})
@DisplayName("control-plane schema on Oracle — claims, JSON, purge")
class GlobalRunDbTest extends OracleDbTestSupport {

    @Autowired ApplicationRepository applications;
    @Autowired PodRepository pods;
    @Autowired RunRepository runs;
    @Autowired CronJobRepository cronJobs;
    @Autowired MetricsPurgeRepository purge;
    @Autowired PlatformTransactionManager txManager;

    private final JdbcTemplate globalOwner = globalOwner();
    private final JdbcTemplate metricsOwner = metricsOwner();

    private Application app(String id, String name) {
        return applications.insert(new Application(id, name, null, "db contract test",
                List.of("http://" + name + "/health"), null, Instant.now(), null, null, null,
                RecyclePolicy.REUSE, null, null, false));
    }

    @Test
    void both_migrations_applied_and_every_object_is_valid() {
        assertThat(globalOwner.queryForObject("SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID'", Integer.class)).isZero();
        assertThat(metricsOwner.queryForObject("SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID'", Integer.class)).isZero();
        assertThat(globalOwner.queryForObject("SELECT COUNT(*) FROM user_tables WHERE table_name <> 'flyway_schema_history'", Integer.class)).isEqualTo(12);
    }

    @Test
    void run_lifecycle_round_trips_json_timestamps_and_null_binds() {
        Application a = app("app-life", "lifecycle");
        assertThat(a.healthEndpoints()).containsExactly("http://lifecycle/health");   // CLOB JSON → list

        pods.declareStatic("life-w1", "na-east", "http://w1:8080", a.applicationId());
        pods.register("life-w1", "na-east", "http://w1-self:8080", a.applicationId());   // MERGE matched: declared address wins
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

        Instant created = Instant.now();
        runs.insertRun(new Run("run-life-1", "na-east", "blob-1", null, "lifecycle", "tester",
                RunState.STARTING, null, created, null, null, false, null));
        runs.insertFleetMember(new RunFleetMember("run-life-1", "life-w1", "na-east", MemberState.PENDING,
                null, null, "http://w1:8080", created, null, null, Map.of("threads", "5", "rampUp", "10"), null, null));
        runs.updateMemberState("run-life-1", "life-w1", MemberState.REQUESTED, null, null);   // null fanoutStatusCode
        runs.updateMemberState("run-life-1", "life-w1", MemberState.ACCEPTED, "accepted", 202);
        assertThat(pods.incrementRunsServed("life-w1")).isEqualTo(1);

        Run run = runs.findByRunId("run-life-1").orElseThrow();
        assertThat(run.createdAt()).isEqualTo(created.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
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
            pods.declareStatic("claim-w" + i, "na-east", "http://claim-w" + i + ":8080", a.applicationId());
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<List<String>> first = pool.submit(() -> tx.execute(status -> {
            List<String> ids = idsOf(pods.claimIdleByRegionAndApp("na-east", a.applicationId(), 2));
            firstHolds.countDown();
            try { secondDone.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ids;     // commit only after the second claimer has run
        }));
        Future<List<String>> second = pool.submit(() -> {
            firstHolds.await();
            try {
                return tx.execute(status -> idsOf(pods.claimIdleByRegionAndApp("na-east", a.applicationId(), 5)));
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
        assertThat(pods.claimIdleByRegionAndApp("na-west", a.applicationId(), 5)).isEmpty();   // region filter
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
    void purge_empties_every_metrics_table_for_the_run() {
        String run = "run-purge-1";
        long t = Instant.now().getEpochSecond();
        Integer landed = metricsOwner.execute((ConnectionCallback<Integer>) con -> {
            // Staging rows live in the session, so the whole ingest is one transaction on one connection.
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO metrics.\"workerMetricStage\" (\"runId\",\"workerId\",\"label\",\"windowSecond\",\"region\","
                    + "\"throughput\",\"errorCount\",\"sumElapsedMs\",\"p50Ms\",\"p90Ms\",\"p95Ms\",\"p99Ms\",\"maxMs\",\"activeThreads\","
                    + "\"bytesReceived\",\"bytesSent\") VALUES (?,?,?,?,?,100,5,25000,200,400,500,900,1500,10,10000,2000)");
                 PreparedStatement st = con.prepareStatement(
                    "INSERT INTO metrics.\"workerMetricStatusStage\" (\"runId\",\"workerId\",\"label\",\"windowSecond\",\"region\",\"code\",\"n\") "
                    + "VALUES (?,?,?,?,?,'200',95)")) {
                for (int s = 0; s < 3; s++) {
                    for (PreparedStatement p : List.of(ps, st)) {
                        p.setString(1, run); p.setString(2, "w1"); p.setString(3, "login");
                        p.setLong(4, t + s); p.setString(5, "na-east");
                        p.addBatch();
                    }
                }
                ps.executeBatch();
                st.executeBatch();
            }
            int n;
            try (CallableStatement cs = con.prepareCall("BEGIN metrics.\"metricsIngest\".\"ingestStaged\"(?); END;")) {
                cs.registerOutParameter(1, Types.NUMERIC);
                cs.execute();
                n = cs.getInt(1);
            }
            con.commit();
            return n;
        });
        assertThat(landed).isEqualTo(3);
        assertThat(countIn("runLabel", run)).isEqualTo(1);

        assertThat(purge.deleteByRunId(run)).isEqualTo(3);
        for (String table : List.of("workerMetric", "workerMetricStatus", "runSecond", "runSecondStatus", "runLabel")) {
            assertThat(countIn(table, run)).as(table).isZero();
        }
        assertThat(purge.deleteByRunIds(List.of("never-existed-1", "never-existed-2"))).isZero();   // no-bounds fallback
    }

    private long countIn(String table, String run) {
        Long n = metricsOwner.queryForObject(
                "SELECT COUNT(*) FROM metrics.\"" + table + "\" WHERE \"runId\" = ?", Long.class, run);
        return n == null ? 0 : n;
    }
}
