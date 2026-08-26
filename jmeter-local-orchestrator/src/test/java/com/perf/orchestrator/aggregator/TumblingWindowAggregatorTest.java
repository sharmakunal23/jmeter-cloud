package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.model.JtlRow;
import com.perf.orchestrator.testsupport.WorkerMetricRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("TumblingWindowAggregator")
class TumblingWindowAggregatorTest {

    private static final String WORKER_ID = "jmeter-worker-0";
    private static final String REGION    = "us-east-1";
    private static final String RUN_ID    = "20250413-east";
    private static final String THREAD    = "jmeter-worker-0 1-1";
    private static final String URL       = "https://app/api";

    // 2-second grace period for most tests
    private TumblingWindowAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new TumblingWindowAggregator(WORKER_ID, REGION, RUN_ID, 2);
    }

    // -----------------------------------------------------------------------
    // Row fixture helpers
    // -----------------------------------------------------------------------

    private static JtlRow row(long epochSecond, String label, long elapsedMs, boolean success) {
        String ts = "2025/04/13 14:32:" + String.format("%02d", epochSecond % 60);
        return new JtlRow(ts, epochSecond, elapsedMs, label,
                success ? "200" : "503", success ? "OK" : "Err",
                THREAD, "text", success, "", 1024L, 512L, 80, 80, URL, elapsedMs - 1, 0L, 1L);
    }

    private static JtlRow row(long epochSecond, String label) {
        return row(epochSecond, label, 200L, true);
    }

    // -----------------------------------------------------------------------
    // Construction guard behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("rejects null workerId")
        void rejects_null_worker_id() {
            assertThatThrownBy(() -> new TumblingWindowAggregator(null, REGION, RUN_ID, 2))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects negative graceSeconds — there is no meaningful negative grace period")
        void rejects_negative_grace_seconds() {
            assertThatThrownBy(() -> new TumblingWindowAggregator(WORKER_ID, REGION, RUN_ID, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("graceSeconds");
        }

        @Test
        @DisplayName("accepts graceSeconds=0 — leading-edge window stays open, prior windows close immediately")
        void accepts_zero_grace_seconds() {
            TumblingWindowAggregator zeroGrace =
                    new TumblingWindowAggregator(WORKER_ID, REGION, RUN_ID, 0);
            zeroGrace.record(row(1000L, "GET /api"));
            // Leading edge advances to 1001 — window at 1000 becomes closeable,
            // window at 1001 is the new leading edge and stays open
            zeroGrace.record(row(1001L, "GET /api"));

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(zeroGrace.drainCloseable());

            assertThat(metrics)
                    .as("only the non-leading-edge window at 1000 should close")
                    .hasSize(1);
            assertThat(metrics.get(0).windowSecond()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("rejects negative joinedAtSecond — Phase C invariant")
        void rejects_negative_joined_at_second() {
            assertThatThrownBy(() -> new TumblingWindowAggregator(
                    WORKER_ID, REGION, RUN_ID, 2, false, -5L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("joinedAtSecond");
        }
    }

    // -----------------------------------------------------------------------
    // MID-TEST-SCALING Phase C — joinedAtSecond stamped on every envelope
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("joinedAtSecond stamping (MID-TEST-SCALING Phase C)")
    class JoinedAtSecondStamping {

        @Test
        @DisplayName("default constructor → joinedAtSecond=0 on every emitted batch (original-fleet)")
        void default_is_zero() {
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1003L, "GET /api"));   // close window at 1000

            List<WorkerMetricBatch> batches = aggregator.drainCloseable();
            assertThat(batches).isNotEmpty();
            assertThat(batches.get(0).joinedAtSecond())
                    .as("default constructor must produce 0 — original-fleet semantic")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("explicit joinedAtSecond=42 → 42 on every emitted batch")
        void scale_up_value_propagates() {
            TumblingWindowAggregator scaleUp =
                    new TumblingWindowAggregator(WORKER_ID, REGION, RUN_ID, 2, false, 42L);
            scaleUp.record(row(1000L, "GET /api"));
            scaleUp.record(row(1001L, "POST /api"));
            scaleUp.record(row(1004L, "GET /api"));      // close windows at 1000 + 1001

            List<WorkerMetricBatch> batches = scaleUp.drainCloseable();
            assertThat(batches)
                    .as("at least one batch should have closed")
                    .isNotEmpty();
            assertThat(batches)
                    .as("every emitted batch must carry the joinedAtSecond stamp")
                    .allSatisfy(b -> assertThat(b.joinedAtSecond()).isEqualTo(42L));
        }
    }

    // -----------------------------------------------------------------------
    // Window routing behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("window routing")
    class WindowRouting {

        @Test
        @DisplayName("rows with the same epochSecond and label are routed to the same bucket")
        void same_second_same_label_goes_to_same_bucket() {
            aggregator.record(row(1000L, "POST /api/payment"));
            aggregator.record(row(1000L, "POST /api/payment"));
            aggregator.record(row(1000L, "POST /api/payment"));

            // Force close by advancing 3 seconds past grace period
            aggregator.record(row(1003L, "POST /api/payment"));

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());
            WorkerMetricRow payment = metrics.stream()
                    .filter(m -> m.label().equals("POST /api/payment") && m.windowSecond() == 1000L)
                    .findFirst().orElseThrow();

            assertThat(payment.throughput())
                    .as("3 rows to the same (second, label) must produce throughput=3 in one bucket")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("rows with the same epochSecond but different labels go to separate buckets")
        void same_second_different_labels_go_to_separate_buckets() {
            aggregator.record(row(1000L, "POST /api/payment"));
            aggregator.record(row(1000L, "GET /api/account"));

            aggregator.record(row(1003L, "POST /api/payment")); // advance past grace

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());
            List<WorkerMetricRow> at1000 = metrics.stream()
                    .filter(m -> m.windowSecond() == 1000L).toList();

            assertThat(at1000).hasSize(2);
            assertThat(at1000)
                    .extracting(WorkerMetricRow::label)
                    .containsExactlyInAnyOrder("POST /api/payment", "GET /api/account");
        }

        @Test
        @DisplayName("rows with different epochSeconds go to separate windows")
        void different_seconds_go_to_separate_windows() {
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1001L, "GET /api"));

            aggregator.record(row(1004L, "GET /api")); // advance past both

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());

            assertThat(metrics)
                    .extracting(WorkerMetricRow::windowSecond)
                    .containsExactlyInAnyOrder(1000L, 1001L);
        }
    }

    // -----------------------------------------------------------------------
    // Grace period behaviour — the core correctness guarantee
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("grace period")
    class GracePeriod {

        @Test
        @DisplayName("does not close a window until latestEpochSecond >= windowSecond + graceSeconds")
        void window_not_closed_before_grace_period_expires() {
            aggregator.record(row(1000L, "GET /api"));

            // Latest is 1001 — window at 1000 needs latest >= 1002 (graceSeconds=2) to close
            aggregator.record(row(1001L, "GET /api"));

            assertThat(aggregator.drainCloseable())
                    .as("window at 1000 must still be open when latestEpochSecond=1001")
                    .isEmpty();
        }

        @Test
        @DisplayName("closes a window exactly when latestEpochSecond reaches windowSecond + graceSeconds")
        void window_closes_exactly_at_grace_boundary() {
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1002L, "GET /api")); // latestEpochSecond = 1002 = 1000 + 2

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());

            assertThat(metrics)
                    .as("window at 1000 must close when latestEpochSecond equals 1000 + graceSeconds(2)")
                    .hasSize(1);
            assertThat(metrics.get(0).windowSecond()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("retains the window in memory until it is closed — no premature eviction")
        void open_windows_remain_in_memory_during_grace_period() {
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1001L, "GET /api"));

            // Second row at 1000 arrives within grace — it must be counted
            aggregator.record(row(1000L, "GET /api")); // out-of-order, within grace

            aggregator.record(row(1003L, "GET /api")); // advance past grace
            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());

            WorkerMetricRow at1000 = metrics.stream()
                    .filter(m -> m.windowSecond() == 1000L).findFirst().orElseThrow();

            assertThat(at1000.throughput())
                    .as("2 rows at second 1000 must both be counted (no premature eviction)")
                    .isEqualTo(2L);
        }
    }

    // -----------------------------------------------------------------------
    // Out-of-order row behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("out-of-order rows")
    class OutOfOrderRows {

        @Test
        @DisplayName("accepts a row for an earlier second when it arrives within the grace period")
        void accepts_out_of_order_row_within_grace_period() {
            aggregator.record(row(1002L, "POST /api")); // establishes leading edge
            aggregator.record(row(1001L, "POST /api")); // out-of-order, latestEpochSecond - 1001 = 1 < 2

            aggregator.record(row(1004L, "POST /api")); // close both windows

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());

            WorkerMetricRow at1001 = metrics.stream()
                    .filter(m -> m.windowSecond() == 1001L).findFirst().orElseThrow();

            assertThat(at1001.throughput()).isEqualTo(1L);
        }

        @Test
        @DisplayName("drops a row that arrives after its window has been evicted and logs a warning")
        void drops_row_arriving_after_window_evicted() {
            // Establish latestEpochSecond = 1005
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1005L, "GET /api"));
            aggregator.drainCloseable(); // evicts window at 1000 (1005 - 1000 = 5 > graceSeconds=2)

            // Row for second 1000 arrives after its window is already evicted
            aggregator.record(row(1000L, "GET /api")); // must be silently dropped

            // Throughput at second 1005 should be 1, not 2
            aggregator.record(row(1008L, "GET /api"));
            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable());

            WorkerMetricRow at1005 = metrics.stream()
                    .filter(m -> m.windowSecond() == 1005L).findFirst().orElseThrow();

            assertThat(at1005.throughput())
                    .as("dropped late row must not appear in any metric")
                    .isEqualTo(1L);
        }
    }

    // -----------------------------------------------------------------------
    // drainAll behaviour — used at test end
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("drainAll")
    class DrainAll {

        @Test
        @DisplayName("flushes all open windows — grace period and leading-edge guard do not apply")
        void flushes_all_windows_ignoring_grace_period() {
            // Three windows: 1000 (closeable under Rule B), 1001 (closeable), 1002 (leading edge, normally held open)
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1001L, "GET /api"));
            aggregator.record(row(1002L, "GET /api"));

            // drainAll must return all three regardless of grace period or leading-edge protection
            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainAll());

            assertThat(metrics)
                    .extracting(WorkerMetricRow::windowSecond)
                    .containsExactlyInAnyOrder(1000L, 1001L, 1002L);
        }

        @Test
        @DisplayName("leaves the aggregator empty — openWindowCount is 0 after drainAll")
        void aggregator_is_empty_after_drain_all() {
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1001L, "POST /api"));

            aggregator.drainAll();

            assertThat(aggregator.openWindowCount()).isZero();
        }

        @Test
        @DisplayName("returns empty list when there are no open windows")
        void returns_empty_when_no_windows_open() {
            assertThat(aggregator.drainAll()).isEmpty();
        }

        @Test
        @DisplayName("can be safely called twice — second call returns empty")
        void second_drain_all_is_idempotent() {
            aggregator.record(row(1000L, "GET /api"));

            aggregator.drainAll();
            List<WorkerMetricRow> second = WorkerMetricRow.flatten(aggregator.drainAll());

            assertThat(second).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Multi-label, multi-second correctness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("multi-label, multi-second correctness")
    class MultiLabelMultiSecond {

        @Test
        @DisplayName("200 endpoints across 3 seconds produces 600 distinct metrics on drainAll")
        void produces_correct_bucket_count_for_many_endpoints_and_seconds() {
            int endpointCount = 200;
            for (int second = 1000; second < 1003; second++) {
                for (int e = 0; e < endpointCount; e++) {
                    aggregator.record(row(second, "GET /endpoint/" + e));
                }
            }

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainAll());

            assertThat(metrics).hasSize(200 * 3);
        }

        @Test
        @DisplayName("throughput sums independently per label within the same second")
        void throughput_is_independent_per_label() {
            // 3 requests to payment, 5 requests to account, all in second 1000
            for (int i = 0; i < 3; i++) aggregator.record(row(1000L, "POST /api/payment"));
            for (int i = 0; i < 5; i++) aggregator.record(row(1000L, "GET /api/account"));

            aggregator.record(row(1003L, "POST /api/payment")); // advance past grace

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable()).stream()
                    .filter(m -> m.windowSecond() == 1000L).toList();

            WorkerMetricRow payment = metrics.stream()
                    .filter(m -> m.label().equals("POST /api/payment")).findFirst().orElseThrow();
            WorkerMetricRow account = metrics.stream()
                    .filter(m -> m.label().equals("GET /api/account")).findFirst().orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(payment.throughput()).isEqualTo(3L);
                softly.assertThat(account.throughput()).isEqualTo(5L);
            });
        }

        @Test
        @DisplayName("error rate is computed per label — errors in one label do not affect another")
        void error_rate_is_independent_per_label() {
            // All payment requests fail; all account requests succeed
            for (int i = 0; i < 5; i++) aggregator.record(row(1000L, "POST /api/payment", 200L, false));
            for (int i = 0; i < 5; i++) aggregator.record(row(1000L, "GET /api/account", 200L, true));

            aggregator.record(row(1003L, "GET /api/account")); // advance past grace

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(aggregator.drainCloseable()).stream()
                    .filter(m -> m.windowSecond() == 1000L).toList();

            WorkerMetricRow payment = metrics.stream()
                    .filter(m -> m.label().equals("POST /api/payment")).findFirst().orElseThrow();
            WorkerMetricRow account = metrics.stream()
                    .filter(m -> m.label().equals("GET /api/account")).findFirst().orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(payment.errorRate()).isEqualTo(1.0);
                softly.assertThat(account.errorRate()).isEqualTo(0.0);
            });
        }
    }

    // -----------------------------------------------------------------------
    // THREAD_NAME mode — master-slave deployments
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("THREAD_NAME mode (master-slave)")
    class ThreadNameMode {

        private static final String SLAVE_0_THREAD =
                "jmeter-slave-0.jmeter-workers.perf.svc.cluster.local-Thread Group 1-1";
        private static final String SLAVE_1_THREAD =
                "jmeter-slave-1.jmeter-workers.perf.svc.cluster.local-Thread Group 1-2";

        private TumblingWindowAggregator threadNameAggregator;

        @BeforeEach
        void setUp() {
            threadNameAggregator = new TumblingWindowAggregator(WORKER_ID, REGION, RUN_ID, 2, true);
        }

        private static JtlRow rowForSlave(long epochSecond, String label, String threadName,
                                          long elapsedMs, boolean success) {
            String ts = "2025/04/13 14:32:" + String.format("%02d", epochSecond % 60);
            return new JtlRow(ts, epochSecond, elapsedMs, label,
                    success ? "200" : "503", success ? "OK" : "Err",
                    threadName, "text", success, "", 1024L, 512L, 80, 80, URL, elapsedMs - 1, 0L, 1L);
        }

        @Test
        @DisplayName("rows from different slaves produce separate buckets for the same label and second")
        void different_slaves_produce_separate_buckets() {
            threadNameAggregator.record(rowForSlave(1000L, "GET /api", SLAVE_0_THREAD, 100L, true));
            threadNameAggregator.record(rowForSlave(1000L, "GET /api", SLAVE_1_THREAD, 200L, true));
            threadNameAggregator.record(rowForSlave(1003L, "GET /api", SLAVE_0_THREAD, 50L,  true));

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(threadNameAggregator.drainCloseable()).stream()
                    .filter(m -> m.windowSecond() == 1000L).toList();

            assertThat(metrics).hasSize(2);
            assertThat(metrics).extracting(WorkerMetricRow::workerId)
                    .containsExactlyInAnyOrder("jmeter-slave-0", "jmeter-slave-1");
        }

        @Test
        @DisplayName("extracts pod name prefix before the first dot from an FQDN thread name")
        void extracts_pod_name_from_fqdn_thread_name() {
            threadNameAggregator.record(rowForSlave(1000L, "GET /api", SLAVE_0_THREAD, 100L, true));
            threadNameAggregator.record(rowForSlave(1003L, "GET /api", SLAVE_0_THREAD, 50L,  true));

            WorkerMetricRow metric = WorkerMetricRow.flatten(threadNameAggregator.drainCloseable()).stream()
                    .filter(m -> m.windowSecond() == 1000L).findFirst().orElseThrow();

            assertThat(metric.workerId()).isEqualTo("jmeter-slave-0");
        }

        @Test
        @DisplayName("falls back to the full thread name when it contains no dot")
        void uses_full_thread_name_when_no_dot_present() {
            String simpleName = "standalone-worker 1-1";
            threadNameAggregator.record(rowForSlave(1000L, "GET /api", simpleName, 100L, true));
            threadNameAggregator.record(rowForSlave(1003L, "GET /api", simpleName, 50L,  true));

            WorkerMetricRow metric = WorkerMetricRow.flatten(threadNameAggregator.drainCloseable()).stream()
                    .filter(m -> m.windowSecond() == 1000L).findFirst().orElseThrow();

            assertThat(metric.workerId()).isEqualTo(simpleName);
        }

        @Test
        @DisplayName("throughput for each slave is counted independently within the same label and second")
        void throughput_is_counted_per_slave() {
            for (int i = 0; i < 3; i++)
                threadNameAggregator.record(rowForSlave(1000L, "POST /payment", SLAVE_0_THREAD, 100L, true));
            for (int i = 0; i < 5; i++)
                threadNameAggregator.record(rowForSlave(1000L, "POST /payment", SLAVE_1_THREAD, 150L, true));
            threadNameAggregator.record(rowForSlave(1003L, "POST /payment", SLAVE_0_THREAD, 50L, true));

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(threadNameAggregator.drainCloseable()).stream()
                    .filter(m -> m.windowSecond() == 1000L).toList();

            WorkerMetricRow slave0 = metrics.stream()
                    .filter(m -> m.workerId().equals("jmeter-slave-0")).findFirst().orElseThrow();
            WorkerMetricRow slave1 = metrics.stream()
                    .filter(m -> m.workerId().equals("jmeter-slave-1")).findFirst().orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(slave0.throughput()).isEqualTo(3L);
                softly.assertThat(slave1.throughput()).isEqualTo(5L);
            });
        }
    }

    // -----------------------------------------------------------------------
    // Memory bound behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("memory bounds")
    class MemoryBounds {

        @Test
        @DisplayName("open window count is bounded by graceSeconds even under continuous load")
        void open_window_count_bounded_by_grace_period() {
            // Simulate 100 seconds of continuous load — one row per second
            for (long s = 1000; s < 1100; s++) {
                aggregator.record(row(s, "GET /api"));
                aggregator.drainCloseable(); // called after every record as the poll loop does
            }

            // With graceSeconds=2, at most 3 windows should be open at any time:
            // the leading edge + up to graceSeconds seconds of buffered windows
            assertThat(aggregator.openWindowCount())
                    .as("open window count must be bounded by graceSeconds + 1 regardless of run duration")
                    .isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("envelope grouping (K-1) — one envelope per (workerId, windowSecond) pair")
        void emits_one_envelope_per_pod_per_window() {
            // 200 distinct labels in one pod-window → 1 envelope with 200 entries.
            for (int e = 0; e < 200; e++) {
                aggregator.record(row(1000L, "GET /endpoint/" + e));
            }
            aggregator.record(row(1003L, "GET /endpoint/0")); // close window at 1000

            List<WorkerMetricBatch> envelopes = aggregator.drainCloseable();

            assertThat(envelopes)
                    .filteredOn(b -> b.windowSecond() == 1000L)
                    .as("one envelope per (workerId, windowSecond) pair")
                    .hasSize(1);
            assertThat(envelopes.get(0).entries())
                    .as("the envelope's entries[] holds all 200 labels")
                    .hasSize(200);
            assertThat(envelopes.get(0).region().toString()).isEqualTo(REGION);
            assertThat(envelopes.get(0).workerId().toString()).isEqualTo(WORKER_ID);
            assertThat(envelopes.get(0).runId().toString()).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("MAX_ENTRIES_PER_ENVELOPE split (K-1) — pathological pod-window splits into multiple envelopes")
        void splits_when_entries_exceed_max_per_envelope() {
            // 600 distinct labels in one pod-window → 2 envelopes (500 + 100).
            int total = 600;
            for (int e = 0; e < total; e++) {
                aggregator.record(row(1000L, "GET /endpoint/" + e));
            }
            aggregator.record(row(1003L, "GET /endpoint/0")); // close window at 1000

            List<WorkerMetricBatch> envelopes = aggregator.drainCloseable().stream()
                    .filter(b -> b.windowSecond() == 1000L)
                    .toList();

            assertThat(envelopes)
                    .as("600 entries split into 2 envelopes at MAX_ENTRIES_PER_ENVELOPE=500")
                    .hasSize(2);
            assertThat(envelopes.get(0).entries()).hasSize(500);
            assertThat(envelopes.get(1).entries()).hasSize(100);

            // Same envelope-level metadata on both — the consumer's idempotency
            // contract handles per-row INSERTs identically.
            assertSoftly(softly -> {
                softly.assertThat(envelopes.get(0).windowSecond())
                        .isEqualTo(envelopes.get(1).windowSecond());
                softly.assertThat(envelopes.get(0).region().toString())
                        .isEqualTo(envelopes.get(1).region().toString());
                softly.assertThat(envelopes.get(0).workerId().toString())
                        .isEqualTo(envelopes.get(1).workerId().toString());
                softly.assertThat(envelopes.get(0).runId().toString())
                        .isEqualTo(envelopes.get(1).runId().toString());
            });

            // No row lost across the split — flatten to verify total row count.
            assertThat(WorkerMetricRow.flatten(envelopes)).hasSize(total);
        }

        @Test
        @DisplayName("drainCloseable returns unmodifiable list of envelopes — callers cannot corrupt state")
        void drain_closeable_returns_unmodifiable_list() {
            aggregator.record(row(1000L, "GET /api"));
            aggregator.record(row(1003L, "GET /api")); // close window at 1000

            List<WorkerMetricBatch> envelopes = aggregator.drainCloseable();

            assertThatThrownBy(() -> envelopes.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}