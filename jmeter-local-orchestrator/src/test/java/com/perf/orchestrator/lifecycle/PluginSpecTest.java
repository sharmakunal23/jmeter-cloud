package com.perf.orchestrator.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A plugin spec is path- and shell-safe before it can reach the filesystem or argv. */
class PluginSpecTest {

    private static final String ULID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Test
    @DisplayName("a jar and a zip bundle are accepted")
    void valid() {
        assertThat(new PluginSpec(ULID, "jpgc-casutg.jar").isBundle()).isFalse();
        assertThat(new PluginSpec(ULID, "casutg-bundle.zip").isBundle()).isTrue();
    }

    @Test
    @DisplayName("a malformed blobId is rejected")
    void badBlobId() {
        assertThatThrownBy(() -> new PluginSpec("not-a-ulid", "a.jar"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blobId");
        assertThatThrownBy(() -> new PluginSpec("01arz3ndektsv4rrffq69g5fav", "a.jar")) // lower case
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blobId");
        assertThatThrownBy(() -> new PluginSpec(null, "a.jar"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blobId");
    }

    @Test
    @DisplayName("path separators, bad extensions and leading punctuation are rejected")
    void badFileName() {
        assertThatThrownBy(() -> new PluginSpec(ULID, "../evil.jar"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileName");
        assertThatThrownBy(() -> new PluginSpec(ULID, "lib/evil.jar"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileName");
        assertThatThrownBy(() -> new PluginSpec(ULID, "lib\\evil.jar"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileName");
        assertThatThrownBy(() -> new PluginSpec(ULID, "evil.sh"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(".jar");
        assertThatThrownBy(() -> new PluginSpec(ULID, ".hidden.jar"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileName");
        assertThatThrownBy(() -> new PluginSpec(ULID, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileName");
    }

    @Test
    @DisplayName("StartTestRequest tolerates a null plugins list and copies a supplied one")
    void startTestRequestPlugins() {
        StartTestRequest none = new StartTestRequest("r1", null, null, null, null,
                List.of(), List.of(), Map.of(), null, null, null, null, null, null, null, null, null);
        assertThat(none.plugins()).isEmpty();

        StartTestRequest some = new StartTestRequest("r1", null, null, null, null,
                List.of(), List.of(), Map.of(), null, null, null, null, null, null, null, null,
                List.of(new PluginSpec(ULID, "a.jar")));
        assertThat(some.plugins()).hasSize(1);
        assertThat(some.plugins().get(0).blobId()).isEqualTo(ULID);
    }
}
