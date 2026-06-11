package com.perf.documentservice.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring smoke test for document-service's AccessLogFilter. The full
 * edge-case matrix is exercised by the equivalent test in jmeter-
 * global-orchestrator; this one just confirms the copy wires up.
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
        accessLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void cleanup() {
        accessLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void blobEndpointEmitsAccessLine() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/blob/sha256-abc");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(200);

        filter.doFilter(req, resp, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap())
                .containsEntry(AccessLogFilter.MDC_METHOD, "GET")
                .containsEntry(AccessLogFilter.MDC_PATH, "/api/v1/blob/sha256-abc")
                .containsEntry(AccessLogFilter.MDC_STATUS, "200");
    }

    @Test
    void actuatorPathSkipsFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(appender.list).isEmpty();
    }
}
