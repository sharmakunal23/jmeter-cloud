package com.perf.metricsconsumer.observability;

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

/** Wiring smoke for metrics-consumer's AccessLogFilter. See global-orch test for full matrix. */
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
    void ingestEndpointEmitsAccessLine() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ingest");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(202);

        filter.doFilter(req, resp, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry(AccessLogFilter.MDC_METHOD, "POST")
                .containsEntry(AccessLogFilter.MDC_PATH, "/api/v1/ingest")
                .containsEntry(AccessLogFilter.MDC_STATUS, "202");
    }

    @Test
    void actuatorPathSkipsFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(appender.list).isEmpty();
    }
}
