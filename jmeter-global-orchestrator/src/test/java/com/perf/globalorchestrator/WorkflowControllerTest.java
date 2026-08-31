package com.perf.globalorchestrator;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.Workflow;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.http.WorkflowController;
import com.perf.globalorchestrator.http.WorkflowExceptionHandler;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowRepository;
import com.perf.globalorchestrator.service.WorkflowService;
import com.perf.globalorchestrator.service.WorkflowValidation;
import com.perf.globalorchestrator.service.WorkflowValidation.RegionDemand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The workflow API's HTTP contract: the landing list, the save lock, and the two refusals that matter. */
@DisplayName("WorkflowController — HTTP contract + error mapping")
class WorkflowControllerTest {

    private WorkflowService service;
    private WorkflowRepository workflows;
    private WorkflowExecutionRepository executions;
    private ApplicationGroupRepository groups;
    private MockMvc mvc;

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(1,
            List.of(new DelayNode("d1", "Settle", new NodePosition(0, 0), JoinPolicy.ALL, 30)), List.of());
    private static final Workflow WF = new Workflow("wf1", "cps", "Nightly", "every night", GRAPH,
            true, 3, "alice", NOW, "alice", NOW, null);

    @BeforeEach
    void setUp() {
        service = mock(WorkflowService.class);
        workflows = mock(WorkflowRepository.class);
        executions = mock(WorkflowExecutionRepository.class);
        groups = mock(ApplicationGroupRepository.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new WorkflowController(service, workflows, executions, groups))
                .setControllerAdvice(new WorkflowExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /groups lists every group with its workflow count and owning team")
    void listGroups() throws Exception {
        ApplicationGroup cps = new ApplicationGroup("cps", "Servicing MQ", "the MQ apps", null, null, 7,
                null, null, null, false, "Payments Platform",
                List.of("team@example.com"), List.of(), List.of(), NOW, null, null);
        when(groups.findAll()).thenReturn(List.of(cps));
        when(workflows.countsByGroup()).thenReturn(Map.of("cps", 2));

        mvc.perform(get("/api/v1/workflows/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value("cps"))
                .andExpect(jsonPath("$[0].teamName").value("Payments Platform"))
                .andExpect(jsonPath("$[0].workflowCount").value(2))
                .andExpect(jsonPath("$[0].notifyTo[0]").value("team@example.com"));
    }

    @Test
    @DisplayName("a group with no workflows still lists, at zero")
    void listGroupsWithoutWorkflows() throws Exception {
        when(groups.findAll()).thenReturn(List.of(
                new ApplicationGroup("demo", "Demo", null, NOW, null)));
        when(workflows.countsByGroup()).thenReturn(Map.of());

        mvc.perform(get("/api/v1/workflows/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowCount").value(0));
    }

    @Test
    @DisplayName("POST /validate returns the peak-workers picture beside the group's reservation")
    void validateReturnsCapacity() throws Exception {
        when(service.validate(eq("cps"), any())).thenReturn(WorkflowValidation.ok(
                List.of("cluster 'na-east': this workflow can want 9 workers at once"),
                List.of(new RegionDemand("na-east", 9, List.of("Test A", "Test B"), 8, false))));

        mvc.perform(post("/api/v1/workflows/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"cps\",\"graph\":{\"v\":1,\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.capacity[0].peakWorkers").value(9))
                .andExpect(jsonPath("$.capacity[0].reserved").value(8))
                .andExpect(jsonPath("$.capacity[0].fits").value(false))
                .andExpect(jsonPath("$.capacity[0].tasks[0]").value("Test A"));
    }

    @Test
    @DisplayName("an invalid graph is 400 WORKFLOW_INVALID and carries every violation at once")
    void invalidGraphIsRejectedWithAllViolations() throws Exception {
        WorkflowValidation bad = WorkflowValidation.invalid(
                List.of("task 'Check': pick an application to check", "the tasks form a loop"));
        when(service.create(anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenThrow(new WorkflowService.WorkflowInvalidException(bad));

        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"cps\",\"name\":\"Broken\","
                                + "\"graph\":{\"v\":1,\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKFLOW_INVALID"))
                .andExpect(jsonPath("$.validation.errors.length()").value(2));
    }

    @Test
    @DisplayName("PUT without a revision is refused — a blind save is not a save")
    void updateRequiresARevision() throws Exception {
        mvc.perform(put("/api/v1/workflows/wf1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nightly\",\"graph\":{\"v\":1,\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a stale revision is 409 WORKFLOW_REVISION_CONFLICT, naming both revisions")
    void staleRevisionConflicts() throws Exception {
        when(service.update(eq("wf1"), eq(3), anyString(), any(), any(), anyBoolean(), any()))
                .thenThrow(new WorkflowService.WorkflowRevisionConflictException("wf1", 3, 5));

        mvc.perform(put("/api/v1/workflows/wf1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nightly\",\"revision\":3,"
                                + "\"graph\":{\"v\":1,\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("current is 5")));
    }

    @Test
    @DisplayName("launching over the group's reservation is 409 and names the clusters and tasks")
    void launchRefusedOnCapacity() throws Exception {
        when(service.launch(eq("wf1"), any())).thenThrow(new WorkflowService.WorkflowCapacityExceededException(
                List.of(new RegionDemand("na-east", 12, List.of("Test A", "Test B", "Test C"), 8, false))));

        mvc.perform(post("/api/v1/workflows/wf1/executions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.clusters[0].region").value("na-east"))
                .andExpect(jsonPath("$.clusters[0].peakWorkers").value(12))
                .andExpect(jsonPath("$.clusters[0].tasks.length()").value(3));
    }

    @Test
    @DisplayName("a second concurrent run of one workflow is refused, not silently cleared against the same reservation")
    void secondConcurrentLaunchRefused() throws Exception {
        when(service.launch(eq("wf1"), any()))
                .thenThrow(new WorkflowService.WorkflowAlreadyRunningException("wf1", 1));

        mvc.perform(post("/api/v1/workflows/wf1/executions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("cancel or wait")));
    }

    @Test
    @DisplayName("a launch is 202 with the opened execution")
    void launchAccepted() throws Exception {
        when(service.launch(eq("wf1"), any())).thenReturn(new WorkflowExecution("ex1", "wf1", "cps",
                "Nightly", GRAPH, ExecutionState.RUNNING, null, "alice", NOW, null, NOW, List.of()));

        mvc.perform(post("/api/v1/workflows/wf1/executions").header("X-Actor", "alice"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("ex1"))
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    @DisplayName("editing a workflow while it is running is refused — the canvas must not drift from the run")
    void editRefusedWhileRunning() throws Exception {
        when(service.update(eq("wf1"), eq(3), anyString(), any(), any(), anyBoolean(), any()))
                .thenThrow(new WorkflowService.WorkflowBusyException("wf1", 1));

        mvc.perform(put("/api/v1/workflows/wf1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nightly\",\"revision\":3,"
                                + "\"graph\":{\"v\":1,\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_RUNNING"))
                .andExpect(jsonPath("$.runningExecutions").value(1));
    }

    @Test
    @DisplayName("GET carries the last execution, which is how the UI knows to disable Run and Edit")
    void getCarriesTheLastExecution() throws Exception {
        when(service.requireWorkflow("wf1")).thenReturn(WF);
        when(executions.findByWorkflow("wf1", 1)).thenReturn(List.of(
                new WorkflowExecution("ex9", "wf1", "cps", "Nightly", GRAPH,
                        ExecutionState.RUNNING, null, "alice", NOW, null, NOW, List.of())));

        mvc.perform(get("/api/v1/workflows/wf1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastExecution.executionId").value("ex9"))
                .andExpect(jsonPath("$.lastExecution.state").value("RUNNING"));
    }

    @Test
    @DisplayName("deleting a workflow with a running execution is 409, not a silent orphan")
    void deleteRefusedWhileRunning() throws Exception {
        doThrow(new WorkflowService.WorkflowBusyException("wf1", 1)).when(service).delete("wf1");

        mvc.perform(delete("/api/v1/workflows/wf1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_RUNNING"))
                .andExpect(jsonPath("$.runningExecutions").value(1));
    }

    @Test
    @DisplayName("an unknown workflow is 404")
    void unknownWorkflowIs404() throws Exception {
        when(service.requireWorkflow("nope"))
                .thenThrow(new WorkflowService.WorkflowNotFoundException("nope"));
        mvc.perform(get("/api/v1/workflows/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("history is bounded even when a caller asks for more")
    void historyIsBounded() throws Exception {
        when(service.requireWorkflow("wf1")).thenReturn(WF);
        when(executions.findByWorkflow(eq("wf1"), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/workflows/wf1/executions").param("limit", "99999"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(executions).findByWorkflow("wf1", 200);
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
