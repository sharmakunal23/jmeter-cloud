package com.perf.metricsconsumer.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects an oversize {@code /api/v1/ingest} body from its {@code Content-Length}
 * alone — before the controller buffers it — so a multi-GB POST costs a header
 * read, not heap. A chunked body (no length) still hits the controller's
 * post-read check, whose cap bounds the parse.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)   // after auth (+5): a bad token stays 401
public class IngestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;
    private final ObjectMapper mapper;

    public IngestBodyLimitFilter(@Value("${metricsConsumer.ingest.maxBodyBytes:2097152}") long maxBodyBytes,
                                 ObjectMapper mapper) {
        this.maxBodyBytes = maxBodyBytes;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/v1/ingest".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), new IngestResponse(0, "PAYLOAD_TOO_LARGE",
                    "Content-Length " + declared + " exceeds maxBodyBytes " + maxBodyBytes));
            return;
        }
        chain.doFilter(request, response);
    }
}
