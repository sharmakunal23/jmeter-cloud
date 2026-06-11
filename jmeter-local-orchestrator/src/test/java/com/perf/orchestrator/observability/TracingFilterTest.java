package com.perf.orchestrator.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingFilterTest {

    private final TracingFilter filter = new TracingFilter();

    @AfterEach
    void clearMdcSpillover() {
        MDC.clear();
    }

    @Test
    void criticalEndpointPopulatesActorAndRunIdFromHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/test");
        req.addHeader(TracingFilter.HEADER_ACTOR, "global-orch");
        req.addHeader(TracingFilter.HEADER_RUN_ID, "run-abc-123");

        AtomicReference<String> actor = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        FilterChain chain = (q, s) -> {
            actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));
            runId.set(MDC.get(TracingFilter.MDC_KEY_RUN_ID));
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(actor.get()).isEqualTo("global-orch");
        assertThat(runId.get()).isEqualTo("run-abc-123");
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
        assertThat(MDC.get(TracingFilter.MDC_KEY_RUN_ID)).isNull();
    }

    @Test
    void runIdHeaderMissingDoesNotInsertMdcKey() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/test");

        AtomicReference<String> runId = new AtomicReference<>("set");
        FilterChain chain = (q, s) -> runId.set(MDC.get(TracingFilter.MDC_KEY_RUN_ID));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(runId.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/swagger-ui/", "/v3/api-docs", "/openapi.yaml"})
    void nonCriticalPathsSkipFilter(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (q, s) -> actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(actor.get()).isNull();
    }

    @Test
    void mdcClearedOnException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/test");
        req.addHeader(TracingFilter.HEADER_RUN_ID, "r-1");

        FilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest q,
                                 jakarta.servlet.ServletResponse s)
                    throws IOException, ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(TracingFilter.MDC_KEY_RUN_ID)).isNull();
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
    }
}
