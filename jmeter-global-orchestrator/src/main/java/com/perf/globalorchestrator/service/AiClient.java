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
 * Thin client for the Anthropic Messages API — one JSON POST to
 * {@code /v1/messages}, no SDK (the fat JAR is already near its size budget and
 * {@link RestClient} ships with {@code spring-boot-starter-web}).
 *
 * <p>Three response rules callers depend on: a {@code stop_reason} of
 * {@code max_tokens} or {@code refusal} is an {@link AiUpstreamException}, never
 * a partial answer — a truncated body would otherwise be cached as if it were
 * the summary; only {@code text} blocks are concatenated, so a thinking block
 * never leaks into the output; and passing a {@code responseSchema} sets
 * {@code output_config.format}, which makes the reply schema-valid JSON rather
 * than something the caller has to recover by hand.
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
    /** Beta gate for the {@code fallbacks} parameter; sent only when fallbacks are on. */
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String model;
    private final int maxTokens;
    private final String effort;
    private final String thinking;
    private final boolean structuredOutput;
    private final String fallbacks;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AiClient(
            ObjectMapper mapper,
            @Value("${globalOrchestrator.ai.apiKey:}") String apiKey,
            @Value("${globalOrchestrator.ai.model:claude-opus-5}") String model,
            @Value("${globalOrchestrator.ai.baseUrl:https://api.anthropic.com}") String baseUrl,
            @Value("${globalOrchestrator.ai.maxTokens:16000}") int maxTokens,
            @Value("${globalOrchestrator.ai.effort:high}") String effort,
            @Value("${globalOrchestrator.ai.thinking:adaptive}") String thinking,
            @Value("${globalOrchestrator.ai.structuredOutput:true}") boolean structuredOutput,
            @Value("${globalOrchestrator.ai.fallbacks:default}") String fallbacks) {
        this.mapper = mapper;
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = blankToNull(effort);
        this.thinking = blankToNull(thinking);
        this.structuredOutput = structuredOutput;
        this.fallbacks = blankToNull(fallbacks);
        this.enabled = apiKey != null && !apiKey.isBlank();

        // Adaptive thinking on a hard run can take a minute or more before the
        // first byte, so the read timeout is generous (the operator sees a
        // "Claude is reading your run…" spinner); the connect timeout stays
        // tight so an unreachable endpoint fails fast.
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(5_000);
        rf.setReadTimeout(180_000);

        RestClient.Builder b = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(rf)
                .defaultHeader("x-api-key", apiKey == null ? "" : apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", "application/json");
        if (this.fallbacks != null) {
            b = b.defaultHeader("anthropic-beta", FALLBACK_BETA);
        }
        this.http = b.build();
    }

    /** True when an {@code ANTHROPIC_API_KEY} is configured. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The model id reported to the UI + persisted alongside each cached response. */
    public String model() {
        return model;
    }

    /** Free-text completion — the {@code /ai/ping} smoke test; no schema constraint. */
    public AiResult complete(String system, String user) {
        return complete(system, user, null);
    }

    /**
     * Single-turn completion. {@code system} sets the role + output contract;
     * {@code user} carries the run data. Returns the concatenated text blocks
     * plus the token counts (surfaced to the operator for cost observability).
     *
     * @param responseSchema JSON Schema the reply must satisfy, or null for free
     *                       text. Sent as {@code output_config.format}, so the
     *                       model cannot answer with prose around the JSON.
     * @throws AiDisabledException  when no API key is configured.
     * @throws AiUpstreamException  on any non-2xx, connectivity failure,
     *                              truncated / refused answer, or unparseable response.
     */
    public AiResult complete(String system, String user, Map<String, Object> responseSchema) {
        if (!enabled) {
            throw new AiDisabledException("ANTHROPIC_API_KEY is not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));
        if (thinking != null) {
            body.put("thinking", Map.of("type", thinking));
        }
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        if (effort != null) {
            outputConfig.put("effort", effort);
        }
        if (structuredOutput && responseSchema != null) {
            outputConfig.put("format", Map.of("type", "json_schema", "schema", responseSchema));
        }
        if (!outputConfig.isEmpty()) {
            body.put("output_config", outputConfig);
        }
        // A safety classifier can decline a digest (HTTP 200, stop_reason
        // "refusal"), which parse() turns into a hard 502 — so the run would be
        // unanalysable, ?fresh=true included. Fallbacks re-run it server-side on
        // another model inside the same call.
        if (fallbacks != null) {
            body.put("fallbacks", fallbacks);
        }

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

        return parse(responseBody);
    }

    /**
     * Reads one Messages API response. Package-private so the wire contract can
     * be tested without an HTTP call.
     */
    AiResult parse(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AiUpstreamException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }

        // A truncated or declined answer is NOT a result: returning it would let
        // half a JSON object be stored as the operator's summary for the whole
        // cache TTL. Fail loudly and let the caller surface 502 instead.
        String stopReason = root.path("stop_reason").asText("");
        if ("max_tokens".equals(stopReason)) {
            throw new AiUpstreamException(
                    "Anthropic response hit max_tokens (" + maxTokens + ") and is truncated;"
                            + " raise globalOrchestrator.ai.maxTokens");
        }
        if ("refusal".equals(stopReason)) {
            throw new AiUpstreamException("Anthropic declined the request: "
                    + root.path("stop_details").path("category").asText("unspecified"));
        }

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
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "none".equalsIgnoreCase(s.trim()) ? null : s.trim();
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
