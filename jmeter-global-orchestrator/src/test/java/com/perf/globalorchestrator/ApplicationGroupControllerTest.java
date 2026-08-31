package com.perf.globalorchestrator;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.http.ApplicationGroupController;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The application-group registry's HTTP contract and error mapping, against a mocked repository. */
@DisplayName("ApplicationGroupController — HTTP contract + error mapping")
class ApplicationGroupControllerTest {

    private ApplicationGroupRepository repo;
    private GroupCapacityRepository capacity;
    private MockMvc mvc;

    private static final ApplicationGroup CPS =
            new ApplicationGroup("cps", "Servicing MQ", null, Instant.parse("2026-08-29T00:00:00Z"), null);

    @BeforeEach
    void setUp() {
        repo = mock(ApplicationGroupRepository.class);
        capacity = mock(GroupCapacityRepository.class);
        ProvisioningProperties provisioning = mock(ProvisioningProperties.class);
        when(provisioning.regions()).thenReturn(List.of("na-east", "na-west"));
        mvc = MockMvcBuilders.standaloneSetup(new ApplicationGroupController(repo, capacity, provisioning)).build();
    }

    @Test
    @DisplayName("GET list hydrates the application count per group")
    void list_hydratesCounts() throws Exception {
        when(repo.findAll()).thenReturn(List.of(CPS));
        when(repo.applicationCounts()).thenReturn(Map.of("cps", 4));
        mvc.perform(get("/api/v1/applicationGroups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value("cps"))
                .andExpect(jsonPath("$[0].name").value("Servicing MQ"))
                .andExpect(jsonPath("$[0].applicationCount").value(4));
    }

    @Test
    @DisplayName("POST creates with a trimmed name and returns 201 with applicationCount 0")
    void create_happyPath() throws Exception {
        when(repo.findById("cps")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"cps\",\"name\":\"  Servicing MQ \",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value("cps"))
                .andExpect(jsonPath("$.name").value("Servicing MQ"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.applicationCount").value(0));
    }

    @Test
    @DisplayName("POST rejects an id that is not an identifier stem — upper-case, hyphen, digit-first, > 30 (400 INVALID_REQUEST)")
    void create_rejectsBadId() throws Exception {
        for (String bad : List.of("CPS", "cps-pci", "1cps", "a".repeat(31))) {
            mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupId\":\"" + bad + "\",\"name\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        // Underscore and digits after the first letter are fine.
        when(repo.findById("cps_pci2")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"cps_pci2\",\"name\":\"ok\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"cps\",\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST on an existing id is 409 APPLICATION_GROUP_ID_TAKEN; a duplicate name is 409 APPLICATION_GROUP_NAME_TAKEN")
    void create_conflicts() throws Exception {
        when(repo.findById("cps")).thenReturn(Optional.of(CPS));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"cps\",\"name\":\"Other\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_GROUP_ID_TAKEN"));

        when(repo.findById("mq")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenThrow(new DuplicateKeyException("applicationGroup_name_uq"));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"mq\",\"name\":\"Servicing MQ\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_GROUP_NAME_TAKEN"));
    }

    @Test
    @DisplayName("GET / PUT an unknown group is 404 APPLICATION_GROUP_NOT_FOUND")
    void unknown_isNotFound() throws Exception {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/applicationGroups/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_GROUP_NOT_FOUND"));
        mvc.perform(put("/api/v1/applicationGroups/nope").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT updates name + description and echoes the count")
    void update_happyPath() throws Exception {
        when(repo.findById("cps")).thenReturn(Optional.of(CPS));
        when(repo.update("cps", "Servicing MQ (Card)", "the MQ apps", null, null, 7, RecyclePolicy.REUSE, null, null, false))
                .thenReturn(new ApplicationGroup("cps", "Servicing MQ (Card)", "the MQ apps", CPS.createdAt(), null));
        when(repo.countVisibleApplications("cps")).thenReturn(2);
        mvc.perform(put("/api/v1/applicationGroups/cps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Servicing MQ (Card)\",\"description\":\"the MQ apps\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Servicing MQ (Card)"))
                .andExpect(jsonPath("$.applicationCount").value(2));
    }

    @Test
    @DisplayName("DELETE is 409 APPLICATION_GROUP_HAS_APPLICATIONS while applications remain, else 204 (idempotent)")
    void delete_guardedByApplications() throws Exception {
        when(repo.countApplications("cps")).thenReturn(3);
        mvc.perform(delete("/api/v1/applicationGroups/cps"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_GROUP_HAS_APPLICATIONS"));
        verify(repo, never()).delete("cps");

        when(repo.countApplications("empty")).thenReturn(0);
        when(repo.delete("empty")).thenReturn(false);   // never existed
        mvc.perform(delete("/api/v1/applicationGroups/empty")).andExpect(status().isNoContent());

        // The FK wins a race between the count and the delete.
        when(repo.countApplications("raced")).thenReturn(0, 1);
        when(repo.delete("raced")).thenThrow(new DataIntegrityViolationException("ORA-02292"));
        mvc.perform(delete("/api/v1/applicationGroups/raced"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_GROUP_HAS_APPLICATIONS"));
    }

    @Test
    @DisplayName("POST/PUT carry the two Grafana URLs and hotDays; a relative or non-http URL and a bad hotDays are 400")
    void grafanaFields() throws Exception {
        when(repo.findById("mq")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"mq\",\"name\":\"MQ\",\"grafanaLiveUrl\":\" https://g.example.com/d/mqProductMetrics/mq?orgId=1 \","
                                + "\"grafanaHistoryUrl\":\"\",\"hotDays\":14}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grafanaLiveUrl").value("https://g.example.com/d/mqProductMetrics/mq?orgId=1"))
                .andExpect(jsonPath("$.grafanaHistoryUrl").doesNotExist())
                .andExpect(jsonPath("$.hotDays").value(14));
        // Omitted hotDays defaults to 7.
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"mq\",\"name\":\"MQ\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hotDays").value(7));
        for (String bad : List.of("/d/relative", "ftp://x/y", "not a url", "https://"))
            mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupId\":\"mq\",\"name\":\"MQ\",\"grafanaLiveUrl\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"mq\",\"name\":\"MQ\",\"hotDays\":0}"))
                .andExpect(status().isBadRequest());
        // PUT replaces wholesale.
        when(repo.findById("cps")).thenReturn(Optional.of(CPS));
        when(repo.update(eq("cps"), eq("Servicing MQ"), any(), eq("https://g.example.com/d/cps"), isNull(), eq(30),
                eq(RecyclePolicy.REUSE), isNull(), isNull(), eq(false)))
                .thenReturn(new ApplicationGroup("cps", "Servicing MQ", null, "https://g.example.com/d/cps", null, 30, CPS.createdAt(), null));
        mvc.perform(put("/api/v1/applicationGroups/cps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Servicing MQ\",\"grafanaLiveUrl\":\"https://g.example.com/d/cps\",\"hotDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaLiveUrl").value("https://g.example.com/d/cps"))
                .andExpect(jsonPath("$.hotDays").value(30));
    }

    @Test
    @DisplayName("the pool's policy lives on the group: POST validates it, seeds capacity at 0 per region; PUT replaces it; alwaysOn omitted is preserved")
    void policyOnTheGroup() throws Exception {
        when(repo.findById("mq")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"mq\",\"name\":\"MQ\",\"recyclePolicy\":\"MAX_RUNS\",\"maxRunsPerPod\":3,\"alwaysOn\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recyclePolicy").value("MAX_RUNS"))
                .andExpect(jsonPath("$.maxRunsPerPod").value(3))
                .andExpect(jsonPath("$.alwaysOn").value(true));
        verify(capacity).upsert("mq", "na-east", 0);
        verify(capacity).upsert("mq", "na-west", 0);
        mvc.perform(post("/api/v1/applicationGroups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"mq2\",\"name\":\"MQ2\",\"recyclePolicy\":\"MAX_AGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        ApplicationGroup on = new ApplicationGroup("cps", "Servicing MQ", null, null, null, 7,
                RecyclePolicy.REUSE, null, null, true, CPS.createdAt(), null, null);
        when(repo.findById("cps")).thenReturn(Optional.of(on));
        when(repo.update(eq("cps"), eq("Servicing MQ"), any(), isNull(), isNull(), eq(7),
                eq(RecyclePolicy.EVERY_RUN), isNull(), isNull(), eq(true)))
                .thenReturn(new ApplicationGroup("cps", "Servicing MQ", null, null, null, 7,
                        RecyclePolicy.EVERY_RUN, null, null, true, CPS.createdAt(), null, null));
        mvc.perform(put("/api/v1/applicationGroups/cps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Servicing MQ\",\"recyclePolicy\":\"EVERY_RUN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recyclePolicy").value("EVERY_RUN"))
                .andExpect(jsonPath("$.alwaysOn").value(true));
    }

    @Test
    @DisplayName("DELETE is refused while the group still owns workers or capacity rows")
    void delete_refusedWithWorkers() throws Exception {
        when(repo.countApplications("cps")).thenReturn(0);
        when(repo.countPods("cps")).thenReturn(2);
        when(capacity.countByGroupId("cps")).thenReturn(1);
        mvc.perform(delete("/api/v1/applicationGroups/cps"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_GROUP_HAS_WORKERS"));
        verify(repo, never()).delete(any());
    }
}
