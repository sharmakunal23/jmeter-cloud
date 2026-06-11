package com.perf.globalorchestrator.observability;

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
        // Guard against test pollution if an assertion fired before the
        // filter's finally block ran.
        MDC.clear();
    }

    @Test
    void criticalRequestPopulatesActorAndIds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/api/v1/runs/r-1/members/worker-42/logs");
        req.addHeader(TracingFilter.HEADER_ACTOR, "ops-bot");

        AtomicReference<String> snapshotRunId   = new AtomicReference<>();
        AtomicReference<String> snapshotActor   = new AtomicReference<>();
        AtomicReference<String> snapshotWorker  = new AtomicReference<>();
        FilterChain chain = (q, s) -> {
            snapshotRunId.set(MDC.get(PathIds.KEY_RUN_ID));
            snapshotActor.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));
            snapshotWorker.set(MDC.get(PathIds.KEY_WORKER_ID));
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(snapshotRunId.get()).isEqualTo("r-1");
        assertThat(snapshotActor.get()).isEqualTo("ops-bot");
        assertThat(snapshotWorker.get()).isEqualTo("worker-42");
        // MDC must be empty afterwards — the filter cleaned up.
        assertThat(MDC.get(PathIds.KEY_RUN_ID)).isNull();
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
        assertThat(MDC.get(PathIds.KEY_WORKER_ID)).isNull();
    }

    @Test
    void missingActorHeaderDefaultsToAnonymous() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/runs/r-1");
        AtomicReference<String> snapshot = new AtomicReference<>();
        FilterChain chain = (q, s) -> snapshot.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(snapshot.get()).isEqualTo(TracingFilter.DEFAULT_ACTOR);
    }

    @Test
    void whitespaceOnlyActorDefaultsToAnonymous() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/runs/r-1");
        req.addHeader(TracingFilter.HEADER_ACTOR, "   ");
        AtomicReference<String> snapshot = new AtomicReference<>();
        FilterChain chain = (q, s) -> snapshot.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(snapshot.get()).isEqualTo(TracingFilter.DEFAULT_ACTOR);
    }

    @Test
    void nonCriticalPathSkipsFilter() throws Exception {
        // /actuator/health must NOT enrich MDC at all — verified by
        // observing that the filter's shouldNotFilter returns true and
        // the chain runs without any MDC mutation.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");

        AtomicReference<String> snapshot = new AtomicReference<>();
        FilterChain chain = (q, s) -> snapshot.set(MDC.get(TracingFilter.MDC_KEY_ACTOR));

        // shouldNotFilter is invoked by OncePerRequestFilter.doFilter — call
        // the wrapper so the framework's contract is exercised.
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(snapshot.get()).isNull();
    }

    @Test
    void mdcClearedEvenWhenChainThrows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/api/v1/applications/payments");
        FilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest q,
                                 jakarta.servlet.ServletResponse s)
                    throws IOException, ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("boom");

        // The filter's finally block must have removed every key it set.
        assertThat(MDC.get(PathIds.KEY_APPLICATION_ID)).isNull();
        assertThat(MDC.get(TracingFilter.MDC_KEY_ACTOR)).isNull();
    }

    @Test
    void preExistingMdcKeysFromAutoconfigArePreserved() throws Exception {
        // Simulate Spring Boot's Slf4jBaggageManager having set traceId
        // before our filter ran. Our finally must NOT touch it.
        MDC.put("traceId", "deadbeefcafebabe");
        try {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/runs/r-1");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(MDC.get("traceId")).isEqualTo("deadbeefcafebabe");
            assertThat(MDC.get(PathIds.KEY_RUN_ID)).isNull();
        } finally {
            MDC.remove("traceId");
        }
    }
}
