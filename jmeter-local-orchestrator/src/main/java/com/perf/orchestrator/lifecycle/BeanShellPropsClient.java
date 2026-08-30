package com.perf.orchestrator.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * TCP client for JMeter's BeanShell server (enabled at launch via
 * {@code -Jbeanshell.server.port} + {@code -Jbeanshell.server.file}, see
 * {@code TestRunManager.buildLaunchSpec}) — the runtime property-update
 * channel (UX-DYNAMICS T5). Only ever emits {@code props.put("k","v");}
 * statements — raw scripts are never accepted or forwarded.
 *
 * <p>Injection-safe by construction: keys are already validated to
 * {@code [A-Za-z_][A-Za-z0-9_.]{0,63}} (no quoting needed) and values have
 * {@code \} and {@code "} escaped; {@link JmeterProperties} rejects control
 * characters, so no newline can break out of the string literal.
 *
 * <p>Best-effort like {@link JmeterShutdownPortClient}: a failed send (port
 * not yet open, JMeter exited, server disabled) returns false and the caller
 * maps it to a 502. Only plan values read through {@code ${__P(name)}}
 * observe an update, at their next evaluation.
 */
public final class BeanShellPropsClient {

    private static final Logger LOG = LoggerFactory.getLogger(BeanShellPropsClient.class);

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int WRITE_TIMEOUT_MS = 2_000;

    private final int port;

    public BeanShellPropsClient(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid beanshell server port: " + port);
        }
        this.port = port;
    }

    public int port() { return port; }

    /** Pushes every entry as one {@code props.put} statement; false on any IO failure. */
    public boolean sendProperties(Map<String, String> properties) {
        StringBuilder script = new StringBuilder(properties.size() * 48);
        properties.forEach((k, v) -> script.append(statement(k, v)));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WRITE_TIMEOUT_MS);
            try (OutputStream out = socket.getOutputStream()) {
                out.write(script.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            LOG.info("pushed {} propert{} to the BeanShell server on port {}",
                    properties.size(), properties.size() == 1 ? "y" : "ies", port);
            return true;
        } catch (IOException e) {
            LOG.warn("failed to push properties to the BeanShell server on port {}: {}",
                    port, e.toString());
            return false;
        }
    }

    /** One {@code props.put("key","escapedValue");} line. Package-private for the test. */
    static String statement(String key, String value) {
        return "props.put(\"" + key + "\",\"" + escape(value) + "\");\n";
    }

    /** Escapes {@code \} then {@code "} — the two characters legal in values that could break the literal. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
