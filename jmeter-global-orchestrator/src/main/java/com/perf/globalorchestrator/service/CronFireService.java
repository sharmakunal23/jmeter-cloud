package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobFire;
import com.perf.globalorchestrator.domain.CronJobFireOutcome;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.email.EmailSender;
import com.perf.globalorchestrator.report.DailyReportComposer;
import com.perf.globalorchestrator.report.InfraReadinessComposer;
import com.perf.globalorchestrator.provision.PodRecycler;
import com.perf.globalorchestrator.provision.PodSpinService;
import com.perf.globalorchestrator.provision.RecycleEvaluator.RecycleReason;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.CronJobFireHistoryRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The fire path: claim due schedules and act on them. A scheduled launch goes
 * through {@link WorkflowService#launch}, so it is the execution a human click
 * would have produced — same capacity pre-flight, same one-at-a-time rule, same
 * audit trail, just a {@code system} actor.
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

    private final CronJobRepository cronJobs;
    private final CronJobFireHistoryRepository fireHistory;
    /** The only launch path: a schedule starts a workflow, never a run directly. */
    private final WorkflowService workflowService;
    // Scaling dependencies.
    private final GroupCapacityRepository capacities;
    private final ApplicationGroupRepository groups;
    private final PodRepository pods;
    /** Always wired; it skips {@code SOURCE=STATIC} rows itself (CLUSTER-CAPACITY). */
    private final PodRecycler recycler;
    private final PodSpinService spinService;
    // AUTOMATION Phase E/D — report email dependencies.
    private final EmailSender emailSender;
    private final InfraReadinessComposer infraComposer;
    private final DailyReportComposer dailyComposer;
    private final String reportRecipientsFallback;
    private final int maxDueBatch;

    public CronFireService(CronJobRepository cronJobs,
                           CronJobFireHistoryRepository fireHistory,
                           WorkflowService workflowService,
                           GroupCapacityRepository capacities,
                           ApplicationGroupRepository groups,
                           PodRepository pods,
                           PodRecycler recycler,
                           PodSpinService spinService,
                           EmailSender emailSender,
                           InfraReadinessComposer infraComposer,
                           DailyReportComposer dailyComposer,
                           @Value("${globalOrchestrator.automation.reportRecipients:}") String reportRecipientsFallback,
                           @Value("${globalOrchestrator.automation.maxDueBatch:50}") int maxDueBatch) {
        this.cronJobs = cronJobs;
        this.fireHistory = fireHistory;
        this.workflowService = workflowService;
        this.capacities = capacities;
        this.groups = groups;
        this.pods = pods;
        this.recycler = recycler;
        this.spinService = spinService;
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
     * LAUNCH_WORKFLOW launches the group's workflow; SCALE_IN releases every
     * IDLE worker in (group, region) without replacement; SCALE_OUT spins
     * workers up to the group's reservation. Never throws — every failure mode
     * maps to a {@link CronJobFireOutcome} so the sweep loop is unkillable and
     * the operator always gets a fire-history row.
     *
     * <p>{@code actor} is {@code system:scheduler} for an automatic fire and
     * the operator's {@code X-Actor} for a manual {@code fireNow}. Scaling
     * creates no run, so it carries no actor into an audit trail;
     * LAUNCH_WORKFLOW does, via the execution it starts.
     */
    public FireResult fire(CronJob job, Actor actor) {
        return switch (job.kind()) {
            case LAUNCH_WORKFLOW  -> fireLaunchWorkflow(job, actor);
            case SCALE_IN         -> fireScaleIn(job);
            case SCALE_OUT        -> fireScaleOut(job);
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

    /**
     * Launch the schedule's workflow. The engine owns every guard, so this
     * method's whole job is translating its refusals into an outcome an
     * operator can read.
     *
     * <p><b>SKIPPED is not failure.</b> "Already running" and
     * "the graph no longer fits the group's reservation" mean this window did
     * not run and the next one may — a schedule that reported FAILED for those
     * would train operators to ignore the status. FAILED is reserved for a
     * schedule that cannot work until someone changes something: a deleted
     * workflow, or a graph the validator rejects.
     */
    private FireResult fireLaunchWorkflow(CronJob job, Actor actor) {
        Instant firedAt = Instant.now();
        try {
            WorkflowExecution execution = workflowService.launch(job.workflowId(), actor);
            return record(job, firedAt, execution.executionId(), CronJobFireOutcome.LAUNCHED, null);
        } catch (WorkflowService.WorkflowNotFoundException e) {
            // ORCH_CRON_JOB.WORKFLOW_ID carries no FK on purpose, so a deleted
            // workflow leaves the schedule standing — say so rather than
            // letting it disappear unnoticed.
            return record(job, firedAt, null, CronJobFireOutcome.FAILED,
                    "workflow no longer exists: " + job.workflowId());
        } catch (WorkflowService.WorkflowAlreadyRunningException
                 | WorkflowService.WorkflowCapacityExceededException
                 | WorkflowService.WorkflowDisabledException e) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED, e.getMessage());
        } catch (WorkflowService.WorkflowInvalidException e) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED,
                    "workflow is not launchable: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("cron fire {} ({}) unexpected failure", job.cronJobId(), job.name(), e);
            return record(job, firedAt, null, CronJobFireOutcome.FAILED, e.toString());
        }
    }

    /**
     * Release every IDLE worker of the group in the region without
     * replacement, via {@link PodRecycler#drainOne}. SKIPs when the group is
     * {@code alwaysOn} (production-like protection). IN_USE workers are left
     * alone (the recycler's existing IDLE-only race guard). The fire is a
     * no-op success when nothing is idle — reported as LAUNCHED with a
     * "drained 0/0" detail rather than SKIPPED, because the schedule itself
     * did fire as intended.
     */
    private FireResult fireScaleIn(CronJob job) {
        Instant firedAt = Instant.now();
        ApplicationGroup group = groups.findById(job.groupId()).orElse(null);
        if (group == null) {
            return record(job, firedAt, null, CronJobFireOutcome.FAILED,
                    "group not registered: " + job.groupId());
        }
        if (group.alwaysOn()) {
            return record(job, firedAt, null, CronJobFireOutcome.SKIPPED,
                    "group '" + group.groupId() + "' is alwaysOn — scale in suppressed");
        }
        String region = job.region();
        List<Pod> snapshot = pods.findByGroupAndRegion(group.groupId(), region);
        int idle = 0;
        int drained = 0;
        for (Pod p : snapshot) {
            if (p.state() != PodState.IDLE) continue;
            if (p.source() == com.perf.globalorchestrator.domain.PodSource.STATIC) continue;   // operator's worker
            idle++;
            try {
                if (recycler.drainOne(p, group, RecycleReason.DRAIN_AFTER_RUN)) drained++;
            } catch (RuntimeException e) {
                // Per-pod failure shouldn't abort the batch.
                LOG.warn("SCALE_IN {} ({}): release of pod {} failed",
                        job.cronJobId(), job.name(), p.podId(), e);
            }
        }
        return record(job, firedAt, null, CronJobFireOutcome.LAUNCHED,
                "released " + drained + "/" + idle + " idle worker(s) in " + region);
    }

    /**
     * Spin workers in the group's pool in the region up to
     * {@code groupCapacity.maxAvailable}. SKIPs when the group holds no
     * reservation on that cluster (the operator must reserve capacity before
     * scheduling a scale-out). A per-spin failure logs and breaks early so we
     * don't hammer a broken provisioner — the next window retries the gap.
     */
    private FireResult fireScaleOut(CronJob job) {
        Instant firedAt = Instant.now();
        String region = job.region();
        String groupId = job.groupId();
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
                LOG.warn("SCALE_OUT {} ({}): spin {}/{} failed; aborting batch",
                        job.cronJobId(), job.name(), i + 1, gap, e);
                break;
            }
        }
        return record(job, firedAt, null, CronJobFireOutcome.LAUNCHED,
                "provisioned " + spun + "/" + gap + " worker(s) in " + region
                        + " (current=" + current + ", max=" + max + ")");
    }

    private FireResult record(CronJob job, Instant firedAt, String executionId,
                              CronJobFireOutcome outcome, String error) {
        // Two writes: the schedule row's last-fire summary (does NOT touch
        // nextFireAt — the claim already advanced it; a manual fireNow leaves
        // the schedule's cadence untouched) + the append-only history row.
        cronJobs.recordFire(job.cronJobId(), firedAt, executionId, outcome.name());
        fireHistory.insert(new CronJobFire(
                Ulid.generate(), job.cronJobId(), firedAt, outcome.name(), executionId, truncate(error)));
        LOG.info("cron fire {} ({}) → {}{}{}",
                job.cronJobId(), job.name(), outcome,
                executionId != null ? " executionId=" + executionId : "",
                error != null ? " (" + error + ")" : "");
        return new FireResult(outcome, executionId, error);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    /** Result of a single fire — surfaced by {@code fireNow}. */
    public record FireResult(CronJobFireOutcome outcome, String executionId, String error) {}
}
