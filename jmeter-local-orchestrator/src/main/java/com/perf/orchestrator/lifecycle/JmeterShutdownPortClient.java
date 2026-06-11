package com.perf.orchestrator.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * MID-TEST-SCALING Phase B — TCP client for JMeter's non-GUI shutdown port.
 *
 * <p>When JMeter is launched in non-GUI mode with
 * {@code -Jjmeterengine.nongui.port=N} (see {@code TestRunManager.buildLaunchSpec}),
 * it listens on {@code localhost:N} for plain-text commands:
 * <ul>
 *   <li>{@code Shutdown} — graceful: in-flight samplers complete, then exit.
 *   <li>{@code StopTestNow} — forceful: stop at end of current iteration.
 *   <li>{@code HeapDump} / {@code ThreadDump} — diagnostics (not used here).
 * </ul>
 *
 * <p>Operations are best-effort: a failed send (port not yet open, JMeter
 * already exited, etc.) returns false and the caller falls back to OS
 * signals (SIGTERM/SIGKILL via {@code JmeterProcess}).
 *
 * <p>The protocol is "open, write command + newline, close" — no response,
 * no auth (port is bound to localhost only). Tiny by design.
 */
public final class JmeterShutdownPortClient {

    private static final Logger LOG = LoggerFactory.getLogger(JmeterShutdownPortClient.class);

    /** Bound at most this long when opening the socket — JMeter binds locally. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /** Bound at most this long when writing — single command + newline. */
    private static final int WRITE_TIMEOUT_MS = 2_000;

    private final int port;

    public JmeterShutdownPortClient(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid jmeter shutdown port: " + port);
        }
        this.port = port;
    }

    public int port() { return port; }

    /** Sends "Shutdown" — graceful drain (in-flight samplers complete). */
    public boolean sendShutdown() {
        return send("Shutdown");
    }

    /** Sends "StopTestNow" — forceful (stop at end of iteration). */
    public boolean sendStopTestNow() {
        return send("StopTestNow");
    }

    private boolean send(String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WRITE_TIMEOUT_MS);
            try (OutputStream out = socket.getOutputStream()) {
                out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            LOG.info("sent '{}' to JMeter shutdown port {}", command, port);
            return true;
        } catch (IOException e) {
            // Common reasons: JMeter not yet listening (race during startup),
            // JMeter already exited, port not bound (operator disabled it).
            // Caller falls back to signals.
            LOG.warn("failed to send '{}' to JMeter shutdown port {}: {}",
                    command, port, e.toString());
            return false;
        }
    }
}
