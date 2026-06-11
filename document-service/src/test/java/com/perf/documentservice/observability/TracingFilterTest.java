package com.perf.documentservice.observability;

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
    void blobPathPopulatesBlobIdAndActor() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/api/v1/blob/sha256-deadbeef/metadata");
        req.addHeader(TracingFilter.HEADER_ACTOR, "ui");

        AtomicReference<String> blob  = new AtomicReference<>();
        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (q, s) -> {
            blob.set(MDC.get(PathIds.KEY_BLOB_ID));
            actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(blob.get()).isEqualTo("sha256-deadbeef");
        assertThat(actor.get()).isEqualTo("ui");
        assertThat(MDC.get(PathIds.KEY_BLOB_ID)).isNull();
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/swagger-ui/", "/v3/api-docs"})
    void nonCriticalPathsSkipFilter(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (q, s) -> actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(actor.get()).isNull();
    }

    @Test
    void missingHeaderDefaultsActorToAnonymous() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/blob");
        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (q, s) -> actor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(actor.get()).isEqualTo(TracingFilter.DEFAULT_ACTOR);
    }

    @Test
    void mdcClearedEvenWhenChainThrows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE",
                "/api/v1/blob/abc");
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

        assertThat(MDC.get(PathIds.KEY_BLOB_ID)).isNull();
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
    }
}
