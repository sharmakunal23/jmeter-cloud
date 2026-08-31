package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.CronJob;
import com.perf.globalorchestrator.domain.CronJobKind;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.CronJobFireHistoryRepository;
import com.perf.globalorchestrator.repo.CronJobRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.service.CronFireService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The create-time validation, which exists so a fire can never quietly act on
 * something the operator did not name: a workflow must belong to the group that
 * schedules it, and a cluster must be one that group has reserved capacity on.
 * The CHECK constraint is the backstop; these are the messages a human reads.
 */
@DisplayName("CronJobController — group-scoped validation (AUTOMATION-3)")
class CronJobControllerTest {

    private CronJobRepository cronJobs;
    private ApplicationGroupRepository groups;
    private WorkflowRepository workflows;
    private GroupCapacityRepository capacities;
    private MockMvc mvc;

    private static final ApplicationGroup CPS =
            new ApplicationGroup("cps", "Servicing MQ", null, Instant.parse("2026-08-31T00:00:00Z"), 1);

    private static Workflow workflow(String id, String groupId) {
        return new Workflow(id, groupId, "Nightly regression", null, null, true, 1,
                "tester", Instant.now(), "tester", Instant.now(), null);
    }

    @BeforeEach
    void setUp() {
        cronJobs = mock(CronJobRepository.class);
        groups = mock(ApplicationGroupRepository.class);
        workflows = mock(WorkflowRepository.class);
        capacities = mock(GroupCapacityRepository.class);
        when(workflows.namesById()).thenReturn(Map.of());
        when(cronJobs.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc = MockMvcBuilders.standaloneSetup(new CronJobController(
                cronJobs, mock(CronJobFireHistoryRepository.class), groups, workflows,
                capacities, mock(CronFireService.class))).build();
    }

    private static String body(String json) { return json; }

    @Test
    @DisplayName("a workflow schedule is created against its group")
    void createWorkflowSchedule() throws Exception {
        when(groups.findById("cps")).thenReturn(Optional.of(CPS));
        when(workflows.findById("01WF")).thenReturn(Optional.of(workflow("01WF", "cps")));

        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"nightly","kind":"LAUNCH_WORKFLOW","groupId":"cps","workflowId":"01WF",
                 "cronExpression":"0 2 * * *","timeZone":"UTC"}""")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("LAUNCH_WORKFLOW"))
                .andExpect(jsonPath("$.groupId").value("cps"))
                .andExpect(jsonPath("$.workflowId").value("01WF"))
                .andExpect(jsonPath("$.workflowName").value("Nightly regression"))
                .andExpect(jsonPath("$.region").doesNotExist());
    }

    @Test
    @DisplayName("a workflow from ANOTHER group is refused — one team's cadence must not spend another's reservation")
    void refusesCrossGroupWorkflow() throws Exception {
        when(groups.findById("cps")).thenReturn(Optional.of(CPS));
        when(workflows.findById("01WF")).thenReturn(Optional.of(workflow("01WF", "demo")));

        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"nightly","kind":"LAUNCH_WORKFLOW","groupId":"cps","workflowId":"01WF",
                 "cronExpression":"0 2 * * *"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("belongs to group 'demo'")));
        verify(cronJobs, never()).insert(any());
    }

    @Test
    @DisplayName("an unknown workflow and an unknown group each get their own code")
    void refusesUnknownTargets() throws Exception {
        when(groups.findById("cps")).thenReturn(Optional.of(CPS));
        when(workflows.findById(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"n","kind":"LAUNCH_WORKFLOW","groupId":"cps","workflowId":"01GONE","cronExpression":"0 2 * * *"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_WORKFLOW"));

        when(groups.findById("ghost")).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"n","kind":"LAUNCH_WORKFLOW","groupId":"ghost","workflowId":"01WF","cronExpression":"0 2 * * *"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_APPLICATION_GROUP"));
    }

    @Test
    @DisplayName("a scaling schedule needs a cluster the group actually reserved")
    void scalingNeedsAReservation() throws Exception {
        when(groups.findById("cps")).thenReturn(Optional.of(CPS));
        when(capacities.find("cps", "na-east")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"evening","kind":"SCALE_IN","groupId":"cps","region":"na-east","cronExpression":"0 20 * * *"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_CONFIGURED"));

        when(capacities.find("cps", "na-east")).thenReturn(
                Optional.of(new GroupCapacity("cps", "na-east", 4, null, null)));
        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"evening","kind":"SCALE_IN","groupId":"cps","region":"na-east","cronExpression":"0 20 * * *"}""")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SCALE_IN"))
                .andExpect(jsonPath("$.region").value("na-east"))
                .andExpect(jsonPath("$.workflowId").doesNotExist());
    }

    @Test
    @DisplayName("a report carries no group, workflow or region however it is asked for")
    void reportsStayPlatformWide() throws Exception {
        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"readiness","kind":"INFRA_READINESS","groupId":"cps","workflowId":"01WF",
                 "region":"na-east","recipients":"ops@x.com","cronExpression":"0 7 * * *"}""")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipients").value("ops@x.com"))
                // The group/workflow/region the caller sent are dropped, not stored —
                // ORCH_CRON_JOB_KIND_FIELDS_CHK would reject the row otherwise.
                .andExpect(jsonPath("$.groupId").doesNotExist())
                .andExpect(jsonPath("$.workflowId").doesNotExist())
                .andExpect(jsonPath("$.region").doesNotExist());
    }

    @Test
    @DisplayName("kind is required, and a retired kind is named in the error")
    void kindIsRequired() throws Exception {
        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"n","cronExpression":"0 2 * * *"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("kind is required")));

        mvc.perform(post("/api/v1/cronJobs").contentType(MediaType.APPLICATION_JSON).content(body("""
                {"name":"n","kind":"LAUNCH_RUN","groupId":"cps","cronExpression":"0 2 * * *"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("LAUNCH_WORKFLOW")));
    }

    @Test
    @DisplayName("the list filters by group and hydrates every workflow name in one query")
    void listFiltersByGroup() throws Exception {
        CronJob job = new CronJob("01CRON", "nightly", "cps", "01WF", "0 2 * * *", "UTC", true,
                "tester", Instant.now(), null, null, null, null, null,
                CronJobKind.LAUNCH_WORKFLOW, null, null, null, null);
        when(cronJobs.findAll("cps")).thenReturn(List.of(job));
        when(workflows.namesById()).thenReturn(Map.of("01WF", "Nightly regression"));

        mvc.perform(get("/api/v1/cronJobs?groupId=cps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].workflowName").value("Nightly regression"));

        // ONE lookup for the whole page, not one per row.
        verify(workflows).namesById();
        verify(workflows, never()).findById(anyString());
    }

    @Test
    @DisplayName("a schedule whose workflow was deleted still lists, with a null name")
    void listSurvivesADeletedWorkflow() throws Exception {
        CronJob job = new CronJob("01CRON", "orphan", "cps", "01GONE", "0 2 * * *", "UTC", true,
                "tester", Instant.now(), null, null, null, null, null,
                CronJobKind.LAUNCH_WORKFLOW, null, null, null, null);
        when(cronJobs.findAll(null)).thenReturn(List.of(job));

        mvc.perform(get("/api/v1/cronJobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].workflowId").value("01GONE"))
                .andExpect(jsonPath("$.items[0].workflowName").doesNotExist());
    }
}
