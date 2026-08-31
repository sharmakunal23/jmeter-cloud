package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobFire;
import com.perf.globalorchestrator.domain.CronJobFireOutcome;
import com.perf.globalorchestrator.domain.CronJobKind;
import com.perf.globalorchestrator.email.EmailSender;
import com.perf.globalorchestrator.provision.PodRecycler;
import com.perf.globalorchestrator.provision.PodSpinService;
import com.perf.globalorchestrator.report.DailyReportComposer;
import com.perf.globalorchestrator.report.InfraReadinessComposer;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.CronJobFireHistoryRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fire path's outcome mapping. The rule under test is which refusals are
 * SKIPPED and which are FAILED: SKIPPED means "this window did not run and the
 * next one may", FAILED means "this schedule cannot work until someone changes
 * something". Getting it backwards trains an operator to ignore the status.
 */
@DisplayName("CronFireService — what each refusal becomes (AUTOMATION-3)")
class CronFireServiceTest {

    private CronJobRepository cronJobs;
    private CronJobFireHistoryRepository fireHistory;
    private WorkflowService workflowService;
    private GroupCapacityRepository capacities;
    private ApplicationGroupRepository groups;
    private PodRepository pods;
    private PodRecycler recycler;
    private PodSpinService spinService;
    private CronFireService service;

    private static final Actor ACTOR = Actor.system("scheduler");

    @BeforeEach
    void setUp() {
        cronJobs = mock(CronJobRepository.class);
        fireHistory = mock(CronJobFireHistoryRepository.class);
        workflowService = mock(WorkflowService.class);
        capacities = mock(GroupCapacityRepository.class);
        groups = mock(ApplicationGroupRepository.class);
        pods = mock(PodRepository.class);
        recycler = mock(PodRecycler.class);
        spinService = mock(PodSpinService.class);
        service = new CronFireService(cronJobs, fireHistory, workflowService, capacities, groups,
                pods, recycler, spinService, mock(EmailSender.class),
                mock(InfraReadinessComposer.class), mock(DailyReportComposer.class), "", 50);
    }

    private static CronJob workflowJob() {
        return new CronJob("01CRON", "nightly", "cps", "01WF", "0 2 * * *", "UTC", true,
                "tester", Instant.now(), null, null, null, Instant.now(), null,
                CronJobKind.LAUNCH_WORKFLOW, null, null, null, null);
    }

    private static CronJob scalingJob(CronJobKind kind) {
        return new CronJob("01CRON", "overnight", "cps", null, "0 20 * * *", "UTC", true,
                "tester", Instant.now(), null, null, null, Instant.now(), null,
                kind, "na-east", null, null, null);
    }

    private static ApplicationGroup group(boolean alwaysOn) {
        return new ApplicationGroup("cps", "Servicing MQ", null, null, null, 7,
                com.perf.globalorchestrator.domain.RecyclePolicy.REUSE, null, null, alwaysOn,
                Instant.now(), 1, List.of());
    }

    @Test
    @DisplayName("a launched workflow records LAUNCHED with the execution it started")
    void launchRecordsTheExecution() {
        com.perf.globalorchestrator.domain.WorkflowExecution ex =
                mock(com.perf.globalorchestrator.domain.WorkflowExecution.class);
        when(ex.executionId()).thenReturn("01EXEC");
        when(workflowService.launch(eq("01WF"), any())).thenReturn(ex);

        CronFireService.FireResult r = service.fire(workflowJob(), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.LAUNCHED);
        assertThat(r.executionId()).isEqualTo("01EXEC");
        // Both writes happen: the row's last-fire summary AND the history row.
        verify(cronJobs).recordFire(eq("01CRON"), any(), eq("01EXEC"), eq("LAUNCHED"));
        ArgumentCaptor<CronJobFire> fire = ArgumentCaptor.forClass(CronJobFire.class);
        verify(fireHistory).insert(fire.capture());
        assertThat(fire.getValue().executionId()).isEqualTo("01EXEC");
        assertThat(fire.getValue().errorReason()).isNull();
    }

    @Test
    @DisplayName("already running is SKIPPED, not FAILED — the next window may well run")
    void alreadyRunningIsSkipped() {
        when(workflowService.launch(anyString(), any()))
                .thenThrow(new WorkflowService.WorkflowAlreadyRunningException("01WF", 1));

        CronFireService.FireResult r = service.fire(workflowJob(), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.SKIPPED);
        assertThat(r.error()).contains("already has 1 running");
        assertThat(r.executionId()).isNull();
    }

    @Test
    @DisplayName("a graph that outgrew the group's reservation is SKIPPED, and says by how much")
    void capacityExceededIsSkipped() {
        when(workflowService.launch(anyString(), any()))
                .thenThrow(new WorkflowService.WorkflowCapacityExceededException(List.of(
                        new WorkflowValidation.RegionDemand("na-east", 6, List.of("perf"), 4, false))));

        CronFireService.FireResult r = service.fire(workflowJob(), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.SKIPPED);
        assertThat(r.error()).contains("na-east").contains("6").contains("4");
    }

    @Test
    @DisplayName("a disabled workflow is SKIPPED — the operator turned it off on purpose")
    void disabledWorkflowIsSkipped() {
        when(workflowService.launch(anyString(), any()))
                .thenThrow(new WorkflowService.WorkflowDisabledException("01WF"));

        assertThat(service.fire(workflowJob(), ACTOR).outcome()).isEqualTo(CronJobFireOutcome.SKIPPED);
    }

    @Test
    @DisplayName("a DELETED workflow is FAILED — nothing will fix itself, and the row carries no FK to say so")
    void deletedWorkflowIsFailed() {
        when(workflowService.launch(anyString(), any()))
                .thenThrow(new WorkflowService.WorkflowNotFoundException("01WF"));

        CronFireService.FireResult r = service.fire(workflowJob(), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.FAILED);
        assertThat(r.error()).contains("no longer exists").contains("01WF");
    }

    @Test
    @DisplayName("an unexpected error is FAILED and never escapes — the sweep loop must be unkillable")
    void unexpectedErrorIsContained() {
        when(workflowService.launch(anyString(), any())).thenThrow(new IllegalStateException("boom"));

        CronFireService.FireResult r = service.fire(workflowJob(), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.FAILED);
        assertThat(r.error()).contains("boom");
        verify(fireHistory).insert(any());
    }

    @Test
    @DisplayName("scale in on an always-on group is SKIPPED, and releases nothing")
    void scaleInRespectsAlwaysOn() {
        when(groups.findById("cps")).thenReturn(Optional.of(group(true)));

        CronFireService.FireResult r = service.fire(scalingJob(CronJobKind.SCALE_IN), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.SKIPPED);
        assertThat(r.error()).contains("alwaysOn");
        verify(recycler, never()).drainOne(any(), any(), any());
    }

    @Test
    @DisplayName("scale in reads the group directly — no application lookup stands between the schedule and its pool")
    void scaleInUsesTheGroupOnTheSchedule() {
        when(groups.findById("cps")).thenReturn(Optional.of(group(false)));
        when(pods.findByGroupAndRegion("cps", "na-east")).thenReturn(List.of());

        CronFireService.FireResult r = service.fire(scalingJob(CronJobKind.SCALE_IN), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.LAUNCHED);
        assertThat(r.error()).contains("released 0/0");
        verify(pods).findByGroupAndRegion("cps", "na-east");
    }

    @Test
    @DisplayName("scale out with no reservation on that cluster is SKIPPED, and spins nothing")
    void scaleOutWithoutAReservationIsSkipped() {
        when(capacities.find("cps", "na-east")).thenReturn(Optional.empty());

        CronFireService.FireResult r = service.fire(scalingJob(CronJobKind.SCALE_OUT), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.SKIPPED);
        verify(spinService, never()).spin(anyString(), anyString());
    }

    @Test
    @DisplayName("scale out fills the gap up to the group's reservation, and no further")
    void scaleOutFillsTheGap() {
        when(capacities.find("cps", "na-east")).thenReturn(Optional.of(
                new com.perf.globalorchestrator.domain.GroupCapacity("cps", "na-east", 4, null, null)));
        when(pods.countByGroupAndRegion("cps", "na-east")).thenReturn(1);

        CronFireService.FireResult r = service.fire(scalingJob(CronJobKind.SCALE_OUT), ACTOR);

        assertThat(r.outcome()).isEqualTo(CronJobFireOutcome.LAUNCHED);
        verify(spinService, org.mockito.Mockito.times(3)).spin("cps", "na-east");
    }
}
