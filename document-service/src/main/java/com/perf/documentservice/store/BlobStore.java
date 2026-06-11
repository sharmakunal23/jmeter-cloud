package com.perf.documentservice.store;

import java.io.IOException;
import java.io.InputStream;

/**
 * Single contract for the document-service's storage backends.
 *
 * <p>Two implementations are planned:
 * <ul>
 *   <li>{@link LocalFsBlobStore} (default) — host-mounted directory under
 *       {@code DOCUMENT_SERVICE_LOCAL_FS_ROOT}. Always built.</li>
 *   <li>{@code S3BlobStore} (Step 13, {@code -Pcloud} profile) — pulls AWS
 *       SDK; not in the default JAR.</li>
 * </ul>
 *
 * <p>All methods operate on a server-issued {@code blobId} (ULID,
 * URL-safe). Implementations must never trust a caller-provided digest —
 * the sha256 in {@link BlobMetadata} is computed from the byte stream as
 * it lands.
 *
 * <p>Streams are passed through directly (no buffering in memory) so a
 * 512-MB data-files zip doesn't blow up the heap.
 */
public interface BlobStore {

    /**
     * Stores the bytes from {@code input} and returns the computed metadata.
     * Implementations must compute the sha256 + size during the read, not
     * by re-scanning the persisted bytes.
     *
     * @param input        the byte source — closed by the caller, not the store.
     * @param contentType  optional Content-Type header value to round-trip on GET. May be null.
     * @param name         optional human-readable label (Step 18 — surfaced in the UI dropdown).
     * @param description  optional free-form description (Step 18).
     * @param type         optional purpose tag (testPlan / dataFiles / result / other).
     * @param application  optional application tag (Step 28 — gates the launcher's downstream pickers).
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
     * Lists stored blobs filtered by {@code type} and / or {@code application}
     * (when non-null). Filters AND together. Pagination via {@code offset} +
     * {@code limit}; ordering is by {@code uploadedAt} DESC so the newest
     * blob lands first.
     *
     * @param typeFilter         filter to a single type tag, or {@code null} for all types.
     * @param applicationFilter  filter to a single application tag, or {@code null} for all apps.
     *                           Empty string matches blobs that have no application tag (legacy).
     * @param offset             number of items to skip.
     * @param limit              maximum items returned. Caller must clamp.
     */
    BlobListing list(String typeFilter, String applicationFilter,
                     int offset, int limit) throws IOException;

    /**
     * Returns distinct application tags across all stored blobs, with the
     * blob count per application. Sorted by {@code blobCount} DESC so the
     * busiest applications surface first. Blobs without an application
     * tag (legacy uploads pre-Step-28) appear under a {@code null} key.
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
     * Deletes a blob plus its metadata sidecar. Returns {@code true} if the
     * blob existed (and was removed) — {@code false} if it was already gone.
     * Idempotent on absent blobs; safe to call repeatedly.
     */
    boolean delete(String blobId) throws IOException;
}
