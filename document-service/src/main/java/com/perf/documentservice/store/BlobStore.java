package com.perf.documentservice.store;

import java.io.IOException;
import java.io.InputStream;

/**
 * Storage contract for the document-service, implemented by
 * {@link LocalFsBlobStore} (default) and {@code S3BlobStore} ({@code -Pcloud}
 * only — the AWS SDK is not in the default JAR).
 *
 * <p>Every method keys off a server-issued {@code blobId} (ULID, URL-safe) and
 * streams bytes through without buffering, so a 512-MB data-files zip never
 * lands in the heap. Implementations compute the {@link BlobMetadata} sha256
 * from the stream as it arrives — a caller-supplied digest is never trusted.
 */
public interface BlobStore {

    /**
     * Stores the bytes from {@code input} and returns the computed metadata.
     * Compute sha256 and size during the read, never by re-scanning the
     * persisted bytes.
     *
     * @param input        byte source — closed by the caller, not the store.
     * @param contentType  Content-Type to round-trip on GET. Nullable.
     * @param name         human-readable label shown in the UI dropdown. Nullable.
     * @param description  free-form description. Nullable.
     * @param type         purpose tag: testPlan / dataFiles / result / other. Nullable.
     * @param application  application tag gating the launcher's pickers. Nullable.
     * @return server-issued metadata including the new {@code blobId}.
     */
    BlobMetadata put(InputStream input, String contentType,
                     String name, String description, String type,
                     String application) throws IOException;

    /** Convenience for callers that don't care about tagging. */
    default BlobMetadata put(InputStream input, String contentType) throws IOException {
        return put(input, contentType, null, null, null, null);
    }

    /**
     * Lists blobs matching every non-null filter, newest {@code uploadedAt}
     * first.
     *
     * @param typeFilter         a single type tag, or {@code null} for all.
     * @param applicationFilter  a single application tag, or {@code null} for all.
     *                           <b>Empty string</b> matches only untagged blobs.
     * @param offset             items to skip.
     * @param limit              maximum items returned. The caller must clamp this.
     */
    BlobListing list(String typeFilter, String applicationFilter,
                     int offset, int limit) throws IOException;

    /**
     * Returns each distinct application tag with its blob count, busiest first.
     * Untagged blobs appear under a {@code null} key.
     */
    java.util.List<ApplicationSummary> listApplications() throws IOException;

    /** One row in {@link #listApplications()}. */
    record ApplicationSummary(String application, long blobCount) {}

    /**
     * Opens the blob's bytes for streaming. Caller must close the stream.
     *
     * @throws BlobNotFoundException if {@code blobId} is unknown.
     */
    InputStream open(String blobId) throws IOException;

    /**
     * Returns the metadata for an existing blob without opening its bytes.
     *
     * @throws BlobNotFoundException if {@code blobId} is unknown.
     */
    BlobMetadata stat(String blobId) throws IOException;

    /**
     * Deletes a blob and its metadata, returning whether it existed. Idempotent
     * — calling it on an absent blob is not an error.
     */
    boolean delete(String blobId) throws IOException;
}
