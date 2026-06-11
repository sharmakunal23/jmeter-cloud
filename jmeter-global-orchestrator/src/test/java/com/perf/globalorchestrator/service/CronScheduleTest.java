package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.service.CronSchedule.InvalidCronException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTOMATION — unit tests for the cron parsing + next-fire math. Pure
 * functions, no Spring context: pins the 5-field/6-field/@macro normalisation,
 * timezone handling, catch-up-once semantics, and validation failures.
 */
@DisplayName("CronSchedule — parse + next-fire math")
class CronScheduleTest {

    @Test
    @DisplayName("5-field unix cron is normalised to 6-field (seconds prepended)")
    void fiveFieldUnix() {
        // "0 2 * * *" = 02:00 daily. From midnight UTC → 02:00 the same day.
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant next = CronSchedule.nextFireAfter("0 2 * * *", "UTC", from);
        assertThat(next).isEqualTo(Instant.parse("2026-01-01T02:00:00Z"));
    }

    @Test
    @DisplayName("6-field cron (with seconds) is accepted as-is and matches the 5-field form")
    void sixFieldEquivalent() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant unix = CronSchedule.nextFireAfter("0 2 * * *", "UTC", from);
        Instant sixField = CronSchedule.nextFireAfter("0 0 2 * * *", "UTC", from);
        assertThat(sixField).isEqualTo(unix);
    }

    @Test
    @DisplayName("@daily macro fires at midnight")
    void macro() {
        Instant from = Instant.parse("2026-01-01T06:00:00Z");
        Instant next = CronSchedule.nextFireAfter("@daily", "UTC", from);
        assertThat(next).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("next-fire is computed in the schedule's timezone, not UTC")
    void timezone() {
        // Noon in New York on a winter day = 17:00 UTC (EST = UTC-5).
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant next = CronSchedule.nextFireAfter("0 0 12 * * *", "America/New_York", from);
        assertThat(next).isEqualTo(Instant.parse("2026-01-01T17:00:00Z"));
    }

    @Test
    @DisplayName("catch-up-once: a 'from' well past the daily slot returns the NEXT future slot, not a backlog")
    void catchUpOnce() {
        // It's 05:00 — the 02:00 daily slot already passed today. The next fire
        // is tomorrow at 02:00 (one future slot), never a replay of today's.
        Instant from = Instant.parse("2026-01-01T05:00:00Z");
        Instant next = CronSchedule.nextFireAfter("0 2 * * *", "UTC", from);
        assertThat(next).isEqualTo(Instant.parse("2026-01-02T02:00:00Z"));
        assertThat(next).isAfter(from);
    }

    @Test
    @DisplayName("nextFires returns N strictly-increasing future fire times")
    void nextFires() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        List<Instant> fires = CronSchedule.nextFires("0 2 * * *", "UTC", from, 5);
        assertThat(fires).hasSize(5);
        assertThat(fires).isSorted();
        assertThat(fires.get(0)).isEqualTo(Instant.parse("2026-01-01T02:00:00Z"));
        assertThat(fires.get(4)).isEqualTo(Instant.parse("2026-01-05T02:00:00Z"));
    }

    @Test
    @DisplayName("validate rejects a malformed cron expression")
    void validateBadCron() {
        assertThatThrownBy(() -> CronSchedule.validate("not a cron", "UTC"))
                .isInstanceOf(InvalidCronException.class);
    }

    @Test
    @DisplayName("validate rejects a wrong field count (4 or 7 fields)")
    void validateFieldCount() {
        assertThatThrownBy(() -> CronSchedule.validate("0 2 * *", "UTC"))
                .isInstanceOf(InvalidCronException.class);
        assertThatThrownBy(() -> CronSchedule.validate("0 0 2 * * * *", "UTC"))
                .isInstanceOf(InvalidCronException.class);
    }

    @Test
    @DisplayName("validate rejects an unknown timezone")
    void validateBadZone() {
        assertThatThrownBy(() -> CronSchedule.validate("0 2 * * *", "Mars/Olympus"))
                .isInstanceOf(InvalidCronException.class)
                .hasMessageContaining("timeZone");
    }

    @Test
    @DisplayName("validate rejects an expression that can never fire (Feb 30)")
    void validateNeverFires() {
        assertThatThrownBy(() -> CronSchedule.validate("0 0 0 30 2 *", "UTC"))
                .isInstanceOf(InvalidCronException.class)
                .hasMessageContaining("no future fire");
    }

    @Test
    @DisplayName("blank expression is rejected")
    void validateBlank() {
        assertThatThrownBy(() -> CronSchedule.validate("  ", "UTC"))
                .isInstanceOf(InvalidCronException.class);
    }
}
