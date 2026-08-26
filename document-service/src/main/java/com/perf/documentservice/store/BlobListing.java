package com.perf.documentservice.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of {@link BlobStore#list} results.
 *
 * <p>{@code total} counts matches before paging, or {@code -1} when the backend
 * cannot count without a full scan (S3) — the UI renders "1-50 of many" for -1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlobListing(
        List<BlobMetadata> items,
        int total,
        int offset,
        int limit) {
}
