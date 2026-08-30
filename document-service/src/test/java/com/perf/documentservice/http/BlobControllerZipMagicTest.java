package com.perf.documentservice.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the upload-time ZIP magic gate for {@code dataFiles} AND {@code plugin}
 * uploads — a jar is a zip container, so one PK\x03\x04 check covers both.
 */
@DisplayName("BlobController.requireZipMagic")
class BlobControllerZipMagicTest {

    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04, 0x01, 0x02};

    @Test
    @DisplayName("a valid ZIP/JAR header passes and the magic bytes are unread")
    void validZipPasses() throws IOException {
        InputStream in = BlobController.requireZipMagic(new ByteArrayInputStream(ZIP), "plugin");
        assertThat(in.readAllBytes()).isEqualTo(ZIP);
    }

    @Test
    @DisplayName("a non-zip plugin upload is rejected with the type in the message")
    void nonZipPluginRejected() {
        byte[] text = "not a jar".getBytes();
        assertThatThrownBy(() -> BlobController.requireZipMagic(new ByteArrayInputStream(text), "plugin"))
                .hasMessageContaining("X-Type=plugin")
                .hasMessageContaining("ZIP/JAR");
    }

    @Test
    @DisplayName("an empty body is rejected")
    void emptyBodyRejected() {
        assertThatThrownBy(() -> BlobController.requireZipMagic(new ByteArrayInputStream(new byte[0]), "dataFiles"))
                .hasMessageContaining("empty body");
    }
}
