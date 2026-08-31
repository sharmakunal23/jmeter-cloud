package com.perf.orchestrator.lifecycle;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import bsh.Interpreter;
import bsh.util.Sessiond;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REAL bsh 2.0b6 {@link Sessiond} — the exact eval daemon JMeter
 * starts on {@code port + 1}. Two live-found bugs are pinned here: the client
 * once wrote to the Httpd port (statements answered "Bad Request", never
 * evaluated), and once emitted {@code props.put} — undefined in the SERVER
 * namespace, where only the {@code startup.bsh} helpers (setprop) exist.
 */
@DisplayName("BeanShellPropsClient ↔ real bsh Sessiond")
class BeanShellSessiondRoundTripTest {

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void startSessiond(Interpreter interpreter, int port) throws Exception {
        Thread daemon = new Thread(new Sessiond(interpreter.getNameSpace(), port));
        daemon.setDaemon(true);
        daemon.start();
    }

    @Test
    @DisplayName("setprop round-trips into the interpreter's properties, escapes intact")
    void pushEvaluatesInsideBsh() throws Exception {
        Properties props = new Properties();
        Interpreter interpreter = new Interpreter();
        interpreter.set("props", props);
        // The stock extras/startup.bsh helper the client's statements target.
        interpreter.eval("setprop(p, v) { props.setProperty(p, v); }");

        int sessiondPort = freePort();
        startSessiond(interpreter, sessiondPort);
        BeanShellPropsClient client = new BeanShellPropsClient(sessiondPort - 1);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                client.sendProperties(Map.of("rampSeconds", "60", "quoted", "a\\b\"c")));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(props.getProperty("rampSeconds")).isEqualTo("60");
            assertThat(props.getProperty("quoted")).isEqualTo("a\\b\"c");
        });
    }

    @Test
    @DisplayName("a namespace without setprop answers an eval Error — the push reports FALSE, not a lying success")
    void missingHelperIsAFailure() throws Exception {
        Interpreter bare = new Interpreter();   // no setprop — a stripped startup.bsh
        int sessiondPort = freePort();
        startSessiond(bare, sessiondPort);
        BeanShellPropsClient client = new BeanShellPropsClient(sessiondPort - 1);

        // Await the daemon accepting connections; the push itself must FAIL.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(client.sendProperties(Map.of("k", "v")))
                        .as("eval Error in the reply must surface as failure")
                        .isFalse());
    }
}
