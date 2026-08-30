package com.perf.metricsconsumer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The cloud-profile bearer gate: exact URI, constant-time compare, 401 with the IngestResponse shape. */
class IngestAuthFilterTest {

    private final IngestAuthFilter filter = new IngestAuthFilter("s3cret", new ObjectMapper());

    @Test
    void only_the_ingest_uri_is_filtered() {
        assertThat(filter.shouldNotFilter(request("/api/v1/ingest"))).isFalse();
        for (String open : new String[] {"/api/v1/ingest/", "/actuator/health", "/swagger-ui.html", "/openapi.yaml"}) {
            assertThat(filter.shouldNotFilter(request(open))).as(open).isTrue();
        }
    }

    @Test
    void a_matching_bearer_token_passes_and_anything_else_is_401() throws Exception {
        assertThat(filter.authorized("Bearer s3cret")).isTrue();
        assertThat(filter.authorized("Bearer  s3cret ")).isTrue();
        assertThat(filter.authorized("Bearer nope")).isFalse();
        assertThat(filter.authorized("Basic s3cret")).isFalse();
        assertThat(filter.authorized(null)).isFalse();

        MockHttpServletRequest req = request("/api/v1/ingest");
        req.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(res.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"").contains("\"rowsInserted\":0");

        MockHttpServletRequest ok = request("/api/v1/ingest");
        ok.addHeader("Authorization", "Bearer s3cret");
        MockHttpServletResponse okRes = new MockHttpServletResponse();
        filter.doFilterInternal(ok, okRes, chain);
        verify(chain).doFilter(ok, okRes);
    }

    @Test
    void a_blank_token_refuses_to_boot_under_cloud() {
        assertThatThrownBy(() -> new IngestAuthFilter(" ", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("METRICS_AUTH_TOKEN");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        return req;
    }
}
