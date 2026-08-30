package com.perf.globalorchestrator;

import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.http.ApplicationController;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.service.ApplicationPurgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The application registry's group fields: validation, the upper-cased default, and PUT's wholesale replace. */
@DisplayName("ApplicationController — metricsGroupId / metricsApplication")
class ApplicationControllerTest {

    private static final String ID = "01J0CHECK" + "A".repeat(17);   // 26 chars, no I/L/O/U

    private ApplicationRepository repo;
    private ApplicationGroupRepository groups;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(ApplicationRepository.class);
        groups = mock(ApplicationGroupRepository.class);
        ProvisioningProperties provisioning = mock(ProvisioningProperties.class);
        when(provisioning.regions()).thenReturn(List.of("na-east"));
        when(groups.findById("cps")).thenReturn(Optional.of(
                new ApplicationGroup("cps", "Servicing MQ", null, Instant.now(), null)));
        when(groups.findById("nope")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        ApplicationController controller = new ApplicationController(
                repo, groups, mock(RunRepository.class),
                mock(ApplicationPurgeService.class), provisioning);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST with a group and no metricsApplication stores the upper-cased name")
    void create_defaultsMetricsApplication() throws Exception {
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cps-pci\",\"metricsGroupId\":\" cps \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metricsGroupId").value("cps"))
                .andExpect(jsonPath("$.metricsApplication").value("CPS-PCI"));
        ArgumentCaptor<Application> stored = ArgumentCaptor.forClass(Application.class);
        verify(repo).insert(stored.capture());
        assertThat(stored.getValue().metricsGroupId()).isEqualTo("cps");
        assertThat(stored.getValue().metricsApplication()).isEqualTo("CPS-PCI");
    }

    @Test
    @DisplayName("POST with an unknown group or a malformed metricsApplication is 400 INVALID_REQUEST")
    void create_rejectsBadGroupFields() throws Exception {
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"checkout\",\"metricsGroupId\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("unknown application group: nope"));
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"checkout\",\"metricsGroupId\":\"cps\",\"metricsApplication\":\"has space\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("PUT replaces both fields wholesale — and a body without a group is 400: every application belongs to one")
    void update_replacesWholesale() throws Exception {
        Application existing = new Application(ID, "cps-pci", null, null, List.of(), Instant.now(),
                null, null, null, "cps", "CPS-PCI");
        when(repo.findById(ID)).thenReturn(Optional.of(existing));
        when(repo.update(eq(ID), eq("cps-pci"), isNull(), isNull(), anyList(), eq("cps"), eq("CPP")))
                .thenReturn(new Application(ID, "cps-pci", null, null, List.of(), existing.createdAt(),
                        null, null, null, "cps", "CPP"));
        mvc.perform(put("/api/v1/applications/" + ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cps-pci\",\"metricsGroupId\":\"cps\",\"metricsApplication\":\"CPP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsApplication").value("CPP"));

        mvc.perform(put("/api/v1/applications/" + ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cps-pci\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("metricsGroupId is required")));
        verify(repo, times(1)).update(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST without a group is 400 — capacity and the recycle policy live on the group, so the fields are ignored here")
    void create_requiresGroup() throws Exception {
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"checkout\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("metricsGroupId is required")));
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"checkout\",\"metricsGroupId\":\"cps\",\"recyclePolicy\":\"MAX_RUNS\",\"maxRunsPerPod\":3,\"capacity\":[{\"region\":\"na-east\",\"maxAvailable\":9}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recyclePolicy").doesNotExist())
                .andExpect(jsonPath("$.capacity").doesNotExist())
                .andExpect(jsonPath("$.metricsGroupId").value("cps"));
    }

    @Test
    @DisplayName("the per-app Grafana override is gone: the fields are ignored on POST and never echoed — dashboards live on the group")
    void noGrafanaOverride() throws Exception {
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cps-pci\",\"metricsGroupId\":\"cps\",\"grafanaLiveUrl\":\"https://g.example.com/d/pci\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grafanaLiveUrl").doesNotExist())
                .andExpect(jsonPath("$.grafanaHistoryUrl").doesNotExist());
    }
}
