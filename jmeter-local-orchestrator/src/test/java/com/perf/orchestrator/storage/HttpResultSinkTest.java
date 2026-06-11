package com.perf.orchestrator.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("HttpResultSink — no-op contract for RESULT_SINK=HTTP_UPLOAD")
class HttpResultSinkTest {

    private final HttpResultSink sink = new HttpResultSink();

    @Nested
    @DisplayName("when upload is called")
    class WhenUploadIsCalled {

        @Test
        @DisplayName("returns a noUpload outcome — JTL stays local; caller pulls it via GET /api/v1/results/file")
        void returns_no_upload_outcome() {
            UploadResult result = sink.upload("demo-app", "run-42", "jmeter-worker-0", Paths.get("/results/results.jtl"));

            assertSoftly(softly -> {
                softly.assertThat(result.skipped()).as("skipped flag — distinguishes from a real upload").isTrue();
                softly.assertThat(result.target()).as("no remote target for HTTP_UPLOAD sink").isEmpty();
                softly.assertThat(result.sizeBytes()).isEqualTo(0L);
                softly.assertThat(result.durationMs()).isEqualTo(0L);
            });
        }

        @Test
        @DisplayName("does not touch the file path it is given — never reads, opens, or stats the JTL")
        void does_not_touch_file() {
            // The path is intentionally fake — if the sink ever tried to read
            // or stat it, this would surface as an IOException at runtime.
            Path nonExistent = Paths.get("/no/such/file/results.jtl");

            assertThatCode(() -> sink.upload("demo-app", "run-1", "worker-0", nonExistent))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("never throws — the no-op terminal must be safe at COMPLETED for every run")
        void never_throws() {
            // ResultUploader (step 9) calls this on every COMPLETED transition.
            // A throw here would mark a successful test FAILED.
            assertThatCode(() -> sink.upload("demo-app", "run-1", "worker-0", Paths.get("/tmp/x.jtl")))
                    .doesNotThrowAnyException();
        }
    }
}
