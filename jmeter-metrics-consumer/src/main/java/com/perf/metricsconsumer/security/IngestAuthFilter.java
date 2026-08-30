package com.perf.metricsconsumer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.http.IngestResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The bearer-token gate on exactly {@code /api/v1/ingest}, active under the
 * {@code cloud} profile only (the platform runs without auth locally). The
 * token is {@code metricsConsumer.auth.token} ({@code METRICS_AUTH_TOKEN}),
 * compared in constant time; a miss is {@code 401 UNAUTHORIZED} with
 * {@code WWW-Authenticate: Bearer}. Actuator and the API docs are not filtered.
 */
@Component
@Profile({"cloud", "dev", "test", "prod"})   // every hosted tier; off under `local` (no auth locally)
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class IngestAuthFilter extends OncePerRequestFilter {

    static final String PROTECTED_PATH = "/api/v1/ingest";
    private static final String BEARER = "Bearer ";

    private final byte[] expected;
    private final ObjectMapper mapper;

    public IngestAuthFilter(@Value("${metricsConsumer.auth.token:}") String token, ObjectMapper mapper) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "METRICS_AUTH_TOKEN (metricsConsumer.auth.token) must be set under the cloud profile");
        }
        this.expected = token.trim().getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                new IngestResponse(0, "UNAUTHORIZED", "missing or invalid bearer token"));
    }

    boolean authorized(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        byte[] presented = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }
}
