package com.perf.orchestrator.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("UploadResult")
class UploadResultTest {

    @Nested
    @DisplayName("when constructed via factories")
    class WhenConstructedViaFactories {

        @Test
        @DisplayName("noUpload() carries empty target and zero counters — represents AUTO_UPLOAD_RESULTS=false")
        void no_upload_factory_produces_zero_state() {
            UploadResult r = UploadResult.noUpload();

            assertSoftly(softly -> {
                softly.assertThat(r.skipped()).isTrue();
                softly.assertThat(r.target()).isEmpty();
                softly.assertThat(r.sizeBytes()).isZero();
                softly.assertThat(r.durationMs()).isZero();
            });
        }

        @Test
        @DisplayName("uploaded() carries the target the operator will see in GET /api/v1/results")
        void uploaded_factory_carries_target_and_counters() {
            UploadResult r = UploadResult.uploaded("doc-service://documents/d-123", 1_048_576L, 87L);

            assertSoftly(softly -> {
                softly.assertThat(r.skipped()).isFalse();
                softly.assertThat(r.target()).isEqualTo("doc-service://documents/d-123");
                softly.assertThat(r.sizeBytes()).isEqualTo(1_048_576L);
                softly.assertThat(r.durationMs()).isEqualTo(87L);
            });
        }
    }

    @Nested
    @DisplayName("construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("rejects negative sizeBytes — uploads cannot have negative size")
        void rejects_negative_size() {
            assertThatThrownBy(() -> new UploadResult("x", -1L, 0L, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative durationMs — clock skew should not produce negative durations")
        void rejects_negative_duration() {
            assertThatThrownBy(() -> new UploadResult("x", 0L, -1L, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null target — callers must pass an empty string for the skipped case")
        void rejects_null_target() {
            assertThatThrownBy(() -> new UploadResult(null, 0L, 0L, true))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two noUpload results compare equal — record semantics enable test fixture reuse")
        void two_no_upload_results_compare_equal() {
            assertThat(UploadResult.noUpload()).isEqualTo(UploadResult.noUpload());
        }
    }
}
