package com.perf.documentservice.store;

/**
 * Thrown when a backend is asked for an unknown {@code blobId};
 * {@link com.perf.documentservice.http.BlobController} maps it to 404
 * {@code BLOB_NOT_FOUND}.
 */
public class BlobNotFoundException extends RuntimeException {
    public BlobNotFoundException(String blobId) {
        super("blob not found: " + blobId);
    }
}
