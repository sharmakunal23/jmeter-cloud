package com.perf.documentservice.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Immutable description of a stored blob — what every backend records and
 * every caller sees back from POST / GET-metadata.
 *
 * <p>{@code blobId} is server-issued (ULID, URL-safe). {@code sha256} is
 * computed from the byte stream during upload via {@code DigestInputStream}
 * — never trust a caller-provided digest. {@code contentType} is preserved
 * verbatim from the upload's {@code Content-Type} header so a GET can
 * round-trip it.
 *
 * <p><b>Step 18 added {@code name}, {@code description}, and {@code type}.</b>
 * They make the blob discoverable in the UI's launcher dropdown without
 * the operator memorising ULIDs. {@code type} groups blobs by purpose
 * (testPlan / dataFiles / result / other); {@code name} is the human-
 * readable label; {@code description} is free-form.
 *
 * <p><b>Step 28 added {@code application}.</b> At 30+ applications the
 * flat dropdown becomes unusable; tagging each blob with an application
 * lets the launcher gate downstream pickers behind an Application
 * picker and filter the listing to just the chosen app's artifacts.
 *
 * <p>All four tagging fields are supplied by the uploader via
 * {@code X-Name} / {@code X-Description} / {@code X-Type} /
 * {@code X-Application} request headers and stored as backend-side
 * metadata (filesystem sidecar / S3 user-metadata).
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

    /** Convenience for the common case where a backend produces a metadata record. */
    public static BlobMetadata of(String blobId, long sizeBytes, String sha256,
                                  String contentType, Instant uploadedAt) {
        return new BlobMetadata(blobId, sizeBytes, sha256, contentType, uploadedAt,
                null, null, null, null, null);
    }

    /** Step 18 / 28 convenience — including the tagging fields. */
    public static BlobMetadata of(String blobId, long sizeBytes, String sha256,
                                  String contentType, Instant uploadedAt,
                                  String name, String description, String type,
                                  String application) {
        return new BlobMetadata(blobId, sizeBytes, sha256, contentType, uploadedAt,
                null, name, description, type, application);
    }
}
