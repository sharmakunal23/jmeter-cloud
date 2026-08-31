package com.perf.orchestrator.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * TCP client for JMeter's BeanShell server (enabled at launch via
 * {@code -Jbeanshell.server.port} + {@code -Jbeanshell.server.file}, see
 * {@code TestRunManager.buildLaunchSpec}) — the runtime property-update
 * channel (UX-DYNAMICS T5). Only ever emits {@code setprop("k","v");}
 * statements — raw scripts are never accepted or forwarded. {@code setprop}
 * is the stock {@code extras/startup.bsh} helper backed by
 * {@code JMeterUtils.getJMeterProperties()} — the same Properties
 * {@code ${__P(name)}} reads. The server console does NOT carry the
 * test-element bindings ({@code props} is undefined there — proven live), so
 * a customized startup file must keep the stock {@code setprop} helper.
 *
 * <p><b>The eval channel is {@code port + 1}.</b> bsh's {@code server(port)}
 * (which JMeter's BeanShellServer runs) starts an Httpd on {@code port} and
 * the raw-eval {@code Sessiond} on {@code port + 1}; a statement written to
 * {@code port} gets an HTTP "Bad Request" and is never evaluated. This client
 * takes the CONFIGURED port and always connects to {@code port + 1} — pinned
 * by the real-Sessiond round-trip test.
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

    /** The Sessiond eval port this client actually connects to. */
    public int sessiondPort() { return port + 1; }

    /**
     * Pushes every entry as one {@code setprop} statement and reads the
     * session's reply to EOF: the Sessiond echoes {@code // Error: …} when a
     * statement fails to evaluate, so a push that JMeter did not actually
     * apply returns false (→ 502) instead of a lying success. EOF arrives
     * promptly — closing our write half ends the bsh session.
     */
    public boolean sendProperties(Map<String, String> properties) {
        StringBuilder script = new StringBuilder(properties.size() * 48);
        properties.forEach((k, v) -> script.append(statement(k, v)));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", sessiondPort()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WRITE_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(script.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.shutdownOutput();
            String reply = readReply(socket.getInputStream());
            if (reply.contains("Error:")) {
                LOG.warn("BeanShell Sessiond on port {} rejected the push: {}",
                        sessiondPort(), reply.replaceAll("\\s+", " ").trim());
                return false;
            }
            LOG.info("pushed {} propert{} to the BeanShell Sessiond on port {}",
                    properties.size(), properties.size() == 1 ? "y" : "ies", sessiondPort());
            return true;
        } catch (IOException e) {
            LOG.warn("failed to push properties to the BeanShell Sessiond on port {}: {}",
                    sessiondPort(), e.toString());
            return false;
        }
    }

    /** Reads the session banner/prompts/errors to EOF, bounded by the socket timeout and 8 KB. */
    private static String readReply(InputStream in) throws IOException {
        return new String(in.readNBytes(8192), StandardCharsets.UTF_8);
    }

    /** One {@code setprop("key","escapedValue");} line. Package-private for the test. */
    static String statement(String key, String value) {
        return "setprop(\"" + key + "\",\"" + escape(value) + "\");\n";
    }

    /** Escapes {@code \} then {@code "} — the two characters legal in values that could break the literal. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
