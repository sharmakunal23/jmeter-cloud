package com.perf.documentservice.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S3-backed {@link BlobStore} for cloud deployments, using {@code blobId} (ULID)
 * directly as the object key and SSE-S3 ({@code AES256}) at rest.
 *
 * <p>{@link #put} stages the stream to a local temp file before
 * {@code PutObject}, because S3 needs {@code Content-Length} up front while the
 * {@link BlobStore} contract forbids buffering the body in memory. That also
 * yields the exact size and sha256 for the user-metadata block, with heap use
 * bounded to the 8-KiB transfer buffer.
 *
 * <p>Single-part upload caps a blob at 5 GB, which covers every artifact this
 * platform handles; multi-part is not implemented.
 */
@Component
@ConditionalOnProperty(name = "documentService.backend", havingValue = "s3")
public class S3BlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(S3BlobStore.class);

    /** S3 user-metadata key for the client-computed sha256. Lowercase per AWS spec. */
    private static final String META_SHA256      = "sha256";
    /** S3 user-metadata key for the upload Instant (ISO-8601). */
    private static final String META_UPLOADED_AT = "uploadedat";
    /** UI tagging fields. AWS folds user-metadata keys to lowercase. */
    private static final String META_NAME        = "name";
    private static final String META_DESCRIPTION = "description";
    private static final String META_TYPE        = "type";
    /** Application tag gating the launcher's downstream pickers. */
    private static final String META_APPLICATION = "application";

    private final S3Client s3;
    private final String bucket;

    public S3BlobStore(S3Client s3,
                       @Value("${documentService.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
        LOG.info("S3BlobStore configured against bucket={}", bucket);
    }

    @Override
    public BlobMetadata put(InputStream input, String contentType,
                            String name, String description, String type,
                            String application) throws IOException {
        String blobId = Ulid.generate();
        Path tmp = Files.createTempFile("docsvc-", ".blob");
        try {
            // Stage to disk so we can hash + size the bytes once, send to
            // S3 with Content-Length, and never buffer >8 KiB in memory.
            MessageDigest sha = newSha256();
            long size;
            try (DigestInputStream dis = new DigestInputStream(input, sha)) {
                size = Files.copy(dis, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = hex(sha.digest());
            Instant uploadedAt = Instant.now();

            Map<String, String> userMeta = new HashMap<>();
            userMeta.put(META_SHA256, sha256);
            userMeta.put(META_UPLOADED_AT, uploadedAt.toString());
            if (name        != null) userMeta.put(META_NAME, name);
            if (description != null) userMeta.put(META_DESCRIPTION, description);
            if (type        != null) userMeta.put(META_TYPE, type);
            if (application != null) userMeta.put(META_APPLICATION, application);

            PutObjectRequest.Builder req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(blobId)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .metadata(userMeta);
            if (contentType != null) {
                req.contentType(contentType);
            }
            s3.putObject(req.build(), RequestBody.fromFile(tmp));

            return BlobMetadata.of(blobId, size, sha256, contentType, uploadedAt,
                    name, description, type, application);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public BlobListing list(String typeFilter, String applicationFilter,
                            int offset, int limit) throws IOException {
        // ListObjectsV2 → page through every key in the bucket; the
        // listing returns size + lastModified but NOT user-metadata, so
        // we HEAD each key to fetch sha256/name/description/type. That
        // makes listing O(N) HEAD requests; acceptable up to a few
        // hundred blobs. Larger deployments would push the metadata
        // into a sibling index (DynamoDB / a small RDS table) — see
        // the cloud-migration plan.
        List<BlobMetadata> all = new ArrayList<>();
        String continuation = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket);
            if (continuation != null) req.continuationToken(continuation);
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());
            for (S3Object obj : resp.contents()) {
                BlobMetadata m;
                try {
                    m = stat(obj.key());
                } catch (BlobNotFoundException | IOException e) {
                    LOG.warn("Skipping {} during list: {}", obj.key(), e.toString());
                    continue;
                }
                if (typeFilter != null && !typeFilter.equals(m.type())) continue;
                if (applicationFilter != null) {
                    if (applicationFilter.isEmpty()) {
                        if (m.application() != null) continue;
                    } else if (!applicationFilter.equals(m.application())) {
                        continue;
                    }
                }
                all.add(m);
            }
            continuation = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (continuation != null);

        all.sort(Comparator.comparing(
                BlobMetadata::uploadedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = all.size();
        int from = Math.max(0, Math.min(offset, total));
        int to   = Math.max(from, Math.min(from + limit, total));
        return new BlobListing(new ArrayList<>(all.subList(from, to)), total, offset, limit);
    }

    @Override
    public InputStream open(String blobId) throws IOException {
        validate(blobId);
        try {
            return s3.getObject(b -> b.bucket(bucket).key(blobId));
        } catch (NoSuchKeyException e) {
            throw new BlobNotFoundException(blobId);
        }
    }

    @Override
    public BlobMetadata stat(String blobId) throws IOException {
        validate(blobId);
        HeadObjectResponse head;
        try {
            head = s3.headObject(b -> b.bucket(bucket).key(blobId));
        } catch (NoSuchKeyException e) {
            throw new BlobNotFoundException(blobId);
        }
        Map<String, String> userMeta = head.metadata();
        Instant uploadedAt;
        String uploadedAtStr = userMeta.get(META_UPLOADED_AT);
        if (uploadedAtStr != null) {
            uploadedAt = Instant.parse(uploadedAtStr);
        } else {
            // Object existed before the user-metadata convention — fall
            // back to S3's last-modified.
            uploadedAt = head.lastModified();
        }
        return new BlobMetadata(
                blobId,
                head.contentLength(),
                userMeta.get(META_SHA256),
                head.contentType(),
                uploadedAt,
                null,
                userMeta.get(META_NAME),
                userMeta.get(META_DESCRIPTION),
                userMeta.get(META_TYPE),
                userMeta.get(META_APPLICATION));
    }

    @Override
    public List<ApplicationSummary> listApplications() throws IOException {
        // Aggregate over a full listing — same scaling caveat as
        // LocalFsBlobStore.listApplications: a sibling index would
        // be needed past a few thousand blobs.
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        String continuation = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket);
            if (continuation != null) req.continuationToken(continuation);
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());
            for (S3Object obj : resp.contents()) {
                try {
                    BlobMetadata m = stat(obj.key());
                    counts.merge(m.application(), 1L, Long::sum);
                } catch (BlobNotFoundException | IOException ignored) {
                    // Tolerate transient mismatch between listing + HEAD —
                    // a concurrently-deleted key drops out of the rollup.
                }
            }
            continuation = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (continuation != null);
        List<ApplicationSummary> rows = new ArrayList<>(counts.size());
        counts.forEach((app, n) -> rows.add(new ApplicationSummary(app, n)));
        rows.sort((a, b) -> {
            if (a.application() == null && b.application() != null) return 1;
            if (a.application() != null && b.application() == null) return -1;
            int byCount = Long.compare(b.blobCount(), a.blobCount());
            if (byCount != 0) return byCount;
            if (a.application() == null) return 0;
            return a.application().compareTo(b.application());
        });
        return rows;
    }

    @Override
    public boolean delete(String blobId) throws IOException {
        validate(blobId);
        // S3 DeleteObject is idempotent — succeeds whether the key exists
        // or not. To preserve the BlobStore contract's "did this exist?"
        // boolean, probe with HEAD first.
        boolean existed;
        try {
            s3.headObject(b -> b.bucket(bucket).key(blobId));
            existed = true;
        } catch (NoSuchKeyException e) {
            existed = false;
        }
        s3.deleteObject(b -> b.bucket(bucket).key(blobId));
        return existed;
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static void validate(String blobId) {
        if (!Ulid.isValid(blobId)) {
            // Reject early — a malformed key shouldn't get a real S3 round-trip.
            throw new BlobNotFoundException(blobId);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
