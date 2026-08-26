package com.perf.k8sorchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.k8sorchestrator.client.DocumentServiceClient;
import com.perf.k8sorchestrator.client.DocumentServiceClient.BlobAccessException;
import com.perf.k8sorchestrator.domain.Actor;
import com.perf.k8sorchestrator.domain.Run;
import com.perf.k8sorchestrator.domain.RunState;
import com.perf.k8sorchestrator.repo.AiResponseRepository;
import com.perf.k8sorchestrator.repo.MetricsPurgeRepository;
import com.perf.k8sorchestrator.repo.PurgeAuditRepository;
import com.perf.k8sorchestrator.repo.RunRepository;
import com.perf.k8sorchestrator.repo.RunTrendRepository;
import com.perf.k8sorchestrator.service.RunPurgeService.PurgeResult;
import com.perf.k8sorchestrator.service.RunPurgeService.RunNotPurgeableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HARD-DELETE / purge — unit-pins
 * {@link RunPurgeService}: the trash-first + terminal guards, the cross-store
 * fan-out order, the shared-blob ref-count guard, and the
 * blob-service-unreachable degraded path. Repos + document-service are Mockito
 * mocks; the {@code self} proxy field is reflected to {@code this} so the
 * {@code @Transactional} tail runs inline against the mocks.
 */
@DisplayName("RunPurgeService — hard delete / purge")
class RunPurgeServiceTest {

    private static final String RUN_ID = "01HRUNPURGE0000000000000AA";

    private RunRepository runs;
    private RunTrendRepository runTrends;
    private AiResponseRepository aiResponses;
    private MetricsPurgeRepository metricsPurge;
    private DocumentServiceClient docClient;
    private PurgeAuditRepository purgeAudit;
    private RunPurgeService svc;

    @BeforeEach
    void setUp() throws Exception {
        runs = mock(RunRepository.class);
        runTrends = mock(RunTrendRepository.class);
        aiResponses = mock(AiResponseRepository.class);
        metricsPurge = mock(MetricsPurgeRepository.class);
        docClient = mock(DocumentServiceClient.class);
        purgeAudit = mock(PurgeAuditRepository.class);
        svc = new RunPurgeService(runs, runTrends, aiResponses, metricsPurge, docClient,
                purgeAudit, new ObjectMapper());
        // Wire the @Lazy self-proxy to this instance so the @Transactional tail
        // executes inline (no Spring context in a unit test).
        Field self = RunPurgeService.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(svc, svc);
    }

    private Run run(RunState state, String testPlanBlobId, String dataFilesBlobId) {
        return new Run(RUN_ID, "us-east-1", testPlanBlobId, dataFilesBlobId, "checkout",
                "alice", state, null, Instant.now(), null, Instant.now(), false, List.of());
    }

    @Test
    @DisplayName("unknown run → RunNotFoundException (404)")
    void unknownRun() {
        when(runs.findByRunId(RUN_ID)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> svc.purgeRun(RUN_ID, Actor.ANONYMOUS_ACTOR, null))
                .isInstanceOf(RunService.RunNotFoundException.class);
        verify(metricsPurge, never()).deleteByRunId(any());
    }

    @Test
    @DisplayName("active run → RUN_NOT_PURGEABLE, nothing deleted")
    void activeRun() {
        when(runs.findByRunId(RUN_ID)).thenReturn(java.util.Optional.of(run(RunState.RUNNING, "tp", null)));
        assertThatThrownBy(() -> svc.purgeRun(RUN_ID, Actor.ANONYMOUS_ACTOR, null))
                .isInstanceOf(RunNotPurgeableException.class);
        verify(metricsPurge, never()).deleteByRunId(any());
        verify(runs, never()).deleteRunRow(any());
    }

    @Test
    @DisplayName("terminal but not hidden → RUN_NOT_PURGEABLE (trash-first)")
    void notHidden() {
        when(runs.findByRunId(RUN_ID)).thenReturn(java.util.Optional.of(run(RunState.COMPLETED, "tp", null)));
        when(runs.isRunHidden(RUN_ID)).thenReturn(false);
        assertThatThrownBy(() -> svc.purgeRun(RUN_ID, Actor.ANONYMOUS_ACTOR, null))
                .isInstanceOf(RunNotPurgeableException.class);
        verify(metricsPurge, never()).deleteByRunId(any());
        verify(runs, never()).deleteRunRow(any());
    }

    @Test
    @DisplayName("happy path — deletes blobs, metrics, run-state in order + tombstone")
    void happyPath() {
        when(runs.findByRunId(RUN_ID)).thenReturn(java.util.Optional.of(run(RunState.COMPLETED, "tpBlob", "dfBlob")));
        when(runs.isRunHidden(RUN_ID)).thenReturn(true);
        when(docClient.listResultBlobIds(RUN_ID)).thenReturn(List.of("res1", "res2"));
        when(runs.countOtherRunsReferencingBlob(eq("tpBlob"), eq(RUN_ID))).thenReturn(0);
        when(runs.countOtherRunsReferencingBlob(eq("dfBlob"), eq(RUN_ID))).thenReturn(0);
        when(metricsPurge.deleteByRunId(RUN_ID)).thenReturn(42L);

        PurgeResult result = svc.purgeRun(RUN_ID, Actor.fromHeader("alice"), "cleanup");

        assertThat(result.metricRowsDeleted()).isEqualTo(42L);
        assertThat(result.blobsDeleted()).isEqualTo(4);   // res1, res2, tpBlob, dfBlob
        assertThat(result.blobStepComplete()).isTrue();

        verify(docClient).deleteBlob("res1");
        verify(docClient).deleteBlob("res2");
        verify(docClient).deleteBlob("tpBlob");
        verify(docClient).deleteBlob("dfBlob");
        // Metrics deleted before the run row (so a metrics failure can't orphan rows).
        var io = inOrder(metricsPurge, aiResponses, runTrends, runs, purgeAudit);
        io.verify(metricsPurge).deleteByRunId(RUN_ID);
        io.verify(aiResponses).deleteForRun(RUN_ID);
        io.verify(runTrends).deleteByRunId(RUN_ID);
        io.verify(runs).deleteRunRow(RUN_ID);
        io.verify(purgeAudit).record(any(), eq("run"), eq(RUN_ID), eq("checkout"),
                eq("alice"), eq("cleanup"), eq(42L), eq(4), eq(null), any());
    }

    @Test
    @DisplayName("shared testPlan blob is kept when another run still references it")
    void sharedBlobKept() {
        when(runs.findByRunId(RUN_ID)).thenReturn(java.util.Optional.of(run(RunState.COMPLETED, "sharedTp", null)));
        when(runs.isRunHidden(RUN_ID)).thenReturn(true);
        when(docClient.listResultBlobIds(RUN_ID)).thenReturn(List.of("res1"));
        when(runs.countOtherRunsReferencingBlob(eq("sharedTp"), eq(RUN_ID))).thenReturn(2);
        when(metricsPurge.deleteByRunId(RUN_ID)).thenReturn(7L);

        PurgeResult result = svc.purgeRun(RUN_ID, Actor.ANONYMOUS_ACTOR, null);

        assertThat(result.blobsDeleted()).isEqualTo(1);   // only res1
        verify(docClient).deleteBlob("res1");
        verify(docClient, never()).deleteBlob("sharedTp");
        verify(runs).deleteRunRow(RUN_ID);                // DB purge still happens
    }

    @Test
    @DisplayName("document-service unreachable → DB purge still completes, blobStepComplete=false")
    void blobServiceUnreachable() {
        when(runs.findByRunId(RUN_ID)).thenReturn(java.util.Optional.of(run(RunState.COMPLETED, "tp", null)));
        when(runs.isRunHidden(RUN_ID)).thenReturn(true);
        when(docClient.listResultBlobIds(RUN_ID)).thenThrow(new BlobAccessException("connection refused"));
        when(metricsPurge.deleteByRunId(RUN_ID)).thenReturn(5L);

        PurgeResult result = svc.purgeRun(RUN_ID, Actor.ANONYMOUS_ACTOR, null);

        assertThat(result.blobStepComplete()).isFalse();
        assertThat(result.blobsDeleted()).isZero();
        verify(metricsPurge).deleteByRunId(RUN_ID);
        verify(runs).deleteRunRow(RUN_ID);
    }
}
