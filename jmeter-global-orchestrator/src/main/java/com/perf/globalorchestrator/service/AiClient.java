package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.observability.ErrorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-0 — thin client for the Anthropic Messages API.
 *
 * <p>Deliberately NOT the {@code anthropic-java} SDK: the fat JAR is already at
 * the doc's 80 MB flag and the call is a single JSON POST, so we use the
 * {@link RestClient} that already ships with {@code spring-boot-starter-web}.
 * Zero new dependencies.
 *
 * <p>The bean is <b>always</b> constructed (not {@code @ConditionalOnProperty})
 * so callers can inject it unconditionally; {@link #isEnabled()} reports whether
 * an API key is present. With no key the AI surfaces 503 {@code AI_DISABLED} and
 * the UI hides its buttons (via {@code GET /api/v1/ai/status}), so local dev
 * without a key still boots green.
 *
 * <p>The API key never leaves this service — the browser calls the
 * orchestrator's {@code /api/v1/ai/**} endpoints, never Anthropic directly.
 */
@Service
public class AiClient {

    private static final Logger LOG = LoggerFactory.getLogger(AiClient.class);

    /** Anthropic Messages API version header — pinned per their versioning contract. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String model;
    private final int maxTokens;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AiClient(
            ObjectMapper mapper,
            @Value("${globalOrchestrator.ai.apiKey:}") String apiKey,
            @Value("${globalOrchestrator.ai.model:claude-sonnet-4-6}") String model,
            @Value("${globalOrchestrator.ai.baseUrl:https://api.anthropic.com}") String baseUrl,
            @Value("${globalOrchestrator.ai.maxTokens:512}") int maxTokens) {
        this.mapper = mapper;
        this.model = model;
        this.maxTokens = maxTokens;
        this.enabled = apiKey != null && !apiKey.isBlank();

        // Claude calls take seconds, not milliseconds: a generous read timeout
        // (the operator sees a "Claude is reading your run…" spinner) but a tight
        // connect timeout so an unreachable endpoint fails fast.
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(5_000);
        rf.setReadTimeout(90_000);

        this.http = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(rf)
                .defaultHeader("x-api-key", apiKey == null ? "" : apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", "application/json")
                .build();
    }

    /** True when an {@code ANTHROPIC_API_KEY} is configured. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The model id reported to the UI + persisted alongside each cached response. */
    public String model() {
        return model;
    }

    /**
     * Single-turn completion. {@code system} sets the role + output contract;
     * {@code user} carries the run data. Returns the concatenated text blocks
     * plus the token counts (surfaced to the operator for cost observability).
     *
     * @throws AiDisabledException  when no API key is configured.
     * @throws AiUpstreamException  on any non-2xx, connectivity failure, or
     *                              unparseable response.
     */
    public AiResult complete(String system, String user) {
        if (!enabled) {
            throw new AiDisabledException("ANTHROPIC_API_KEY is not configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));

        String responseBody;
        try {
            responseBody = http.post()
                    .uri("/v1/messages")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            ErrorContext.logWarn(LOG, "aiComplete model=" + model,
                    "Anthropic API returned " + e.getStatusCode(), e);
            throw new AiUpstreamException(
                    "Anthropic API returned " + e.getStatusCode().value()
                            + ": " + truncate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            ErrorContext.logWarn(LOG, "aiComplete model=" + model,
                    "Anthropic API call failed", e);
            throw new AiUpstreamException("Anthropic API call failed: " + e.getMessage(), e);
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            StringBuilder text = new StringBuilder();
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        text.append(block.path("text").asText());
                    }
                }
            }
            if (text.length() == 0) {
                throw new AiUpstreamException("Anthropic response carried no text content");
            }
            int tokensIn = root.path("usage").path("input_tokens").asInt();
            int tokensOut = root.path("usage").path("output_tokens").asInt();
            return new AiResult(text.toString(), tokensIn, tokensOut);
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUpstreamException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }

    /** Completion result: the text plus the billed token counts. */
    public record AiResult(String text, int tokensIn, int tokensOut) { }

    /** No API key configured — the controller maps this to 503 {@code AI_DISABLED}. */
    public static class AiDisabledException extends RuntimeException {
        public AiDisabledException(String message) { super(message); }
    }

    /** Upstream call failed / returned non-2xx / unparseable — mapped to 502 {@code AI_UPSTREAM_ERROR}. */
    public static class AiUpstreamException extends RuntimeException {
        public AiUpstreamException(String message, Throwable cause) { super(message, cause); }
        public AiUpstreamException(String message) { super(message); }
    }
}
