package com.perf.orchestrator.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("HttpArtifactSource — no-op contract for ARTIFACT_SOURCE=HTTP_UPLOAD")
class HttpArtifactSourceTest {

    private final HttpArtifactSource source = new HttpArtifactSource();

    @Nested
    @DisplayName("when fetch is called")
    class WhenFetchIsCalled {

        @ParameterizedTest(name = "returns empty for kind={0} — files arrive over HTTP, not pulled from this source")
        @ValueSource(strings = {ArtifactSource.KIND_TEST_PLAN, ArtifactSource.KIND_DATA_FILES})
        void returns_empty_for_each_artifact_kind(String kind) throws Exception {
            // The orchestrator's run loop reads HTTP-uploaded artifacts straight
            // from disk; this source has no remote system to consult, so empty
            // is the contract — never an exception, never a stream.
            Optional<InputStream> result = source.fetch(kind, FetchSpec.of("run-1"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty even when params are populated — opaque keys are ignored by HTTP mode")
        void returns_empty_regardless_of_params() throws Exception {
            FetchSpec spec = new FetchSpec("run-1", Map.of("s3Url", "s3://bucket/key", "documentId", "d-9"));

            assertThat(source.fetch(ArtifactSource.KIND_TEST_PLAN, spec)).isEmpty();
        }

        @Test
        @DisplayName("never throws — the no-op path must be safe to invoke from the hot lifecycle code")
        void never_throws() {
            // TestRunManager (step 7) calls fetch() unconditionally on PREPARING.
            // A throw from this default would crash a test that hasn't even
            // started; that would be a regression worth catching here.
            assertThatCode(() -> source.fetch(ArtifactSource.KIND_TEST_PLAN, FetchSpec.of("run-1")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> source.fetch(ArtifactSource.KIND_DATA_FILES, FetchSpec.of("run-1")))
                    .doesNotThrowAnyException();
        }
    }
}
