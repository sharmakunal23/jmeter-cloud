package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.CapacityExhaustedException;
import com.perf.regionalorchestrator.provision.PodProvisioner;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A refused spin is 409 CAPACITY_EXHAUSTED with the quota's numbers — the hub's run fails with that reason. */
class PodsControllerCapacityTest {

    @Test
    @DisplayName("POST /pods → 409 CAPACITY_EXHAUSTED when the namespace quota cannot admit the worker")
    void capacityExhaustedIs409() throws Exception {
        PodProvisioner provisioner = mock(PodProvisioner.class);
        when(provisioner.createAndStart(any())).thenThrow(new CapacityExhaustedException("namespace x cannot admit another worker — quota headroom: pods=0"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PodsController(provisioner, new RegionalProperties("na-east")))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/v1/pods").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podName\":\"payments-na-east-worker-1\",\"groupId\":\"cps\",\"region\":\"na-east\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXHAUSTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("pods=0")));
    }
}
