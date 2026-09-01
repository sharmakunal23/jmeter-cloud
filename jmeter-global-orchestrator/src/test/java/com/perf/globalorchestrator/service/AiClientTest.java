package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.service.AiClient.AiUpstreamException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Messages API response contract. The truncation and refusal cases are the
 * point: without them a half-written JSON object becomes the operator's summary
 * and is cached under it for the whole TTL.
 */
@DisplayName("AiClient — Messages API response contract")
class AiClientTest {

    private AiClient client(int maxTokens) {
        return new AiClient(new ObjectMapper(), "test-key", "claude-opus-5",
                "https://api.anthropic.com", maxTokens, "high", "adaptive", true);
    }

    @Test
    @DisplayName("concatenates text blocks and reads the token counts")
    void readsTextAndUsage() {
        AiClient.AiResult r = client(8192).parse("""
                {"stop_reason":"end_turn",
                 "content":[{"type":"text","text":"{\\"summary\\":"},
                            {"type":"text","text":"\\"ok\\"}"}],
                 "usage":{"input_tokens":3120,"output_tokens":210}}""");
        assertThat(r.text()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(r.tokensIn()).isEqualTo(3120);
        assertThat(r.tokensOut()).isEqualTo(210);
    }

    @Test
    @DisplayName("skips thinking blocks so reasoning never lands in the summary")
    void skipsThinkingBlocks() {
        AiClient.AiResult r = client(8192).parse("""
                {"stop_reason":"end_turn",
                 "content":[{"type":"thinking","thinking":"weighing the tail latency"},
                            {"type":"text","text":"answer"}],
                 "usage":{"input_tokens":1,"output_tokens":1}}""");
        assertThat(r.text()).isEqualTo("answer");
    }

    @Test
    @DisplayName("a max_tokens stop is an error, not a partial answer")
    void truncationIsRejected() {
        assertThatThrownBy(() -> client(512).parse("""
                {"stop_reason":"max_tokens",
                 "content":[{"type":"text","text":"{\\"summary\\":\\"the run ramp"}],
                 "usage":{"input_tokens":3120,"output_tokens":512}}"""))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessageContaining("max_tokens")
                .hasMessageContaining("512");
    }

    @Test
    @DisplayName("a refusal is surfaced with its category")
    void refusalIsRejected() {
        assertThatThrownBy(() -> client(8192).parse("""
                {"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"},
                 "content":[],"usage":{"input_tokens":10,"output_tokens":0}}"""))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessageContaining("cyber");
    }

    @Test
    @DisplayName("an answer with no text block is an error, not an empty summary")
    void emptyContentIsRejected() {
        assertThatThrownBy(() -> client(8192).parse("""
                {"stop_reason":"end_turn","content":[],"usage":{"input_tokens":1,"output_tokens":0}}"""))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessageContaining("no text content");
    }

    @Test
    @DisplayName("a non-JSON body is an upstream error, not a crash")
    void unparseableBodyIsRejected() {
        assertThatThrownBy(() -> client(8192).parse("<html>502 Bad Gateway</html>"))
                .isInstanceOf(AiUpstreamException.class);
    }

    @Test
    @DisplayName("no API key means disabled, and complete() refuses before any call")
    void disabledWithoutKey() {
        AiClient off = new AiClient(new ObjectMapper(), "  ", "claude-opus-5",
                "https://api.anthropic.com/", 8192, "high", "adaptive", true);
        assertThat(off.isEnabled()).isFalse();
        assertThatThrownBy(() -> off.complete("sys", "user"))
                .isInstanceOf(AiClient.AiDisabledException.class);
    }
}
