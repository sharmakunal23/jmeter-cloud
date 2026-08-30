package com.perf.orchestrator.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Unit-tests {@link S3ArtifactSource} against an in-memory {@link S3Client}
 * stub. A real LocalStack-backed integration test is left for an `*IT.java`
 * follow-up under {@code mvn -Pstorage-s3 verify}; the unit coverage here
 * pins the URL parsing, kind→param mapping, and error-translation contracts.
 */
@DisplayName("S3ArtifactSource — URL parsing + kind mapping + error translation")
class S3ArtifactSourceTest {

    @Nested
    @DisplayName("URL parsing")
    class UrlParsing {

        @Test
        @DisplayName("splits s3://bucket/key/with/slashes into bucket + full key")
        void parses_well_formed_url() throws Exception {
            S3ArtifactSource.S3Location loc =
                    S3ArtifactSource.parseS3Url("s3://perf-bucket/plans/checkout-flow.jmx");

            assertSoftly(softly -> {
                softly.assertThat(loc.bucket()).isEqualTo("perf-bucket");
                softly.assertThat(loc.key()).isEqualTo("plans/checkout-flow.jmx");
            });
        }

        @Test
        @DisplayName("rejects URLs without the s3:// scheme — would silently miss the right bucket")
        void rejects_non_s3_scheme() {
            assertThatThrownBy(() -> S3ArtifactSource.parseS3Url("https://perf-bucket/key"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("s3://");
        }

        @Test
        @DisplayName("rejects URLs missing a bucket or key — fail at parse, not on the SDK call")
        void rejects_incomplete_url() {
            assertThatThrownBy(() -> S3ArtifactSource.parseS3Url("s3://"))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> S3ArtifactSource.parseS3Url("s3://bucket-only"))
                    .isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("fetch() kind → param mapping")
    class KindMapping {

        @Test
        @DisplayName("KIND_TEST_PLAN reads the testPlanS3Url param")
        void test_plan_kind_reads_test_plan_url() throws Exception {
            AtomicReference<GetObjectRequest> seen = new AtomicReference<>();
            S3ArtifactSource source = new S3ArtifactSource(stubClient(seen, bytes("plan-bytes")));

            Optional<InputStream> result = source.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of(
                            "testPlanS3Url",  "s3://perf-bucket/plans/checkout.jmx",
                            "dataFilesS3Url", "s3://perf-bucket/data/checkout.zip")));

            assertThat(result).isPresent();
            assertSoftly(softly -> {
                softly.assertThat(seen.get().bucket()).isEqualTo("perf-bucket");
                softly.assertThat(seen.get().key())
                        .as("testPlan kind must look at testPlanS3Url, not dataFilesS3Url")
                        .isEqualTo("plans/checkout.jmx");
            });
        }

        @Test
        @DisplayName("KIND_DATA_FILES reads the dataFilesS3Url param")
        void data_files_kind_reads_data_files_url() throws Exception {
            AtomicReference<GetObjectRequest> seen = new AtomicReference<>();
            S3ArtifactSource source = new S3ArtifactSource(stubClient(seen, bytes("zip-bytes")));

            source.fetch(ArtifactSource.KIND_DATA_FILES,
                    new FetchSpec("run-1", Map.of(
                            "testPlanS3Url",  "s3://perf-bucket/plans/checkout.jmx",
                            "dataFilesS3Url", "s3://perf-bucket/data/checkout.zip")));

            assertThat(seen.get().key()).isEqualTo("data/checkout.zip");
        }

        @Test
        @DisplayName("returns empty when the param for the requested kind is missing — falls back to local upload")
        void empty_when_param_missing() throws Exception {
            S3ArtifactSource source = new S3ArtifactSource(stubClient(new AtomicReference<>(), bytes("x")));

            Optional<InputStream> result = source.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of()));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("rejects an unknown kind — programmer error, surface immediately")
        void unknown_kind_throws() {
            S3ArtifactSource source = new S3ArtifactSource(stubClient(new AtomicReference<>(), bytes("x")));

            assertThatThrownBy(() -> source.fetch("WHATEVER", new FetchSpec("r", Map.of())))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Unknown artifact kind");
        }
    }

    @Nested
    @DisplayName("error translation")
    class ErrorTranslation {

        @Test
        @DisplayName("a 404 from S3 surfaces as IOException with the bucket+key in the message — easier ops triage")
        void no_such_key_becomes_ioexception() {
            S3ArtifactSource source = new S3ArtifactSource(throwingClient(
                    NoSuchKeyException.builder().message("not found").build()));

            assertThatThrownBy(() -> source.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("r", Map.of("testPlanS3Url", "s3://b/k"))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("s3://b/k");
        }

        @Test
        @DisplayName("a non-404 S3 error surfaces as IOException with the underlying status code")
        void non_404_surfaces_status_in_message() {
            S3ArtifactSource source = new S3ArtifactSource(throwingClient(
                    S3Exception.builder()
                            .statusCode(500)
                            .awsErrorDetails(AwsErrorDetails.builder()
                                    .errorMessage("internal").build())
                            .build()));

            assertThatThrownBy(() -> source.fetch(
                    ArtifactSource.KIND_DATA_FILES,
                    new FetchSpec("r", Map.of("dataFilesS3Url", "s3://b/k"))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 500");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — minimal S3Client stubs. We only care about the getObject(...)
    // overload with a ResponseTransformer; the rest of the interface is
    // unused. AssertJ catches any other call as an UnsupportedOperationException.
    // -----------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static S3Client stubClient(AtomicReference<GetObjectRequest> seen, byte[] body) {
        return new MinimalS3Stub() {
            @Override
            public <T> T getObject(GetObjectRequest req,
                                   ResponseTransformer<GetObjectResponse, T> transformer) {
                throw unsupportedAlt();
            }

            @Override
            public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest req) {
                seen.set(req);
                GetObjectResponse meta = GetObjectResponse.builder()
                        .contentLength((long) body.length)
                        .build();
                return new ResponseInputStream<>(meta, AbortableInputStream.create(new ByteArrayInputStream(body)));
            }
        };
    }

    private static S3Client throwingClient(RuntimeException toThrow) {
        return new MinimalS3Stub() {
            @Override
            public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest req) {
                throw toThrow;
            }
        };
    }

    /** Override only the getObject method we need — every other method throws. */
    private static abstract class MinimalS3Stub implements S3Client {
        @Override public String serviceName() { return "s3"; }
        @Override public void close() { }
        protected static UnsupportedOperationException unsupportedAlt() {
            return new UnsupportedOperationException("test stub does not implement this S3Client overload");
        }
    }
}
