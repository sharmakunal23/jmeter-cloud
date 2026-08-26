package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.repo.ApplicationHealthHistoryRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.PurgeAuditRepository;
import com.perf.globalorchestrator.repo.RunRepository;
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
 * HARD-DELETE / purge Phase 2 — the irreversible
 * second tier for applications. {@code DELETE /applications/{id}} "hides" an app
 * (renames it to an archived name, frees the original name, re-tags its
 * runs to the archived name — see {@code ApplicationController});
 * this PHYSICALLY removes a hidden app and everything still bound to it:
 *
 * <ol>
 *   <li>every one of the app's runs — purged via {@link RunPurgeService}
 *       (result/testPlan/dataFiles blobs, metrics rows, run-state rows);</li>
 *   <li>the app's {@code pod} registry rows (cleared BEFORE the app row — the
 *       {@code pod.applicationId} FK is {@code ON DELETE RESTRICT});</li>
 *   <li>its {@code applicationHealthHistory};</li>
 *   <li>the {@code application} row itself ({@code applicationCapacity} cascades);</li>
 *   <li>a single {@code purgeAudit} tombstone for the whole sweep.</li>
 * </ol>
 *
 * <p>Precondition: the app must already be HIDDEN ({@code 409 APPLICATION_NOT_PURGEABLE}
 * otherwise; {@code 404} when unknown). A hidden app has no active runs (the hide
 * guard enforces it), so its runs are all terminal and its pods are idle registry
 * rows.
 *
 * <p>Retry-safe: every step is idempotent. Each run purge is independently
 * committed, so a failure partway leaves the app hidden with fewer runs; a retry
 * re-lists the remaining runs and finishes.
 */
@Service
public class ApplicationPurgeService {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationPurgeService.class);

    private final ApplicationRepository applications;
    private final RunRepository runs;
    private final RunPurgeService runPurge;
    private final PodRepository pods;
    private final ApplicationHealthHistoryRepository healthHistory;
    private final PurgeAuditRepository purgeAudit;
    private final ObjectMapper json;

    @Autowired
    @Lazy
    private ApplicationPurgeService self;

    public ApplicationPurgeService(ApplicationRepository applications,
                                   RunRepository runs,
                                   RunPurgeService runPurge,
                                   PodRepository pods,
                                   ApplicationHealthHistoryRepository healthHistory,
                                   PurgeAuditRepository purgeAudit,
                                   ObjectMapper json) {
        this.applications = applications;
        this.runs = runs;
        this.runPurge = runPurge;
        this.pods = pods;
        this.healthHistory = healthHistory;
        this.purgeAudit = purgeAudit;
        this.json = json;
    }

    /**
     * Permanently deletes a hidden application and its entire footprint.
     *
     * @throws UnknownApplicationException        when the id is unknown (404).
     * @throws ApplicationNotPurgeableException   when the app exists but has not
     *                                            been hidden first (409).
     */
    public AppPurgeResult purgeApplication(String applicationId, Actor actor, String reason) {
        Application app = applications.findHiddenById(applicationId).orElseGet(() -> {
            // Not hidden — distinguish "exists but visible" (must hide first, 409)
            // from "unknown" (404).
            if (applications.findById(applicationId).isPresent()) {
                throw new ApplicationNotPurgeableException(applicationId,
                        "application " + applicationId + " must be hidden (deleted) before it can be purged");
            }
            throw new UnknownApplicationException(applicationId);
        });

        // The app's runs were re-tagged to its archived name at hide time.
        String archivedName = app.name();
        List<String> runIds = runs.findRunIdsByApplication(archivedName);

        long totalMetricRows = 0L;
        int totalBlobs = 0;
        int childRunsPurged = 0;
        boolean blobStepComplete = true;
        for (String runId : runIds) {
            Run run = runs.findByRunId(runId).orElse(null);
            if (run == null) continue;   // already gone (retry); skip
            RunPurgeService.PurgeResult r = runPurge.purgeRunArtifacts(run, actor, reason, false);
            totalMetricRows += r.metricRowsDeleted();
            totalBlobs += r.blobsDeleted();
            childRunsPurged++;
            if (!r.blobStepComplete()) blobStepComplete = false;
        }

        // Pods + health history + the app row + tombstone — one transaction.
        String detailsJson = writeDetails(blobStepComplete);
        self.commitApplicationPurge(applicationId, archivedName, actor, reason,
                totalMetricRows, totalBlobs, childRunsPurged, detailsJson);

        LOG.info("Application {} (archived '{}') purged by {}: {} runs, {} metric rows, {} blobs "
                + "(blobStepComplete={})",
                applicationId, archivedName, actor.name(), childRunsPurged, totalMetricRows,
                totalBlobs, blobStepComplete);
        return new AppPurgeResult(applicationId, childRunsPurged, totalMetricRows, totalBlobs,
                blobStepComplete);
    }

    /**
     * Transactional tail: delete the app's pod rows (clearing the RESTRICT FK),
     * its health-transition log, the application row ({@code applicationCapacity}
     * cascades), and write the single application tombstone — atomically.
     */
    @Transactional("transactionManager")
    protected void commitApplicationPurge(String applicationId, String archivedName, Actor actor,
                                          String reason, long metricRows, int blobs, int childRuns,
                                          String detailsJson) {
        pods.deleteByApplicationId(applicationId);
        healthHistory.deleteByApplicationId(applicationId);
        applications.delete(applicationId);   // applicationCapacity cascades
        purgeAudit.record(Ulid.generate(), "application", applicationId, archivedName,
                actor.name(), reason, metricRows, blobs, childRuns, detailsJson);
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

    /** Outcome of an application purge. */
    public record AppPurgeResult(String applicationId, int runsPurged, long metricRowsDeleted,
                                 int blobsDeleted, boolean blobStepComplete) { }

    /** Unknown application id — maps to 404. */
    public static class UnknownApplicationException extends RuntimeException {
        public UnknownApplicationException(String applicationId) {
            super("application not found: " + applicationId);
        }
    }

    /** The app exists but has not been hidden first — maps to 409. */
    public static class ApplicationNotPurgeableException extends RuntimeException {
        private final String applicationId;
        public ApplicationNotPurgeableException(String applicationId, String message) {
            super(message);
            this.applicationId = applicationId;
        }
        public String applicationId() { return applicationId; }
    }
}
