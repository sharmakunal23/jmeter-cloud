package com.perf.k8sorchestrator.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the AccessLogFilter contract using
 * a Logback {@link ListAppender} attached to the {@code "access"} logger.
 * The filter is exercised through {@link OncePerRequestFilter#doFilter}
 * so the {@code shouldNotFilter} contract is verified end-to-end.
 */
class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
    private ListAppender<ILoggingEvent> appender;
    private Logger accessLogger;

    @BeforeEach
    void attachAppender() {
        accessLogger = (Logger) LoggerFactory.getLogger("access");
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
        // Make sure ALL access-line levels are captured — the parent root
        // logger may be set higher in the test JVM.
        accessLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void cleanup() {
        accessLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void criticalRequestEmitsExactlyOneAccessLine() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/applications");
        req.setRemoteAddr("203.0.113.5");
        req.addHeader("User-Agent", "phase-e-smoke/1.0");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(200);

        filter.doFilter(req, resp, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMessage()).isEqualTo("access");
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMDCPropertyMap())
                .containsEntry(AccessLogFilter.MDC_METHOD, "GET")
                .containsEntry(AccessLogFilter.MDC_PATH, "/api/v1/applications")
                .containsEntry(AccessLogFilter.MDC_STATUS, "200")
                .containsEntry(AccessLogFilter.MDC_CLIENT_IP, "203.0.113.5")
                .containsEntry(AccessLogFilter.MDC_USER_AGENT, "phase-e-smoke/1.0")
                .containsKey(AccessLogFilter.MDC_LATENCY_MS);
        long latency = Long.parseLong(event.getMDCPropertyMap().get(AccessLogFilter.MDC_LATENCY_MS));
        assertThat(latency).isGreaterThanOrEqualTo(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/prometheus", "/swagger-ui/", "/v3/api-docs", "/openapi.yaml", "/favicon.ico"})
    void nonCriticalPathsEmitNoAccessLine(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).isEmpty();
    }

    @Test
    void xForwardedForFirstTokenWinsOverRemoteAddr() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/runs");
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.50");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry(AccessLogFilter.MDC_CLIENT_IP, "203.0.113.10");
    }

    @Test
    void blankXForwardedForFallsBackToRemoteAddr() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/runs");
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "   ");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry(AccessLogFilter.MDC_CLIENT_IP, "10.0.0.1");
    }

    @Test
    void chainExceptionStillEmitsAccessLineAtWarn() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/runs");
        FilterChain throwing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest q,
                                 jakarta.servlet.ServletResponse s)
                    throws IOException, ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), throwing))
                .isInstanceOf(ServletException.class)
                .hasMessage("boom");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getMDCPropertyMap())
                .containsEntry(AccessLogFilter.MDC_METHOD, "POST")
                .containsEntry(AccessLogFilter.MDC_PATH, "/api/v1/runs");
    }

    @Test
    void mdcClearedAfterAccessLine() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/applications");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        // The keys the filter set are gone — but Phase A's tracing-autoconfig
        // keys (traceId, spanId) would be untouched. None of those are set
        // in this test, so MDC should be empty.
        assertThat(MDC.get(AccessLogFilter.MDC_METHOD)).isNull();
        assertThat(MDC.get(AccessLogFilter.MDC_PATH)).isNull();
        assertThat(MDC.get(AccessLogFilter.MDC_STATUS)).isNull();
        assertThat(MDC.get(AccessLogFilter.MDC_LATENCY_MS)).isNull();
        assertThat(MDC.get(AccessLogFilter.MDC_CLIENT_IP)).isNull();
        assertThat(MDC.get(AccessLogFilter.MDC_USER_AGENT)).isNull();
    }

    @Test
    void latencyMsIsMonotoneNonNegativeEvenForFastChain() throws Exception {
        // Sanity check — a synchronous mock chain can complete in <1ms;
        // we must report 0, not a negative or wrapped value.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/applications");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(5);
        for (ILoggingEvent e : events) {
            long latency = Long.parseLong(e.getMDCPropertyMap().get(AccessLogFilter.MDC_LATENCY_MS));
            assertThat(latency).isGreaterThanOrEqualTo(0L);
        }
    }
}
