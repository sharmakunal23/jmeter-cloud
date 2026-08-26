package com.perf.documentservice.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the server-side blob-description cap, which must hold independently
 * because the UI's {@code maxLength} is bypassable by a direct caller.
 */
@DisplayName("BlobController.sanitizeDescription")
class BlobControllerSanitizeTest {

    @Test
    @DisplayName("null / blank → null")
    void nullAndBlank() {
        assertThat(BlobController.sanitizeDescription(null)).isNull();
        assertThat(BlobController.sanitizeDescription("   ")).isNull();
    }

    @Test
    @DisplayName("trims surrounding whitespace")
    void trims() {
        assertThat(BlobController.sanitizeDescription("  hi  ")).isEqualTo("hi");
    }

    @Test
    @DisplayName("exactly 200 chars is kept; 201+ is truncated to 200")
    void caps() {
        String exactly200 = "x".repeat(200);
        assertThat(BlobController.sanitizeDescription(exactly200)).hasSize(200);

        String tooLong = "y".repeat(5000);
        assertThat(BlobController.sanitizeDescription(tooLong)).hasSize(200);
    }
}
