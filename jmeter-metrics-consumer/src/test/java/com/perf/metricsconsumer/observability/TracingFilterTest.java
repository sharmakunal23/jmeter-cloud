package com.perf.metricsconsumer.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
    void ingestPopulatesActorMdc() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ingest");
        req.addHeader(TracingFilter.HEADER_ACTOR, "replay-cli");

        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (q, s) -> actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(actor.get()).isEqualTo("replay-cli");
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
    }

    @Test
    void actuatorPathSkipsFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (q, s) -> actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(actor.get()).isNull();
    }

    @Test
    void mdcClearedOnException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ingest");
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

        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
    }
}
