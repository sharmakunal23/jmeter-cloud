package com.perf.documentservice.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Immutable description of a stored blob, returned from every upload and
 * metadata read.
 *
 * <p>{@code blobId} is server-issued (ULID) and {@code sha256} is computed from
 * the byte stream during upload — a caller-supplied digest is never trusted.
 * The four tagging fields arrive as {@code X-Name} / {@code X-Description} /
 * {@code X-Type} / {@code X-Application} request headers and are stored
 * backend-side (filesystem sidecar, or S3 user-metadata).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlobMetadata(
        String blobId,
        long sizeBytes,
        String sha256,
        String contentType,
        Instant uploadedAt,
        String owner,
        String name,
        String description,
        String type,
        String application) {

    /** Untagged blob — the four tagging fields are left null. */
    public static BlobMetadata of(String blobId, long sizeBytes, String sha256,
                                  String contentType, Instant uploadedAt) {
        return new BlobMetadata(blobId, sizeBytes, sha256, contentType, uploadedAt,
                null, null, null, null, null);
    }

    /** Tagged blob, as uploaded through {@code BlobController}. */
    public static BlobMetadata of(String blobId, long sizeBytes, String sha256,
                                  String contentType, Instant uploadedAt,
                                  String name, String description, String type,
                                  String application) {
        return new BlobMetadata(blobId, sizeBytes, sha256, contentType, uploadedAt,
                null, name, description, type, application);
    }
}
