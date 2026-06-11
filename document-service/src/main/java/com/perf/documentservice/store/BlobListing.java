package com.perf.documentservice.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Page of blob metadata returned by {@link BlobStore#list}.
 *
 * <p>{@code total} is a count of items matching the filter <em>before</em>
 * paging — useful for UI pagination ("page 2 of 17"). For backends where
 * counting requires a full scan (S3), implementations may return
 * {@code -1} to mean "unknown"; the UI then renders "1-50 of many".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlobListing(
        List<BlobMetadata> items,
        int total,
        int offset,
        int limit) {
}
