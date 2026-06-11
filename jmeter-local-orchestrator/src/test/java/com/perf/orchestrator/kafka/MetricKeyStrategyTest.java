package com.perf.orchestrator.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetricKeyStrategy")
class MetricKeyStrategyTest {

    private final MetricKeyStrategy strategy = MetricKeyStrategy.standard();

    // -----------------------------------------------------------------------
    // Key format behaviour
    // K-1 envelope shape: {region}|{workerId}|{windowSecond}.
    // windowSecond was added in the K-1 amendment (2026-05-11) so a single
    // pod's envelopes spread across all 60 partitions over time. Per-pod
    // ordering across seconds is sacrificed; per-second within-split
    // adjacency is preserved (split envelopes for the same windowSecond
    // share one key and land on the same partition).
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("standard key format")
    class StandardKeyFormat {

        @Test
        @DisplayName("produces pipe-delimited key in {region}|{workerId}|{windowSecond} order")
        void produces_pipe_delimited_key_in_correct_order() {
            String key = strategy.keyFor("us-east-1", "jmeter-worker-4", 1_744_554_727L);

            assertThat(key).isEqualTo("us-east-1|jmeter-worker-4|1744554727");
        }

        @Test
        @DisplayName("key contains exactly two pipe separators")
        void key_contains_exactly_two_pipes() {
            String key = strategy.keyFor("us-west-2", "jmeter-worker-0", 1_744_554_727L);

            long pipeCount = key.chars().filter(c -> c == '|').count();
            assertThat(pipeCount).isEqualTo(2);
        }

        @Test
        @DisplayName("region is the first segment — enables Kafka consumer routing by region prefix")
        void region_is_first_segment() {
            String key = strategy.keyFor("us-east-1", "jmeter-worker-0", 1_744_554_727L);

            assertThat(key).startsWith("us-east-1|");
        }

        @Test
        @DisplayName("windowSecond is the last segment as a base-10 long")
        void window_second_is_last_segment() {
            String key = strategy.keyFor("us-east-1", "jmeter-worker-7", 1_744_554_727L);

            assertThat(key).endsWith("|1744554727");
        }
    }

    // -----------------------------------------------------------------------
    // Partition consistency behaviour — same inputs must always produce same key
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("partition consistency")
    class PartitionConsistency {

        @Test
        @DisplayName("same inputs always produce the same key — required for split envelopes (>500 entries) to land together")
        void same_inputs_produce_identical_keys() {
            String key1 = strategy.keyFor("us-east-1", "jmeter-worker-4", 1_744_554_727L);
            String key2 = strategy.keyFor("us-east-1", "jmeter-worker-4", 1_744_554_727L);

            assertThat(key1).isEqualTo(key2);
        }

        @ParameterizedTest(name = "different {0} produces a different key")
        @CsvSource({
                "region,        eu-west-1,   jmeter-worker-4, 1744554727",
                "workerId,      us-east-1,   jmeter-worker-9, 1744554727",
                "windowSecond,  us-east-1,   jmeter-worker-4, 1744554728"
        })
        @DisplayName("changing any single dimension changes the key — different dimensions must not collide")
        void changing_any_dimension_changes_key(
                String dimension,
                String region, String workerId, long windowSecond) {

            String baseline = strategy.keyFor("us-east-1", "jmeter-worker-4", 1_744_554_727L);
            String variant  = strategy.keyFor(region, workerId, windowSecond);

            assertThat(variant)
                    .as("changing '%s' must produce a different key to drive partition spread", dimension)
                    .isNotEqualTo(baseline);
        }
    }

    // -----------------------------------------------------------------------
    // Partition spread behaviour — the load-bearing K-1-amendment win
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("partition spread")
    class PartitionSpread {

        @Test
        @DisplayName("a single pod producing 60 seconds of envelopes generates 60 distinct keys — full fan-out across 60 partitions")
        void same_pod_distinct_keys_across_seconds() {
            Set<String> keys = new HashSet<>();
            for (long sec = 1_744_554_700L; sec < 1_744_554_760L; sec++) {
                keys.add(strategy.keyFor("us-east-1", "jmeter-worker-0", sec));
            }
            assertThat(keys)
                    .as("60 distinct windowSeconds → 60 distinct keys → full partition fan-out via Kafka's hash partitioner")
                    .hasSize(60);
        }

        @Test
        @DisplayName("split envelopes for the same windowSecond share one key — consumer sees them adjacently")
        void same_window_second_same_key_for_split_envelopes() {
            // When a pod-window's entries exceed MAX_ENTRIES_PER_ENVELOPE (500),
            // TumblingWindowAggregator emits multiple envelopes carrying the
            // same envelope-level metadata. They must share a partition so the
            // consumer can process them together.
            String split1 = strategy.keyFor("us-east-1", "jmeter-worker-0", 1_744_554_727L);
            String split2 = strategy.keyFor("us-east-1", "jmeter-worker-0", 1_744_554_727L);

            assertThat(split1).isEqualTo(split2);
        }
    }

    // -----------------------------------------------------------------------
    // Functional interface behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("as a functional interface")
    class AsFunctionalInterface {

        @Test
        @DisplayName("can be replaced with a lambda in tests — fixed key for partition-agnostic tests")
        void accepts_lambda_implementation() {
            MetricKeyStrategy fixedKey = (r, w, s) -> "test-key";

            assertThat(fixedKey.keyFor("any-region", "any-worker", 0L))
                    .isEqualTo("test-key");
        }
    }
}
