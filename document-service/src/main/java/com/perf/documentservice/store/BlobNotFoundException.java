package com.perf.documentservice.store;

/**
 * Thrown when a backend is asked for a {@code blobId} it doesn't know.
 * The HTTP layer translates this to 404 NOT_FOUND in
 * {@code GlobalExceptionHandler}.
 */
public class BlobNotFoundException extends RuntimeException {
    public BlobNotFoundException(String blobId) {
        super("blob not found: " + blobId);
    }
}
