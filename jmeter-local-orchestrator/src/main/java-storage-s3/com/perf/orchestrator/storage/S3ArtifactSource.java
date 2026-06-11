package com.perf.orchestrator.storage;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ArtifactSource} backed by AWS S3. Compiled only under
 * {@code -Pstorage-s3} so the default fat JAR doesn't carry the AWS SDK.
 *
 * <h2>Authentication</h2>
 * Uses the AWS SDK's default credentials provider chain — IAM instance
 * profile on EC2, IRSA in EKS, environment vars in dev. Credentials are
 * never read from the {@code POST /test} body.
 *
 * <h2>Per-run S3 URLs</h2>
 * The {@link FetchSpec} carries opaque per-backend keys; this source
 * looks at:
 * <ul>
 *   <li>{@code "testPlanS3Url"} for {@link #KIND_TEST_PLAN}</li>
 *   <li>{@code "dataFilesS3Url"} for {@link #KIND_DATA_FILES}</li>
 * </ul>
 * URLs use the {@code s3://bucket/key} form. A missing slot returns
 * {@link Optional#empty()} — that is normal control flow, not an error.
 *
 * <h2>S3 not as a sink</h2>
 * Per {@code ORCHESTRATOR-PLAN.md} §"Storage Backends", S3 is intentionally
 * not implemented as a {@link ResultSink}. {@code RESULT_SINK=S3} is
 * rejected at startup by {@code OrchestratorConfig}.
 */
public final class S3ArtifactSource implements ArtifactSource, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(S3ArtifactSource.class);

    private final S3Client client;

    public S3ArtifactSource(OrchestratorConfig config) {
        this(buildClient(config));
    }

    /** Test seam — production callers use the public constructor. */
    S3ArtifactSource(S3Client client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Optional<InputStream> fetch(String kind, FetchSpec spec) throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(spec, "spec");

        String urlKey = switch (kind) {
            case KIND_TEST_PLAN  -> "testPlanS3Url";
            case KIND_DATA_FILES -> "dataFilesS3Url";
            default -> throw new IOException("Unknown artifact kind: " + kind);
        };

        String url = spec.params().get(urlKey);
        if (url == null || url.isBlank()) {
            // No URL means "not configured for this kind" — let the caller
            // fall back to the locally-staged file.
            return Optional.empty();
        }

        S3Location loc = parseS3Url(url);
        LOG.info("Fetching {} from s3://{}/{}", kind, loc.bucket(), loc.key());

        try {
            ResponseInputStream<GetObjectResponse> body = client.getObject(GetObjectRequest.builder()
                    .bucket(loc.bucket())
                    .key(loc.key())
                    .build());
            return Optional.of(body);
        } catch (NoSuchKeyException nske) {
            // 404 from S3 — surface as IOException so the caller sees the
            // same failure mode as a network outage. Don't pretend the
            // artifact "isn't configured" — it's misconfigured.
            throw new IOException("S3 object not found: s3://" + loc.bucket() + "/" + loc.key(), nske);
        } catch (S3Exception s3) {
            throw new IOException("S3 fetch failed for s3://" + loc.bucket() + "/" + loc.key()
                    + " (HTTP " + s3.statusCode() + "): " + s3.awsErrorDetails().errorMessage(), s3);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    // -----------------------------------------------------------------------
    // S3 URL parsing — package-private so tests can pin the contract.
    // -----------------------------------------------------------------------

    record S3Location(String bucket, String key) { }

    static S3Location parseS3Url(String s3Url) throws IOException {
        if (s3Url == null || !s3Url.startsWith("s3://")) {
            throw new IOException("S3 URL must start with s3://, got: " + s3Url);
        }
        URI uri;
        try {
            uri = URI.create(s3Url);
        } catch (IllegalArgumentException iae) {
            throw new IOException("Malformed S3 URL: " + s3Url, iae);
        }
        String bucket = uri.getHost();
        String path   = uri.getPath();
        if (bucket == null || bucket.isBlank()) {
            throw new IOException("S3 URL is missing the bucket: " + s3Url);
        }
        if (path == null || path.length() <= 1) {
            throw new IOException("S3 URL is missing the key: " + s3Url);
        }
        return new S3Location(bucket, path.substring(1));
    }

    private static S3Client buildClient(OrchestratorConfig config) {
        S3ClientBuilder b = S3Client.builder();
        if (config.getS3Region() != null && !config.getS3Region().isBlank()) {
            b.region(Region.of(config.getS3Region()));
        }
        // No credentials override — the SDK's default chain handles
        // IRSA / instance-profile / env-var auth.
        return b.build();
    }
}
