package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.observability.SpanAttributes;
import com.perf.globalorchestrator.repo.AiResponseRepository;
import com.perf.globalorchestrator.repo.MetricsPurgeRepository;
import com.perf.globalorchestrator.repo.PurgeAuditRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunTrendRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HARD-DELETE / purge — the irreversible second
 * tier of the two-tier delete model. {@link RunService#deleteRun} "hides" a run
 * (reversible, {@code hiddenAt}); this PHYSICALLY removes a hidden run and the
 * storage it occupies, to reclaim space:
 *
 * <ol>
 *   <li><b>Result blobs</b> in document-service ({@code results-{runId}-*}),
 *       plus the run's testPlan/dataFiles blobs <em>iff</em> no other run still
 *       references them (shared uploaded plans survive).</li>
 *   <li><b>Metrics</b> — the run's per-second rows in
 *       {@code metrics."workerMetric"} (the bulk of the bytes), via the
 *       purge-only datasource.</li>
 *   <li><b>Run-state</b> — {@code aiResponse} + {@code runTrend} rows, then the
 *       {@code run} row itself (cascading its fleet members + audit events), and
 *       a {@code purgeAudit} tombstone, all in one transaction.</li>
 * </ol>
 *
 * <h2>Ordering &amp; retry-safety</h2>
 * Every step is idempotent, so a partially-completed purge is safe to re-run.
 * The order is deliberate:
 * <ul>
 *   <li>Blobs first, and <em>tolerant</em>: if document-service is unreachable
 *       the purge records the blob step as incomplete and proceeds — orphaned,
 *       runId-named result blobs are reclaimable by a future retention sweep, and
 *       wedging the whole purge on a transient blob-service blip would be worse.</li>
 *   <li>Metrics next, and <em>required</em>: if the metrics DELETE fails the
 *       purge ABORTS before touching the run row. The run row is the only handle
 *       on those rows (runId), so deleting it first would orphan them
 *       un-findably. Leaving the run hidden lets the operator retry.</li>
 *   <li>Run-state last, transactionally — the run only disappears once its
 *       artifacts are gone.</li>
 * </ul>
 */
@Service
public class RunPurgeService {

    private static final Logger LOG = LoggerFactory.getLogger(RunPurgeService.class);

    private final RunRepository runs;
    private final RunTrendRepository runTrends;
    private final AiResponseRepository aiResponses;
    private final MetricsPurgeRepository metricsPurge;
    private final DocumentServiceClient docClient;
    private final PurgeAuditRepository purgeAudit;
    private final ObjectMapper json;

    /**
     * Self-reference so {@link #commitRunStatePurge} runs through the Spring proxy
     * and its {@code @Transactional} actually applies (proxy-mode self-invocation
     * limitation, same pattern as {@link RunService}). {@code @Lazy} breaks the
     * construction-time cycle.
     */
    @Autowired
    @Lazy
    private RunPurgeService self;

    private final Counter runsPurged;
    private final Counter metricRowsPurged;
    private final Counter blobsPurged;

    public RunPurgeService(RunRepository runs,
                           RunTrendRepository runTrends,
                           AiResponseRepository aiResponses,
                           MetricsPurgeRepository metricsPurge,
                           DocumentServiceClient docClient,
                           PurgeAuditRepository purgeAudit,
                           ObjectMapper json,
                           MeterRegistry meterRegistry) {
        this.runs = runs;
        this.runTrends = runTrends;
        this.aiResponses = aiResponses;
        this.metricsPurge = metricsPurge;
        this.docClient = docClient;
        this.purgeAudit = purgeAudit;
        this.json = json;
        this.runsPurged = Counter.builder("globalOrchestrator.runs.purged")
                .description("Runs permanently deleted (hard delete / purge).")
                .register(meterRegistry);
        this.metricRowsPurged = Counter.builder("globalOrchestrator.purge.metricRows")
                .description("metrics.workerMetric rows removed by run purges.")
                .register(meterRegistry);
        this.blobsPurged = Counter.builder("globalOrchestrator.purge.blobs")
                .description("document-service blobs removed by run purges.")
                .register(meterRegistry);
    }

    /**
     * Permanently deletes a hidden, terminal run. See the class javadoc for the
     * ordering + retry-safety contract.
     *
     * @throws RunService.RunNotFoundException when the run is unknown (404).
     * @throws RunNotPurgeableException        when the run is still active or has
     *                                         not been hidden first (409).
     */
    public PurgeResult purgeRun(String runId, Actor actor, String reason) {
        SpanAttributes.tag("runId", runId);
        SpanAttributes.tag("actor", actor.name());

        Run run = runs.findByRunId(runId)
                .orElseThrow(() -> new RunService.RunNotFoundException(runId));
        if (!run.state().isTerminal()) {
            throw new RunNotPurgeableException(runId,
                    "run " + runId + " is still active — only terminal runs can be purged");
        }
        if (!runs.isRunHidden(runId)) {
            throw new RunNotPurgeableException(runId,
                    "run " + runId + " must be hidden (deleted) before it can be permanently purged");
        }
        return purgeRunArtifacts(run, actor, reason, /* writeTombstone */ true);
    }

    /**
     * The per-run fan-out, WITHOUT the trash-first / terminal guards — the
     * reusable core shared by {@link #purgeRun} (which guards first) and the
     * application purge ({@code ApplicationPurgeService}, which purges every one
     * of a hidden app's runs as a cascade). See the class javadoc for the
     * ordering + retry-safety contract.
     *
     * @param writeTombstone {@code true} for a standalone run purge (writes a
     *        {@code run} tombstone atomically with the run-state delete);
     *        {@code false} for the app cascade (the single app-level tombstone
     *        records the whole sweep, so per-run tombstones would be noise).
     */
    PurgeResult purgeRunArtifacts(Run run, Actor actor, String reason, boolean writeTombstone) {
        String runId = run.runId();

        // ── 1. Blobs — best-effort, surfaced ────────────────────────────
        int blobsDeleted = 0;
        boolean blobStepComplete = true;
        try {
            for (String blobId : docClient.listResultBlobIds(runId)) {
                docClient.deleteBlob(blobId);
                blobsDeleted++;
            }
            blobsDeleted += deleteBlobIfUnreferenced(run.testPlanBlobId(), runId);
            blobsDeleted += deleteBlobIfUnreferenced(run.dataFilesBlobId(), runId);
        } catch (DocumentServiceClient.BlobAccessException e) {
            blobStepComplete = false;
            LOG.warn("Run {} purge: blob cleanup incomplete ({}); proceeding with DB purge. "
                    + "Orphaned result blobs (results-{}-*) remain for a future retention sweep.",
                    runId, e.getMessage(), runId);
        }
        blobsPurged.increment(blobsDeleted);

        // ── 2. Metrics — required; abort before touching the run row ─────
        long metricRows = metricsPurge.deleteByRunId(runId);
        metricRowsPurged.increment(metricRows);

        // ── 3. Run-state — transactional, through the self proxy ─────────
        String detailsJson = writeDetails(blobStepComplete);
        self.commitRunStatePurge(runId, run.application(), actor, reason,
                metricRows, blobsDeleted, writeTombstone, detailsJson);

        runsPurged.increment();
        LOG.info("Run {} purged by {}: {} metric rows, {} blobs (blobStepComplete={})",
                runId, actor.name(), metricRows, blobsDeleted, blobStepComplete);
        return new PurgeResult(runId, metricRows, blobsDeleted, blobStepComplete);
    }

    /**
     * Transactional tail: drop AI cache + trend rows, delete the run row
     * (cascading members + events), and — when {@code writeTombstone} — the
     * {@code run} tombstone, all atomically.
     */
    @Transactional("transactionManager")
    protected void commitRunStatePurge(String runId, String application, Actor actor, String reason,
                                       long metricRows, int blobsDeleted, boolean writeTombstone,
                                       String detailsJson) {
        aiResponses.deleteForRun(runId);
        runTrends.deleteByRunId(runId);
        runs.deleteRunRow(runId);   // cascades runFleetMember + runEvent
        if (writeTombstone) {
            purgeAudit.record(Ulid.generate(), "run", runId, application, actor.name(), reason,
                    metricRows, blobsDeleted, /* childRunsPurged */ null, detailsJson);
        }
    }

    /** Deletes {@code blobId} only when no OTHER run references it. Returns 1 if deleted, else 0. */
    private int deleteBlobIfUnreferenced(String blobId, String runId) {
        if (blobId == null || blobId.isBlank()) return 0;
        if (runs.countOtherRunsReferencingBlob(blobId, runId) > 0) {
            LOG.debug("Run {} purge: keeping shared blob {} (still referenced by another run)",
                    runId, blobId);
            return 0;
        }
        docClient.deleteBlob(blobId);
        return 1;
    }

    private String writeDetails(boolean blobStepComplete) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("blobStepComplete", blobStepComplete);
        try {
            return json.writeValueAsString(details);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Outcome of a run purge — surfaced to the operator so a partial blob step is visible. */
    public record PurgeResult(String runId, long metricRowsDeleted, int blobsDeleted,
                              boolean blobStepComplete) { }

    /**
     * The run cannot be purged in its current state — it's still active, or it
     * has not been hidden (trashed) first. Maps to 409.
     */
    public static class RunNotPurgeableException extends RuntimeException {
        private final String runId;
        public RunNotPurgeableException(String runId, String message) {
            super(message);
            this.runId = runId;
        }
        public String runId() { return runId; }
    }
}
