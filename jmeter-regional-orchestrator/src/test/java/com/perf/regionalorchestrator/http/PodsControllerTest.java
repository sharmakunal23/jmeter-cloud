package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.PodProvisioner;
import com.perf.regionalorchestrator.provision.PodSpec;
import com.perf.regionalorchestrator.provision.ProvisionResult;
import com.perf.regionalorchestrator.provision.ProvisionedPod;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import com.perf.regionalorchestrator.provision.WorkerState;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PodsController.class, ApiExceptionHandler.class})
@Import(PodsControllerTest.Region.class)
@DisplayName("PodsController — the PodProvisioner over HTTP")
class PodsControllerTest {

    static class Region {
        @Bean RegionalProperties regionalProperties() { return new RegionalProperties("na-east"); }
    }

    @Autowired MockMvc mvc;
    @MockitoBean PodProvisioner provisioner;

    private static final String SPEC = """
            {"podName":"payments-na-east-worker-1","groupId":"cps","region":"%s"}""";

    @Test
    @DisplayName("POST /pods creates and returns the ProvisionResult")
    void create() throws Exception {
        when(provisioner.createAndStart(any(PodSpec.class))).thenReturn(new ProvisionResult(
                "http://payments-na-east-worker-1.workers:8080", "jmeter-local-orchestrator:dev",
                Instant.parse("2026-08-28T10:00:00Z")));

        mvc.perform(post("/api/v1/pods").contentType(MediaType.APPLICATION_JSON).content(SPEC.formatted("na-east")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://payments-na-east-worker-1.workers:8080"))
                .andExpect(jsonPath("$.imageDigest").value("jmeter-local-orchestrator:dev"))
                .andExpect(jsonPath("$.createdAt").value("2026-08-28T10:00:00Z"));
    }

    @Test
    @DisplayName("POST /pods for another region is 400 REGION_MISMATCH and creates nothing")
    void createWrongRegion() throws Exception {
        mvc.perform(post("/api/v1/pods").contentType(MediaType.APPLICATION_JSON).content(SPEC.formatted("na-west")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_MISMATCH"));
    }

    @Test
    @DisplayName("POST /pods with a bad pod name is 400 INVALID_REQUEST")
    void createBadName() throws Exception {
        String bad = SPEC.formatted("na-east").replace("payments-na-east-worker-1", "Bad_Name");
        mvc.perform(post("/api/v1/pods").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /pods?groupId= lists; a missing groupId is 400")
    void list() throws Exception {
        when(provisioner.listFor("cps", null)).thenReturn(List.of(
                new ProvisionedPod("payments-na-east-worker-1", "cps", "na-east",
                        "running", Instant.parse("2026-08-28T10:00:00Z"), "jmeter-local-orchestrator:dev")));

        mvc.perform(get("/api/v1/pods").param("groupId", "cps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].podName").value("payments-na-east-worker-1"))
                .andExpect(jsonPath("$[0].groupId").value("cps"))
                .andExpect(jsonPath("$[0].status").value("running"));

        mvc.perform(get("/api/v1/pods")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /pods/{podName} reports exists + running; a bad name is 400 INVALID_POD_NAME")
    void getState() throws Exception {
        when(provisioner.workerState("payments-na-east-worker-1")).thenReturn(java.util.Optional.of(
                new WorkerState("payments-na-east-worker-1", "APP", "na-east", "Running", true, false, null, null, 0, null)));

        mvc.perform(get("/api/v1/pods/payments-na-east-worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.dead").value(false));

        when(provisioner.workerState("oom-1")).thenReturn(java.util.Optional.of(
                new WorkerState("oom-1", "APP", "na-east", "Failed", false, true, "OOMKilled", 137, 0, null)));
        mvc.perform(get("/api/v1/pods/oom-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.dead").value(true))
                .andExpect(jsonPath("$.reason").value("OOMKilled"))
                .andExpect(jsonPath("$.exitCode").value(137));

        mvc.perform(get("/api/v1/pods/no-such-pod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.running").value(false));

        mvc.perform(get("/api/v1/pods/Bad_Name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POD_NAME"));
    }

    @Test
    @DisplayName("DELETE, stop, start, restart map to the provisioner verbs and answer 204")
    void verbs() throws Exception {
        mvc.perform(delete("/api/v1/pods/w-1")).andExpect(status().isNoContent());
        verify(provisioner).stopAndRemove("w-1");
        mvc.perform(post("/api/v1/pods/w-1/stop")).andExpect(status().isNoContent());
        verify(provisioner).stop("w-1");
        mvc.perform(post("/api/v1/pods/w-1/start")).andExpect(status().isNoContent());
        verify(provisioner).start("w-1");
        mvc.perform(post("/api/v1/pods/w-1/restart")).andExpect(status().isNoContent());
        verify(provisioner).restart("w-1");
    }

    @Test
    @DisplayName("restart of a missing pod is 404 POD_NOT_FOUND; a cluster API failure is 502 CLUSTER_API_ERROR")
    void errors() throws Exception {
        doThrow(new IllegalStateException("Pod w-9 does not exist; cannot restart")).when(provisioner).restart("w-9");
        mvc.perform(post("/api/v1/pods/w-9/restart"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POD_NOT_FOUND"));

        doThrow(new KubernetesClientException("forbidden", 403, null)).when(provisioner).stopAndRemove(eq("w-8"));
        mvc.perform(delete("/api/v1/pods/w-8"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CLUSTER_API_ERROR"));
    }

    @Test
    @DisplayName("GET /workers lists every managed pod's kubelet liveness")
    void workers() throws Exception {
        when(provisioner.listWorkers()).thenReturn(List.of(
                new WorkerState("w-1", "APP", "na-east", "Running", true, false, null, null, 0, null),
                new WorkerState("w-2", "APP", "na-east", "Pending", false, true, "Unschedulable", null, 0, "0/1 nodes are available: insufficient memory")));
        mvc.perform(get("/api/v1/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].podName").value("w-1"))
                .andExpect(jsonPath("$[0].ready").value(true))
                .andExpect(jsonPath("$[1].dead").value(true))
                .andExpect(jsonPath("$[1].reason").value("Unschedulable"));
    }

    @Test
    @DisplayName("GET /image returns the configured image reference")
    void image() throws Exception {
        when(provisioner.currentImageDigest()).thenReturn("jmeter-local-orchestrator:dev");
        mvc.perform(get("/api/v1/image"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageDigest").value("jmeter-local-orchestrator:dev"));
    }
}
