package com.perf.orchestrator.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FetchSpec")
class FetchSpecTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("of(runId) produces a spec with no params — convenient default for HTTP mode")
        void of_factory_produces_empty_params() {
            FetchSpec spec = FetchSpec.of("run-1");

            assertThat(spec.runId()).isEqualTo("run-1");
            assertThat(spec.params()).isEmpty();
        }

        @Test
        @DisplayName("rejects null runId — every test artifact is tagged with the run it belongs to")
        void rejects_null_run_id() {
            assertThatThrownBy(() -> new FetchSpec(null, Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null params — callers must pass Map.of() rather than null")
        void rejects_null_params() {
            assertThatThrownBy(() -> new FetchSpec("run-1", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("defensively copies the params map — caller mutations do not leak into the spec")
        void params_are_defensively_copied() {
            // FetchSpec is shared across threads (run loop reads it; controllers
            // build it). A mutable map here would be a thread-safety bug.
            Map<String, String> mutable = new HashMap<>();
            mutable.put("documentId", "d-1");

            FetchSpec spec = new FetchSpec("run-1", mutable);
            mutable.put("documentId", "d-2");
            mutable.put("extra", "added-after-construction");

            assertThat(spec.params())
                    .containsEntry("documentId", "d-1")
                    .doesNotContainKey("extra");
        }
    }
}
