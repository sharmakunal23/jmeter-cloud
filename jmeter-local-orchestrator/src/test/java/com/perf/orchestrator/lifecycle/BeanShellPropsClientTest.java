package com.perf.orchestrator.lifecycle;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-DYNAMICS T5 — the BeanShell push is injection-safe by construction:
 * only {@code props.put} statements, with {@code \} and {@code "} escaped
 * (control characters are already rejected upstream by JmeterProperties).
 */
@DisplayName("BeanShellPropsClient")
class BeanShellPropsClientTest {

    @Test
    @DisplayName("statement shape — backslash then quote escaped, one line per entry")
    void statementShapeAndEscaping() {
        assertThat(BeanShellPropsClient.statement("threads", "50"))
                .isEqualTo("setprop(\"threads\",\"50\");\n");
        assertThat(BeanShellPropsClient.statement("k", "a\\b\"c"))
                .isEqualTo("setprop(\"k\",\"a\\\\b\\\"c\");\n");
    }

    @Test
    @DisplayName("sendProperties writes every statement to the SESSIOND port (configured+1)")
    void pushWritesAllStatements() throws Exception {
        // The listener stands in for bsh's Sessiond, which server(port)
        // starts on port+1 — so the client is configured with port-1.
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Map<String, String> props = new LinkedHashMap<>();
            props.put("rampSeconds", "60");
            props.put("target", "https://sut.example.com/api");
            boolean sent = new BeanShellPropsClient(server.getLocalPort() - 1).sendProperties(props);
            assertThat(sent).isTrue();
            assertThat(received.get())
                    .isEqualTo("setprop(\"rampSeconds\",\"60\");\n"
                            + "setprop(\"target\",\"https://sut.example.com/api\");\n");
        }
    }

    @Test
    @DisplayName("a refused connection returns false (best-effort, caller maps to 502)")
    void refusedConnectionReturnsFalse() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        assertThat(new BeanShellPropsClient(freePort - 1).sendProperties(Map.of("k", "v"))).isFalse();
    }
}
