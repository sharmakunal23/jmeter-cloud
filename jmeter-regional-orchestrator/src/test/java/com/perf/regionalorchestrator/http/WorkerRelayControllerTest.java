package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.PodProvisioner;
import com.perf.regionalorchestrator.relay.RelayRequest;
import com.perf.regionalorchestrator.relay.RelayResponse;
import com.perf.regionalorchestrator.relay.WorkerRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkerRelayController.class)
@DisplayName("WorkerRelayController — /api/v1/workers/{podName}/** → the worker")
class WorkerRelayControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean WorkerRelay relay;
    @MockitoBean PodProvisioner provisioner;

    @Test
    @DisplayName("method, sub-path, query, body, content type and X-Actor reach the relay; its status, body and content type come back")
    void passesEverythingThrough() throws Exception {
        when(relay.relay(any())).thenReturn(new RelayResponse(202,
                "{\"state\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8), "application/json"));

        mvc.perform(post("/api/v1/workers/payments-na-east-worker-1/api/v1/test?dryRun=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "alice")
                        .header("X-Run-Id", "R1")
                        .content("{\"runId\":\"R1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(content().string("{\"state\":\"RUNNING\"}"));

        ArgumentCaptor<RelayRequest> captor = ArgumentCaptor.forClass(RelayRequest.class);
        verify(relay).relay(captor.capture());
        RelayRequest req = captor.getValue();
        assertThat(req.podName()).isEqualTo("payments-na-east-worker-1");
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.subPath()).isEqualTo("api/v1/test");
        assertThat(req.query()).isEqualTo("dryRun=true");
        assertThat(new String(req.body(), StandardCharsets.UTF_8)).isEqualTo("{\"runId\":\"R1\"}");
        assertThat(req.contentType()).startsWith("application/json");
        assertThat(req.actor()).isEqualTo("alice");
        assertThat(req.runId()).isEqualTo("R1");
    }

    @Test
    @DisplayName("a GET with no body relays a null body; nested sub-paths keep every segment")
    void getNested() throws Exception {
        when(relay.relay(any())).thenReturn(new RelayResponse(200, new byte[0], "text/plain"));

        mvc.perform(get("/api/v1/workers/w-1/api/v1/test/status"))
                .andExpect(status().isOk());

        ArgumentCaptor<RelayRequest> captor = ArgumentCaptor.forClass(RelayRequest.class);
        verify(relay).relay(captor.capture());
        assertThat(captor.getValue().subPath()).isEqualTo("api/v1/test/status");
        assertThat(captor.getValue().body()).isNull();
    }

    @Test
    @DisplayName("POST /workers/status polls every named worker and reports non-answers with their relay status")
    void batchedStatus() throws Exception {
        when(relay.relay(any())).thenAnswer(inv -> {
            RelayRequest req = inv.getArgument(0);
            return req.podName().equals("w-dead")
                    ? new RelayResponse(502, "{\"code\":\"WORKER_UNREACHABLE\"}".getBytes(StandardCharsets.UTF_8), "application/json")
                    : new RelayResponse(200, ("{\"state\":\"RUNNING\",\"pod\":\"" + req.podName() + "\"}").getBytes(StandardCharsets.UTF_8), "application/json");
        });

        mvc.perform(post("/api/v1/workers/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podNames\":[\"w-1\",\"w-dead\",\"w-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].podName").value("w-1"))
                .andExpect(jsonPath("$[0].status").value(200))
                .andExpect(jsonPath("$[0].body.state").value("RUNNING"))
                .andExpect(jsonPath("$[1].podName").value("w-dead"))
                .andExpect(jsonPath("$[1].status").value(502))
                .andExpect(jsonPath("$[2].body.pod").value("w-2"));
    }

    @Test
    @DisplayName("api/v1/logs falls back to the kubelet's container log when the worker no longer answers")
    void logsFallBackToKubelet() throws Exception {
        when(relay.relay(any())).thenReturn(new RelayResponse(502,
                "{\"code\":\"WORKER_UNREACHABLE\"}".getBytes(StandardCharsets.UTF_8), "application/json"));
        when(provisioner.podLog("w-dead", 50)).thenReturn(java.util.Optional.of("… JMeter summary = 21386 in 00:03:47\n"));

        mvc.perform(get("/api/v1/workers/w-dead/api/v1/logs?tail=50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Log-Source", "kubernetes"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("JMeter summary")));

        when(provisioner.podLog("w-gone", 200)).thenReturn(java.util.Optional.empty());
        mvc.perform(get("/api/v1/workers/w-gone/api/v1/logs"))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("subPath strips the prefix and a trailing slash")
    void subPath() {
        assertThat(WorkerRelayController.subPath("/api/v1/workers/w-1/actuator/health", "w-1")).isEqualTo("actuator/health");
        assertThat(WorkerRelayController.subPath("/api/v1/workers/w-1/api/v1/test/", "w-1")).isEqualTo("api/v1/test");
        assertThat(WorkerRelayController.subPath("/api/v1/workers/w-1/", "w-1")).isEmpty();
    }
}
