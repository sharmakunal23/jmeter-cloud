package com.perf.documentservice.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The blob-mount readiness check: a present, writable root with space is UP; anything else is DOWN with the reason. */
class StorageHealthIndicatorTest {

    @TempDir Path root;

    @Test
    void up_when_the_root_is_writable_and_has_space() {
        Health h = new StorageHealthIndicator(root.toString(), 1).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKeys("usableBytes", "minFreeBytes", "rootPath");
    }

    @Test
    void down_when_the_root_is_missing_or_too_full() {
        assertThat(new StorageHealthIndicator(root.resolve("missing").toString(), 1).health().getStatus()).isEqualTo(Status.DOWN);
        Health full = new StorageHealthIndicator(root.toString(), Long.MAX_VALUE).health();
        assertThat(full.getStatus()).isEqualTo(Status.DOWN);
        assertThat(full.getDetails()).containsEntry("reason", "usable space below minFreeBytes");
    }
}
