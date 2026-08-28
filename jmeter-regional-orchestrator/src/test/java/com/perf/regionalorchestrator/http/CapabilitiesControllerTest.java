package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.ProvisionerProperties;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CapabilitiesController.class)
@Import(CapabilitiesControllerTest.Beans.class)
class CapabilitiesControllerTest {

    static class Beans {
        @Bean RegionalProperties regionalProperties() { return new RegionalProperties("na-west"); }
        @Bean ProvisionerProperties provisionerProperties() {
            return new ProvisionerProperties("jmeter-cloud", "workers", "jmeter-local-orchestrator:dev", 8080,
                    "http://metrics-consumer:8083/api/v1/ingest",
                    "http://document-service:8084", 6144, "500m", 10, null);
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
                .andExpect(jsonPath("$.version").value("dev"));
    }
}
