package com.perf.orchestrator.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WarningThrottle")
class WarningThrottleTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<String> emitted = new ArrayList<>();
    private final List<Long> summaries = new ArrayList<>();

    private WarningThrottle throttle(int burst, long windowMs) {
        return new WarningThrottle(burst, windowMs, clock::get);
    }

    private void fire(WarningThrottle t) {
        t.record(() -> emitted.add("full"), summaries::add);
    }

    @Test
    @DisplayName("emits the first `burst` in full then suppresses the rest within a window")
    void emits_first_burst_then_suppresses() {
        WarningThrottle t = throttle(3, 60_000);
        for (int i = 0; i < 100; i++) fire(t); // clock fixed → one window
        assertThat(emitted).hasSize(3);
        assertThat(summaries).as("summary only fires on window roll-over").isEmpty();
    }

    @Test
    @DisplayName("on window roll-over, emits one summary of the suppressed count")
    void summarizes_suppressed_on_rollover() {
        WarningThrottle t = throttle(2, 60_000);
        for (int i = 0; i < 10; i++) fire(t); // 2 emitted, 8 suppressed
        assertThat(emitted).hasSize(2);

        clock.addAndGet(60_001);             // past the window
        fire(t);                             // → summary(8), then this one is first-of-new-window → emitted
        assertThat(summaries).containsExactly(8L);
        assertThat(emitted).hasSize(3);
    }

    @Test
    @DisplayName("no summary when nothing was suppressed")
    void no_summary_without_suppression() {
        WarningThrottle t = throttle(5, 60_000);
        fire(t);
        fire(t);                             // 2 emitted, 0 suppressed
        clock.addAndGet(60_001);
        fire(t);                             // window roll, nothing suppressed → no summary
        assertThat(summaries).isEmpty();
        assertThat(emitted).hasSize(3);
    }

    @Test
    @DisplayName("each window resets the burst allowance and summarizes the prior window")
    void window_resets_allowance() {
        WarningThrottle t = throttle(2, 1_000);
        for (int i = 0; i < 5; i++) fire(t); // window1: 2 emitted, 3 suppressed
        clock.addAndGet(1_001);
        for (int i = 0; i < 5; i++) fire(t); // window2: summary(3) then 2 emitted, 3 suppressed
        clock.addAndGet(1_001);
        fire(t);                             // window3: summary(3), then emitted
        assertThat(emitted).hasSize(5);      // 2 + 2 + 1
        assertThat(summaries).containsExactly(3L, 3L);
    }

    @Test
    @DisplayName("burst=0 suppresses everything but still reports the suppressed count")
    void burst_zero_still_summarizes() {
        WarningThrottle t = throttle(0, 60_000);
        for (int i = 0; i < 4; i++) fire(t);
        assertThat(emitted).isEmpty();
        clock.addAndGet(60_001);
        fire(t);                             // summary(4); the 5th is also suppressed (burst 0)
        assertThat(summaries).containsExactly(4L);
        assertThat(emitted).isEmpty();
    }

    @Test
    @DisplayName("rejects invalid burst / window")
    void rejects_invalid_args() {
        assertThatThrownBy(() -> new WarningThrottle(-1, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WarningThrottle(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
