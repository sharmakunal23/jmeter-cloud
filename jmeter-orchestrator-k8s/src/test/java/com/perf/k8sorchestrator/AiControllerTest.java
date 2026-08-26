package com.perf.k8sorchestrator;

import com.perf.k8sorchestrator.domain.RunInsights;
import com.perf.k8sorchestrator.http.AiController;
import com.perf.k8sorchestrator.service.AiClient;
import com.perf.k8sorchestrator.service.AiClient.AiResult;
import com.perf.k8sorchestrator.service.AiClient.AiUpstreamException;
import com.perf.k8sorchestrator.service.AiInsightsService;
import com.perf.k8sorchestrator.service.AiQuotaGuard;
import com.perf.k8sorchestrator.service.RunService.RunNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI-0/AI-1/AI-2 — controller-level tests of the HTTP contract + error mapping,
 * with a real {@link AiQuotaGuard} (cap = 1) and mocked {@link AiClient} /
 * {@link AiInsightsService}. Standalone MockMvc (no Spring context, no Postgres)
 * keeps these fast; the DB-backed cache behaviour is covered by
 * {@link AiInsightsIT}.
 */
@DisplayName("AiController — HTTP contract + error mapping")
class AiControllerTest {

    private AiClient ai;
    private AiInsightsService insights;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ai = mock(AiClient.class);
        insights = mock(AiInsightsService.class);
        when(ai.model()).thenReturn("claude-test");
        // Real quota guard with a cap of 1 so the second invocation trips it.
        AiController controller = new AiController(ai, new AiQuotaGuard(1), insights);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /ai/status reports enabled + model (no quota consumed)")
    void status_reportsEnabledAndModel() throws Exception {
        when(ai.isEnabled()).thenReturn(true);
        mvc.perform(get("/api/v1/ai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.model").value("claude-test"));
    }

    @Test
    @DisplayName("POST /ai/ping returns the reply + token counts")
    void ping_happyPath() throws Exception {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(any(), any())).thenReturn(new AiResult("pong", 3, 1));
        mvc.perform(post("/api/v1/ai/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("pong"))
                .andExpect(jsonPath("$.tokensIn").value(3))
                .andExpect(jsonPath("$.tokensOut").value(1));
    }

    @Test
    @DisplayName("POST /ai/ping → 503 AI_DISABLED when no API key is configured")
    void ping_disabled_returns503() throws Exception {
        when(ai.isEnabled()).thenReturn(false);
        mvc.perform(post("/api/v1/ai/ping"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_DISABLED"));
    }

    @Test
    @DisplayName("the daily cap trips: second invocation → 429 AI_QUOTA_EXCEEDED")
    void quotaCap_returns429() throws Exception {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(any(), any())).thenReturn(new AiResult("pong", 1, 1));
        // cap = 1 → first ping consumes the budget, second trips the guard.
        mvc.perform(post("/api/v1/ai/ping")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/ai/ping"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("POST /runs/{id}/insights → 404 RUN_NOT_FOUND for an unknown run")
    void insights_unknownRun_returns404() throws Exception {
        when(insights.runInsights(eq("01ARZ3NDEKTSV4RRFFQ69G5FAV"), anyBoolean()))
                .thenThrow(new RunNotFoundException("run not found"));
        mvc.perform(post("/api/v1/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV/insights"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /runs/{id}/insights → 502 AI_UPSTREAM_ERROR when the provider call fails")
    void insights_upstreamFailure_returns502() throws Exception {
        when(insights.runInsights(any(), anyBoolean()))
                .thenThrow(new AiUpstreamException("Anthropic API returned 529"));
        mvc.perform(post("/api/v1/runs/01ARZ3NDEKTSV4RRFFQ69G5FB1/insights"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
    }

    @Test
    @DisplayName("POST /runs/{id}/insights returns the summary + findings")
    void insights_happyPath() throws Exception {
        RunInsights stub = new RunInsights("01ARZ3NDEKTSV4RRFFQ69G5FB1", "claude-test", "v1",
                "Steady throughput.",
                List.of(new RunInsights.Finding("warn", "Latency tail", "p99 climbed late.")),
                10, 20, Instant.parse("2026-05-31T00:00:00Z"), false);
        when(insights.runInsights(eq("01ARZ3NDEKTSV4RRFFQ69G5FB1"), eq(false))).thenReturn(stub);
        mvc.perform(post("/api/v1/runs/01ARZ3NDEKTSV4RRFFQ69G5FB1/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Steady throughput."))
                .andExpect(jsonPath("$.findings[0].severity").value("warn"))
                .andExpect(jsonPath("$.fromCache").value(false));
    }

    @Test
    @DisplayName("POST /runs/compare-insights → 400 INVALID_REQUEST when not exactly 2 ids")
    void compare_wrongIdCount_returns400() throws Exception {
        mvc.perform(post("/api/v1/runs/compare-insights?ids=onlyOne"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/runs/compare-insights?ids=A,B,C"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/runs/compare-insights?ids=DUPE,DUPE"))
                .andExpect(status().isBadRequest());
    }
}
