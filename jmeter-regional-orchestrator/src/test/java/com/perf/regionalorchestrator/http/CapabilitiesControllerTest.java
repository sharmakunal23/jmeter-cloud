package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.ProvisionerProperties;
import com.perf.regionalorchestrator.provision.ProvisioningCheck;
import com.perf.regionalorchestrator.provision.ProvisioningCheckService;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CapabilitiesController.class)
@Import(CapabilitiesControllerTest.Beans.class)
class CapabilitiesControllerTest {

    static class Beans {
        @Bean RegionalProperties regionalProperties() { return new RegionalProperties("na-west"); }
        @Bean com.perf.regionalorchestrator.provision.PodProvisioner podProvisioner() {
            com.perf.regionalorchestrator.provision.PodProvisioner p = org.mockito.Mockito.mock(com.perf.regionalorchestrator.provision.PodProvisioner.class);
            org.mockito.Mockito.when(p.capacity()).thenReturn(new com.perf.regionalorchestrator.provision.NamespaceCapacity(3, 18432L, null, null, 3));
            return p;
        }
        @Bean ProvisioningCheckService provisioningCheckService() {
            ProvisioningCheckService s = org.mockito.Mockito.mock(ProvisioningCheckService.class);
            org.mockito.Mockito.when(s.run()).thenReturn(List.of(
                    new ProvisioningCheck("imageConfigured", true, "jmeter-local-orchestrator:dev"),
                    new ProvisioningCheck("rbacPods", false, "ServiceAccount lacks pods verbs: create in namespace jmeter-cloud")));
            return s;
        }
        @Bean ProvisionerProperties provisionerProperties() {
            return new ProvisionerProperties("jmeter-cloud", "workers", "jmeter-local-orchestrator:dev", 8080,
                    "http://metrics-consumer:8083/api/v1/ingest",
                    "http://document-service:8084", 6144, "500m", 10, null, null);
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void reportsRegionAndWorkerShape() throws Exception {
        mvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("na-west"))
                .andExpect(jsonPath("$.namespace").value("jmeter-cloud"))
                .andExpect(jsonPath("$.headlessService").value("workers"))
                .andExpect(jsonPath("$.image").value("jmeter-local-orchestrator:dev"))
                .andExpect(jsonPath("$.localOrchestratorPort").value(8080))
                .andExpect(jsonPath("$.version").value("dev"))
                .andExpect(jsonPath("$.capacity.workersFree").value(3))
                .andExpect(jsonPath("$.capacity.podsFree").value(3))
                .andExpect(jsonPath("$.capacity.cpuFreeMillis").doesNotExist())
                .andExpect(jsonPath("$.workerMemoryMb").value(6144))
                .andExpect(jsonPath("$.workerEphemeralStorage").doesNotExist());   // local shape: LimitRange default
    }

    @Test
    void provisioningCheckReportsEveryCheckAndRollsUpOk() throws Exception {
        mvc.perform(get("/api/v1/provisioningCheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("na-west"))
                .andExpect(jsonPath("$.image").value("jmeter-local-orchestrator:dev"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.checks[0].name").value("imageConfigured"))
                .andExpect(jsonPath("$.checks[0].ok").value(true))
                .andExpect(jsonPath("$.checks[1].name").value("rbacPods"))
                .andExpect(jsonPath("$.checks[1].ok").value(false))
                .andExpect(jsonPath("$.checks[1].detail").value(
                        "ServiceAccount lacks pods verbs: create in namespace jmeter-cloud"));
    }
}
