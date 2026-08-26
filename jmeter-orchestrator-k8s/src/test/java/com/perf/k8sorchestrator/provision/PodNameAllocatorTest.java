package com.perf.k8sorchestrator.provision;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-function tests for {@link PodNameAllocator#nextSlotIndex}. */
class PodNameAllocatorTest {

    @Test
    void emptyRegistryStartsAtOne() {
        int n = PodNameAllocator.nextSlotIndex("payments", "us-east", List.of());
        assertThat(n).isEqualTo(1);
    }

    @Test
    void monotonicallyIncreasingSequenceFillsNext() {
        int n = PodNameAllocator.nextSlotIndex("payments", "us-east", Set.of(
                "payments-us-east-worker-1",
                "payments-us-east-worker-2",
                "payments-us-east-worker-3"));
        assertThat(n).isEqualTo(4);
    }

    @Test
    void gapInTheSequenceFillsTheLowestGap() {
        // [1, 2, 4] → next is 3
        int n = PodNameAllocator.nextSlotIndex("payments", "us-east", Set.of(
                "payments-us-east-worker-1",
                "payments-us-east-worker-2",
                "payments-us-east-worker-4"));
        assertThat(n).isEqualTo(3);
    }

    @Test
    void singleHighSlotStillFillsOne() {
        // [42] → next is 1, not 43
        int n = PodNameAllocator.nextSlotIndex("payments", "us-east", Set.of(
                "payments-us-east-worker-42"));
        assertThat(n).isEqualTo(1);
    }

    @Test
    void unrelatedPodNamesAreIgnored() {
        // Other apps + other regions + legacy names contribute nothing.
        int n = PodNameAllocator.nextSlotIndex("payments", "us-east", Set.of(
                "checkout-us-east-worker-1",         // different app
                "payments-us-west-worker-1",         // different region
                "orchestrator-1",                    // legacy static
                "payments-us-east-worker-bad",       // non-integer suffix
                "payments-us-east-worker-1"));       // single relevant entry
        assertThat(n).isEqualTo(2);
    }

    @Test
    void formatProducesTheExpectedShape() {
        assertThat(PodNameAllocator.format("payments", "us-east", 1))
                .isEqualTo("payments-us-east-worker-1");
        assertThat(PodNameAllocator.format("checkout-svc", "local-east-1", 42))
                .isEqualTo("checkout-svc-local-east-1-worker-42");
    }

    @Test
    void overlongInputsWouldExceedDnsLimit() {
        // appName(40) + region(20) → format() produces a name well over the
        // 63-char DNS-1123 cap. The bean's validate() rejects this combination
        // before the format call ever runs; the standalone format helper
        // doesn't validate (kept simple for tests + reconciler reads).
        String longApp = "a".repeat(40);
        String longRegion = "b".repeat(20);
        String wouldBe = PodNameAllocator.format(longApp, longRegion, 1);
        assertThat(wouldBe.length()).isGreaterThan(PodNameAllocator.MAX_POD_NAME_LENGTH);
    }
}
