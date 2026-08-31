package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.provision.PodNameAllocator;
import com.perf.globalorchestrator.provision.PodProvisioner;
import com.perf.globalorchestrator.provision.PodReconciler;
import com.perf.globalorchestrator.provision.PodRecycler;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("AdminController — the escape hatches never destroy a worker the platform doesn't own")
class AdminControllerTest {

    private PodRepository pods;
    private PodProvisioner provisioner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        pods = mock(PodRepository.class);
        provisioner = mock(PodProvisioner.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminController(
                mock(PodReconciler.class), mock(PodRecycler.class), provisioner,
                mock(PodNameAllocator.class), mock(ApplicationGroupRepository.class), pods)).build();
    }

    private static Pod pod(String id, PodSource source) {
        return new Pod(id, "na-east", "http://" + id + ":8080", PodState.IDLE, Instant.now(), Instant.now(),
                "cps", 0, null, Instant.now(), source);
    }

    @Test
    @DisplayName("tearing down a DECLARED worker is refused — the regional would delete a Pod the operator owns")
    void tearDownRefusesDeclaredWorkers() throws Exception {
        when(pods.findByPodId("declared-1")).thenReturn(Optional.of(pod("declared-1", PodSource.STATIC)));

        mvc.perform(delete("/api/v1/admin/pods/declared-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POD_SOURCE_STATIC"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "/api/v1/applicationGroups/cps/capacity/na-east/pods/declared-1")));

        verify(provisioner, never()).stopAndRemove(anyString(), anyString());
    }

    @Test
    @DisplayName("tearing down a SPUN worker still goes through to its region")
    void tearDownStopsSpunWorkers() throws Exception {
        when(pods.findByPodId("spun-1")).thenReturn(Optional.of(pod("spun-1", PodSource.DYNAMIC)));

        mvc.perform(delete("/api/v1/admin/pods/spun-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped").value(true));

        verify(provisioner).stopAndRemove("na-east", "spun-1");
    }

    @Test
    @DisplayName("an unregistered pod is 404, never a blind delete against the cluster")
    void tearDownUnknownPod() throws Exception {
        when(pods.findByPodId("ghost")).thenReturn(Optional.empty());

        mvc.perform(delete("/api/v1/admin/pods/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POD_NOT_REGISTERED"));

        verify(provisioner, never()).stopAndRemove(anyString(), anyString());
    }
}
