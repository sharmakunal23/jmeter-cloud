package com.perf.orchestrator.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

@DisplayName("IngestReachabilityProbe — daemon-thread cached readiness")
class IngestReachabilityProbeTest {

    private static final Duration POLL    = Duration.ofMillis(50);
    private static final Duration TIMEOUT = Duration.ofMillis(200);

    @Nested
    @DisplayName("initial state and first probe")
    class InitialState {

        @Test
        @DisplayName("returns DOWN/startup_in_progress before the first probe completes — never reports a false UP at boot")
        void initial_snapshot_is_startup_in_progress() {
            // Stub client that NEVER returns — ensures the first probe is
            // still in-flight when we read snapshot().
            BlockingProbeClient client = new BlockingProbeClient();
            IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    client, POLL, TIMEOUT, "probe-initial");

            try {
                IngestReachabilityProbe.Snapshot s = probe.snapshot();
                assertSoftly(softly -> {
                    softly.assertThat(s.reachable()).isFalse();
                    softly.assertThat(s.reason()).isEqualTo("startup_in_progress");
                });
            } finally {
                client.unblock();
                probe.close();
            }
        }

        @Test
        @DisplayName("flips to UP after the first successful probe — bounded by probe timeout, not by polling interval")
        void up_after_first_successful_probe() throws IOException {
            FakeProbeClient client = new FakeProbeClient(IngestProbeClient.Result.up());
            try (IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    client, POLL, TIMEOUT, "probe-first-up")) {
                probe.start();

                await().atMost(Duration.ofSeconds(2))
                        .until(() -> probe.snapshot().reachable());

                assertThat(probe.snapshot().reachable()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("recovery and degradation")
    class StateTransitions {

        @Test
        @DisplayName("flips to DOWN with the supplied reason when checkReachable returns unreachable — operators see the precise cause")
        void down_when_check_reports_unreachable() throws IOException {
            FakeProbeClient client = new FakeProbeClient(
                    IngestProbeClient.Result.unreachable("ingest_unreachable"));
            try (IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    client, POLL, TIMEOUT, "probe-down")) {
                probe.start();

                await().atMost(Duration.ofSeconds(2))
                        .until(() -> "ingest_unreachable".equals(probe.snapshot().reason()));

                assertSoftly(softly -> {
                    softly.assertThat(probe.snapshot().reachable()).isFalse();
                    softly.assertThat(probe.snapshot().reason()).isEqualTo("ingest_unreachable");
                });
            }
        }

        @Test
        @DisplayName("recovers from DOWN to UP when the next probe succeeds — daemon thread keeps polling across transient faults")
        void recovers_from_down_to_up() throws IOException {
            AtomicReference<IngestProbeClient.Result> nextResult =
                    new AtomicReference<>(IngestProbeClient.Result.unreachable("ingest_unreachable"));
            FakeProbeClient client = new FakeProbeClient(nextResult::get);

            try (IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    client, POLL, TIMEOUT, "probe-recover")) {
                probe.start();

                await().atMost(Duration.ofSeconds(2))
                        .until(() -> "ingest_unreachable".equals(probe.snapshot().reason()));

                nextResult.set(IngestProbeClient.Result.up());

                await().atMost(Duration.ofSeconds(2))
                        .until(() -> probe.snapshot().reachable());

                assertThat(probe.snapshot().reachable()).isTrue();
            }
        }

        @Test
        @DisplayName("a misbehaving client that throws is caught and translated to DOWN — the daemon thread must not die")
        void throwing_client_does_not_kill_loop() throws IOException {
            AtomicBoolean throwOnce = new AtomicBoolean(true);
            FakeProbeClient client = new FakeProbeClient(() -> {
                if (throwOnce.compareAndSet(true, false)) {
                    throw new RuntimeException("boom");
                }
                return IngestProbeClient.Result.up();
            });

            try (IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    client, POLL, TIMEOUT, "probe-throws")) {
                probe.start();

                // The loop survives the throw and the next poll succeeds.
                await().atMost(Duration.ofSeconds(2))
                        .until(() -> probe.snapshot().reachable());

                assertThat(probe.snapshot().reachable()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("close() stops the polling thread and closes the client — idempotent, no resource leak across runs")
        void close_stops_thread_and_closes_client() throws IOException {
            FakeProbeClient client = new FakeProbeClient(IngestProbeClient.Result.up());
            IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    client, POLL, TIMEOUT, "probe-close");
            probe.start();

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> client.callCount.get() > 0);

            probe.close();

            // Second close is a no-op (idempotent).
            probe.close();

            // Capture the call count, then sleep; no further calls must arrive.
            int countAtClose = client.callCount.get();
            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            assertSoftly(softly -> {
                softly.assertThat(client.closed.get()).as("client.close was invoked").isTrue();
                softly.assertThat(client.callCount.get())
                        .as("no probe calls after close — daemon thread is gone")
                        .isLessThanOrEqualTo(countAtClose);
            });
        }

        @Test
        @DisplayName("a null client (init failed) yields a permanent DOWN snapshot with ingest_probe_init_failed reason")
        void null_client_yields_permanent_down() throws IOException {
            try (IngestReachabilityProbe probe = IngestReachabilityProbe.withClient(
                    null, POLL, TIMEOUT, "probe-null")) {
                probe.start();

                IngestReachabilityProbe.Snapshot s = probe.snapshot();
                assertSoftly(softly -> {
                    softly.assertThat(s.reachable()).isFalse();
                    softly.assertThat(s.reason()).isEqualTo("ingest_probe_init_failed");
                });
            }
        }
    }

    // -----------------------------------------------------------------------
    // Stubs
    // -----------------------------------------------------------------------

    /** Stub client returning a configurable result; counts calls. */
    private static final class FakeProbeClient implements IngestProbeClient {
        private final java.util.function.Supplier<Result> nextResult;
        final AtomicInteger callCount = new AtomicInteger();
        final AtomicBoolean closed    = new AtomicBoolean();

        FakeProbeClient(Result fixed) {
            this(() -> fixed);
        }
        FakeProbeClient(java.util.function.Supplier<Result> supplier) {
            this.nextResult = supplier;
        }

        @Override public Result checkReachable(Duration timeout) {
            callCount.incrementAndGet();
            return nextResult.get();
        }
        @Override public void close() { closed.set(true); }
    }

    /** Stub client whose checkReachable blocks indefinitely until unblock(). */
    private static final class BlockingProbeClient implements IngestProbeClient {
        private final Object lock = new Object();
        private volatile boolean unblocked;

        @Override public Result checkReachable(Duration timeout) {
            synchronized (lock) {
                while (!unblocked) {
                    try { lock.wait(); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Result.unreachable("interrupted");
                    }
                }
            }
            return Result.up();
        }
        @Override public void close() {}
        void unblock() {
            synchronized (lock) { unblocked = true; lock.notifyAll(); }
        }
    }
}
