package com.perf.globalorchestrator.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SECURITY S-1 — pins the RateLimitFilter's denied path (the one abusers
 * exercise): burst-then-429 with Retry-After + metric, per-endpoint isolation,
 * the local-default off switch, and the non-critical-path exemption. Standalone
 * MockMvc (no Spring context) — exercises the filter directly against a stub
 * controller, all requests sharing MockMvc's 127.0.0.1 remote addr (one bucket
 * per endpoint class).
 */
@DisplayName("SECURITY S-1 — RateLimitFilter (per-IP, per-endpoint buckets)")
class RateLimitFilterTest {

    @RestController
    static class StubController {
        @PostMapping("/api/v1/runs")                       String launch()                       { return "ok"; }
        @GetMapping("/api/v1/runs/{id}/timeseries")        String ts(@PathVariable String id)     { return "ok"; }
        @GetMapping("/api/v1/runs/{id}/status")            String status(@PathVariable String id) { return "ok"; }
        @GetMapping("/actuator/health")                    String health()                        { return "ok"; }
    }

    private MockMvc mvcWith(RateLimitFilter filter) {
        return MockMvcBuilders.standaloneSetup(new StubController()).addFilters(filter).build();
    }

    @Test
    @DisplayName("RUNS_LAUNCH: 10-burst passes, 11th → 429 + Retry-After + metric increments")
    void runsLaunchBurstThen429() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MockMvc mvc = mvcWith(new RateLimitFilter(true, false, 1000, reg));

        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/runs")).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/runs"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertThat(reg.get("security.ratelimit.exceeded").tag("endpoint", "RUNS_LAUNCH").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("per-endpoint isolation: exhausting RUNS_LAUNCH leaves the TIMESERIES bucket untouched")
    void perEndpointIsolation() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MockMvc mvc = mvcWith(new RateLimitFilter(true, false, 1000, reg));

        for (int i = 0; i < 11; i++) {
            mvc.perform(post("/api/v1/runs"));          // drains the RUNS_LAUNCH bucket (last one 429s)
        }
        mvc.perform(get("/api/v1/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV/timeseries"))
                .andExpect(status().isOk());            // separate bucket → still served
    }

    @Test
    @DisplayName("disabled (the local default): nothing is limited")
    void disabledPassesEverything() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MockMvc mvc = mvcWith(new RateLimitFilter(false, false, 1000, reg));

        for (int i = 0; i < 50; i++) {
            mvc.perform(post("/api/v1/runs")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("non-critical paths (actuator) are exempt even when enabled")
    void actuatorExempt() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MockMvc mvc = mvcWith(new RateLimitFilter(true, false, 1000, reg));

        for (int i = 0; i < 100; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
