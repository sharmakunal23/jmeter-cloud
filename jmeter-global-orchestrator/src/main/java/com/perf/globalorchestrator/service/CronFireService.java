package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.client.DocumentServiceClient.TemplateUnavailableException;
import com.perf.globalorchestrator.client.TemplateBody;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobFire;
import com.perf.globalorchestrator.domain.CronJobFireOutcome;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.email.EmailSender;
import com.perf.globalorchestrator.http.StartRunRequest;
import com.perf.globalorchestrator.report.DailyReportComposer;
import com.perf.globalorchestrator.report.InfraReadinessComposer;
import com.perf.globalorchestrator.provision.PodRecycler;
import com.perf.globalorchestrator.provision.PodSpinService;
import com.perf.globalorchestrator.provision.ProvisioningMode;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import com.perf.globalorchestrator.provision.RecycleEvaluator.RecycleReason;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.CronJobFireHistoryRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The fire path: claim due schedules, and launch a run from a
 * schedule's saved Template. The launch reuses {@link RunService#startRun}, so
 * a scheduled run is byte-for-byte the run a human click would have produced
 * (same capacity gates, same fan-out, same audit trail — just a
 * {@code system} actor).
 *
 * <p>Split deliberately from {@link com.perf.globalorchestrator.sweep.CronJobScheduler}:
 * {@link #claimDue()} is {@code @Transactional} and the scheduler is a separate
 * bean, so the call crosses the Spring proxy (a self-invocation would silently
 * drop the transaction — the same gotcha {@code RunService} documents). The
 * claim holds {@code FOR UPDATE SKIP LOCKED} row locks only for the brief
 * claim+reschedule; the slow launch happens OUTSIDE any transaction.
 */
@Service
public class CronFireService {

    private static final Logger LOG = LoggerFactory.getLogger(CronFireService.class);
    private static final int MAX_ERROR_LEN = 1000;
    /** STATIC-FLEET Phase 2 — fire detail for the two provisioning cron kinds. */
    private static final String PROVISIONING_STATIC_DETAIL =
            "provisioning is disabled (" + ProvisioningMode.PROPERTY + "=STATIC) — "
            + "workers are operator-managed in this deployment";

    private final CronJobRepository cronJobs;
    private final CronJobFireHistoryRepository fireHistory;
    private final DocumentServiceClient documentService;
    private final RunService runService;
    private final RunRepository runs;
    // Drain/provision dependencies.
    private final ApplicationRepository applications;
    private final GroupCapacityRepository capacities;
    private final ApplicationGroupRepository groups;
    private final PodRepository pods;
    /**
     * Absent under {@code PROVISIONING_MODE=STATIC}
     * (recycling an operator-managed worker is not ours to do). Only
     * dereferenced from {@link #fireDrainRegion}, which returns SKIPPED
     * before reaching it in that mode.
     */
    private final ObjectProvider<PodRecycler> recycler;
    private final PodSpinService spinService;
    private final ProvisioningProperties provisioning;
    // AUTOMATION Phase E/D — report email dependencies.
    private final EmailSender emailSender;
    private final InfraReadinessComposer infraComposer;
    private final DailyReportComposer dailyComposer;
    private final String reportRecipientsFallback;
    private final int maxDueBatch;

    public CronFireService(CronJobRepository cronJobs,
                           CronJobFireHistoryRepository fireHistory,
                           DocumentServiceClient documentService,
                           RunService runService,
                           RunRepository runs,
                           ApplicationRepository applications,
                           GroupCapacityRepository capacities,
                           ApplicationGroupRepository groups,
                           PodRepository pods,
                           ObjectProvider<PodRecycler> recycler,
                           PodSpinService spinService,
                           ProvisioningProperties provisioning,
                           EmailSender emailSender,
                           InfraReadinessComposer infraComposer,
                           DailyReportComposer dailyComposer,
                           @Value("${globalOrchestrator.automation.reportRecipients:}") String reportRecipientsFallback,
                           @Value("${globalOrchestrator.automation.maxDueBatch:50}") int maxDueBatch) {
        this.cronJobs = cronJobs;
        this.fireHistory = fireHistory;
        this.documentService = documentService;
        this.runService = runService;
        this.runs = runs;
        this.applications = applications;
        this.capacities = capacities;
        this.groups = groups;
        this.pods = pods;
        this.recycler = recycler;
        this.spinService = spinService;
        this.provisioning = provisioning;
        this.emailSender = emailSender;
        this.infraComposer = infraComposer;
        this.dailyComposer = dailyComposer;
        this.reportRecipientsFallback = reportRecipientsFallback;
        this.maxDueBatch = maxDueBatch;
    }

    /**
     * Atomically claim the due schedules: lock them with
     * {@code FOR UPDATE SKIP LOCKED}, advance each {@code nextFireAt} to its
     * next future slot, and stamp {@code claimedAt}. Returns the pre-advance
     * snapshots for the caller to fire OUTSIDE this transaction.
     *
     * <p>Advancing {@code nextFireAt} <em>here</em> (not after the fire) is the
     * exactly-once fence: once committed, no sibling replica's sweep re-selects
     * the row, and a crash before firing simply skips this one window
     * (catch-up-once — we never double-fire).
     */
    @Transactional
    public List<CronJob> claimDue() {
        Instant now = Instant.now();
        List<CronJob> due = cronJobs.findDueForUpdate(now, maxDueBatch);
        for (CronJob job : due) {
            Instant next;
            try {
                next = CronSchedule.nextFireAfter(job.cronExpression(), job.timeZone(), now);
            } catch (RuntimeException e) {
                // Validated at create/update, so this is defensive: push the
                // next fire out a day rather than risk a hot-loop on a row that
                // somehow has a bad expression.
                LOG.warn("cron {} ({}) reschedule failed; deferring 24h: {}",
                        job.cronJobId(), job.name(), e.toString());
                next = now.plusSeconds(86_400);
            }
            cronJobs.reschedule(job.cronJobId(), next, now);
        }
        return due;
    }

    /**
     * Fire a schedule and record the outcome. Dispatches on {@link CronJob#kind()}:
     * LAUNCH_RUN launches a run from a saved template; DRAIN_REGION recycles
     * every IDLE worker in (app, region) without replacement; PROVISION_REGION
     * spins workers up to the configured cap. Never throws — every failure
     * mode maps to a {@link CronJobFireOutcome} so the sweep loop is unkillable
     * and the operator always gets a fire-history row.
     *
     * <p>{@code actor} is {@code system:scheduler} for an automatic fire and
     * the operator's {@code X-Actor} for a manual {@code fireNow}. Drain /
     * provision don't create a run so they don't carry the actor into an
     * audit trail; LAUNCH_RUN does (via {@code startRun}).
     */
    public FireResult fire(CronJob job, Actor actor) {
        return switch (job.kind()) {
            case LAUNCH_RUN       -> fireLaunchRun(job, actor);
            case DRAIN_REGION     -> fireDrainRegion(job);
            case PROVISION_REGION -> fireProvisionRegion(job);
            case INFRA_READINESS  -> fireInfraReadiness(job);
            case DAILY_REPORT     -> fireDailyReport(job);
        };
    }

    /**
     * AUTOMATION Phase E (goal #2) — compose the infra-readiness report and
     * email it. SKIPPED when no recipients are configured (the schedule's
     * {@code recipients} or the {@code AUTOMATION_REPORT_RECIPIENTS} fallback);
     * FAILED on a delivery error. No run is created.
     */
    private FireResult fireInfraReadiness(CronJob job) {
        Instant firedAt = Instant.now();
        List<String> recipients = resolveRecipients(job);
        if (recipients.isEmpty()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED,
                    "no recipients (set the schedule's recipients or AUTOMATION_REPORT_RECIPIENTS)");
        }
        try {
            InfraReadinessComposer.Report report = infraComposer.compose();
            emailSender.send(recipients,
                    infraComposer.subject(report, job.customSubject()),
                    infraComposer.renderHtml(report, job.customIntro()));
            return record(job, firedAt, null, CronJobFireOutcome.LAUNCHED,
                    "emailed " + recipients.size() + " recipient(s) via " + emailSender.backend()
                            + " — " + (report.allClear() ? "all clear" : "issues detected"));
        } catch (EmailSender.EmailException e) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("INFRA_READINESS {} ({}) unexpected failure", job.cronJobId(), job.name(), e);
            return record(job, firedAt, null, CronJobFireOutcome.FAILED, e.toString());
        }
    }

    /**
     * AUTOMATION Phase D (goal #1) — compose the daily perf-test report and
     * email it. Same shape as {@link #fireInfraReadiness}: SKIPPED when no
     * recipients are configured; FAILED on a delivery error. No run is created
     * (the report reads existing run + runTrend data).
     */
    private FireResult fireDailyReport(CronJob job) {
        Instant firedAt = Instant.now();
        List<String> recipients = resolveRecipients(job);
        if (recipients.isEmpty()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED,
                    "no recipients (set the schedule's recipients or AUTOMATION_REPORT_RECIPIENTS)");
        }
        try {
            DailyReportComposer.Report report = dailyComposer.compose();
            emailSender.send(recipients,
                    dailyComposer.subject(report, job.customSubject()),
                    dailyComposer.renderHtml(report, job.customIntro()));
            return record(job, firedAt, null, CronJobFireOutcome.LAUNCHED,
                    "emailed " + recipients.size() + " recipient(s) via " + emailSender.backend()
                            + " — " + report.totalRuns() + " run(s), "
                            + report.topRegressions().size() + " regression(s)");
        } catch (EmailSender.EmailException e) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("DAILY_REPORT {} ({}) unexpected failure", job.cronJobId(), job.name(), e);
            return record(job, firedAt, null, CronJobFireOutcome.FAILED, e.toString());
        }
    }

    /** Recipients from the schedule, falling back to the env list. */
    private List<String> resolveRecipients(CronJob job) {
        String raw = (job.recipients() != null && !job.recipients().isBlank())
                ? job.recipients() : reportRecipientsFallback;
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private FireResult fireLaunchRun(CronJob job, Actor actor) {
        Instant firedAt = Instant.now();
        String runId = null;
        CronJobFireOutcome outcome;
        String error = null;
        try {
            // Overlap guard — don't pile a second run on top of the previous
            // fire's run if it's still going (SKIPPED, try again next window).
            String prevRunId = job.lastFiredRunId();
            if (prevRunId != null && !prevRunId.isBlank()) {
                Optional<Run> prev = runs.findByRunId(prevRunId);
                if (prev.isPresent() && !prev.get().state().isTerminal()) {
                    return record(job, firedAt, null, CronJobFireOutcome.SKIPPED,
                            "previous run " + prevRunId + " still " + prev.get().state());
                }
            }
            TemplateBody tpl = documentService.fetchTemplate(job.templateBlobId());
            Run run = runService.startRun(toStartRunRequest(tpl), false, actor);
            runId = run.runId();
            outcome = CronJobFireOutcome.LAUNCHED;
        } catch (TemplateUnavailableException e) {
            outcome = CronJobFireOutcome.FAILED;
            error = e.getMessage();
        } catch (RunService.InsufficientCapacityException
                 | RunService.GroupCapacityExceededException e) {
            // No free workers right now — operator action, not a defect.
            outcome = CronJobFireOutcome.SKIPPED;
            error = e.getMessage();
        } catch (RunService.FleetSizeExceededException | IllegalArgumentException e) {
            // Malformed template (no test plan, oversized fleet, bad region).
            outcome = CronJobFireOutcome.FAILED;
            error = "invalid template: " + e.getMessage();
        } catch (RuntimeException e) {
            outcome = CronJobFireOutcome.FAILED;
            error = e.toString();
            LOG.error("cron fire {} ({}) unexpected failure", job.cronJobId(), job.name(), e);
        }
        return record(job, firedAt, runId, outcome, error);
    }

    /**
     * Drain every IDLE worker of the app's group in the region without
     * replacement, via {@link PodRecycler#drainOne}. SKIPs when the group is
     * {@code alwaysOn} (production-like protection). IN_USE workers are left
     * alone (the recycler's existing IDLE-only race guard). The fire is a
     * no-op success when nothing is idle — reported as LAUNCHED with a
     * "drained 0/0" detail rather than SKIPPED, because the schedule itself
     * did fire as intended.
     */
    private FireResult fireDrainRegion(CronJob job) {
        Instant firedAt = Instant.now();
        // The recycler is not wired on an
        // operator-managed fleet. SKIPPED (not FAILED): the schedule is
        // valid, it just has nothing it may do in this deployment, and a
        // recurring FAILED would look like a broken job forever.
        if (provisioning.isStatic()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED, PROVISIONING_STATIC_DETAIL);
        }
        Application app = applications.findByName(job.applicationName()).orElse(null);
        if (app == null) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED,
                    "application not registered: " + job.applicationName());
        }
        ApplicationGroup group = groups.findById(app.metricsGroupId()).orElse(null);
        if (group == null) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED,
                    "group not registered: " + app.metricsGroupId());
        }
        if (group.alwaysOn()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED,
                    "group '" + group.groupId() + "' is alwaysOn — drain suppressed");
        }
        String region = job.region();
        List<Pod> snapshot = pods.findByGroupAndRegion(group.groupId(), region);
        int idle = 0;
        int drained = 0;
        for (Pod p : snapshot) {
            if (p.state() != PodState.IDLE) continue;
            idle++;
            try {
                if (recycler.getObject().drainOne(p, group, RecycleReason.DRAIN_AFTER_RUN)) drained++;
            } catch (RuntimeException e) {
                // Per-pod failure shouldn't abort the batch.
                LOG.warn("DRAIN_REGION {} ({}): drain of pod {} failed",
                        job.cronJobId(), job.name(), p.podId(), e);
            }
        }
        return record(job, firedAt, null, CronJobFireOutcome.LAUNCHED,
                "drained " + drained + "/" + idle + " idle worker(s) in " + region);
    }

    /**
     * Spin workers in the app's group's pool in the region up to
     * {@code groupCapacity.maxAvailable}. SKIPs when the region has no
     * capacity row (the operator must configure a cap before scheduling
     * provision). A per-spin failure logs and breaks early so we don't hammer
     * a broken provisioner — the next window will retry the remaining gap.
     */
    private FireResult fireProvisionRegion(CronJob job) {
        Instant firedAt = Instant.now();
        // See fireDrainRegion.
        if (provisioning.isStatic()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED, PROVISIONING_STATIC_DETAIL);
        }
        Application app = applications.findByName(job.applicationName()).orElse(null);
        if (app == null) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED,
                    "application not registered: " + job.applicationName());
        }
        String region = job.region();
        String groupId = app.metricsGroupId();
        Optional<GroupCapacity> cap = capacities.find(groupId, region);
        if (cap.isEmpty()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED,
                    "no capacity configured for group '" + groupId
                            + "' in region '" + region + "'");
        }
        int max = cap.get().maxAvailable();
        int current = pods.countByGroupAndRegion(groupId, region);
        int gap = Math.max(0, max - current);
        int spun = 0;
        for (int i = 0; i < gap; i++) {
            try {
                spinService.spin(groupId, region);
                spun++;
            } catch (RuntimeException e) {
                LOG.warn("PROVISION_REGION {} ({}): spin {}/{} failed; aborting batch",
                        job.cronJobId(), job.name(), i + 1, gap, e);
                break;
            }
        }
        return record(job, firedAt, null, CronJobFireOutcome.LAUNCHED,
                "provisioned " + spun + "/" + gap + " worker(s) in " + region
                        + " (current=" + current + ", max=" + max + ")");
    }

    private FireResult record(CronJob job, Instant firedAt, String runId,
                              CronJobFireOutcome outcome, String error) {
        // Two writes: the schedule row's last-fire summary (does NOT touch
        // nextFireAt — the claim already advanced it; a manual fireNow leaves
        // the schedule's cadence untouched) + the append-only history row.
        cronJobs.recordFire(job.cronJobId(), firedAt, runId, outcome.name());
        fireHistory.insert(new CronJobFire(
                Ulid.generate(), job.cronJobId(), firedAt, outcome.name(), runId, truncate(error)));
        LOG.info("cron fire {} ({}) → {}{}{}",
                job.cronJobId(), job.name(), outcome,
                runId != null ? " runId=" + runId : "",
                error != null ? " (" + error + ")" : "");
        return new FireResult(outcome, runId, error);
    }

    /** Map a saved template to a launch request — faithful to the UI launcher:
     *  {@code fleetAllocation} wins (its per-worker {@code perNodeProperties}
     *  snapshots already bake in the global props), no spin-on-shortfall
     *  (a schedule never auto-provisions — that's a cost decision the operator
     *  makes by pre-provisioning capacity), and {@code initiatedBy} is left
     *  null so the {@code actor} drives attribution. */
    /** Package-private for the mapping test. */
    static StartRunRequest toStartRunRequest(TemplateBody t) {
        return new StartRunRequest(
                t.testPlanBlobId(),
                t.dataFilesBlobId(),
                t.application(),
                0,                       // fleetSize — unused when fleetAllocation is present
                List.of(),               // regions — legacy single-region path, unused
                t.fleetAllocation(),
                null,                    // initiatedBy — derived from the actor
                Boolean.FALSE,           // spinShortfall — schedules never auto-spin
                t.saveResults(),
                t.pluginIds());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    /** Result of a single fire — surfaced by {@code fireNow}. */
    public record FireResult(CronJobFireOutcome outcome, String runId, String error) {}
}
