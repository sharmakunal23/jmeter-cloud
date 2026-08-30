package com.perf.metricsconsumer.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.health.ConsumerHeartbeat;
import com.perf.metricsconsumer.jdbc.GroupRegistry;
import com.perf.metricsconsumer.jdbc.GroupTarget;
import com.perf.metricsconsumer.jdbc.UnknownGroupException;
import com.perf.metricsconsumer.jdbc.WorkerMetricWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract and error mapping of {@code POST /api/v1/ingest} — every outcome is an IngestResponse. */
@DisplayName("IngestController — status codes + IngestResponse shape")
class IngestControllerTest {

    private static final GroupTarget CPS = new GroupTarget("cps", "CPS", "CPS_METRICS", "CPS_CLASSIFY_LABEL");

    private GroupRegistry groups;
    private WorkerMetricWriter writer;
    private MockMvc mvc;
    private String golden;

    @BeforeEach
    void setUp() throws Exception {
        groups = mock(GroupRegistry.class);
        writer = mock(WorkerMetricWriter.class);
        when(groups.resolve("cps")).thenReturn(CPS);
        when(groups.resolve(any())).thenAnswer(inv -> {
            String g = inv.getArgument(0);
            if ("cps".equals(g)) return CPS;
            throw new UnknownGroupException(g);
        });
        IngestController controller = new IngestController(groups, writer, new ConsumerHeartbeat(), new ObjectMapper(), 4096);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        golden = Files.readString(Path.of("src/test/resources/goldenWorkerMetricBatch.json"));
    }

    @Test
    void a_routed_envelope_is_202_with_the_rows_the_writer_landed() throws Exception {
        when(writer.write(eq(CPS), any())).thenReturn(1);
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON).content(golden))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowsInserted").value(1))
                .andExpect(jsonPath("$.code").value("ACCEPTED"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void a_replay_is_202_with_zero_rows() throws Exception {
        when(writer.write(eq(CPS), any())).thenReturn(0);
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON).content(golden))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowsInserted").value(0))
                .andExpect(jsonPath("$.code").value("ACCEPTED"));
    }

    @Test
    void a_missing_or_unknown_group_is_400_UNKNOWN_GROUP_and_nothing_is_written() throws Exception {
        mvc.perform(post("/api/v1/ingest").contentType(MediaType.APPLICATION_JSON).content(golden))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_GROUP"))
                .andExpect(jsonPath("$.rowsInserted").value(0));
        mvc.perform(post("/api/v1/ingest?groupId=nope").contentType(MediaType.APPLICATION_JSON).content(golden))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_GROUP"))
                .andExpect(jsonPath("$.message").value("unknown or disabled group: nope"));
        verify(writer, never()).write(any(), any());
    }

    @Test
    void malformed_or_invalid_bodies_are_400_BAD_REQUEST_and_oversize_is_413() throws Exception {
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON)
                        .content(golden.replace("\"runId\": \"01KY1535CHHF9WG5HHZETVEQBZ\"", "\"runId\": \"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("runId is required"));
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pad\":\"" + "x".repeat(5000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        verify(writer, never()).write(any(), any());
    }

    @Test
    void a_database_failure_is_503_ORACLE_UNAVAILABLE_without_leaking_the_cause() throws Exception {
        when(writer.write(eq(CPS), any())).thenThrow(new DataAccessResourceFailureException("ORA-12541 no listener"));
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON).content(golden))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORACLE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("database unavailable; retry"));
    }

    @Test
    void an_unexpected_failure_is_500_INTERNAL_ERROR_with_a_fixed_message() throws Exception {
        when(writer.write(eq(CPS), any())).thenThrow(new IllegalStateException("dimension row neither found nor created"));
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.APPLICATION_JSON).content(golden))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("internal error"));
    }

    @Test
    void wrong_media_type_and_method_keep_their_status_with_the_hosted_codes() throws Exception {
        mvc.perform(post("/api/v1/ingest?groupId=cps").contentType(MediaType.TEXT_PLAIN).content(golden))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/v1/ingest?groupId=cps"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().exists("Allow"));
    }
}
