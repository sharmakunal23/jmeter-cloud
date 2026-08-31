package com.perf.documentservice.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code If-None-Match} parsing for the blob read. Getting it wrong is
 * expensive in one direction and silently wrong in the other: too strict and
 * every conditional request re-sends the whole body, too loose and a client is
 * told "not modified" about bytes it has never seen.
 */
@DisplayName("BlobController.matchesEtag — RFC 9110 If-None-Match")
class BlobControllerEtagTest {

    private static final String ETAG = "\"abc123\"";

    @Test
    @DisplayName("absent / blank never matches — an unconditional GET must send the body")
    void absentDoesNotMatch() {
        assertThat(BlobController.matchesEtag(null, ETAG)).isFalse();
        assertThat(BlobController.matchesEtag("", ETAG)).isFalse();
        assertThat(BlobController.matchesEtag("   ", ETAG)).isFalse();
    }

    @Test
    @DisplayName("the exact tag matches; a different one does not")
    void exactTag() {
        assertThat(BlobController.matchesEtag(ETAG, ETAG)).isTrue();
        assertThat(BlobController.matchesEtag("\"other\"", ETAG)).isFalse();
    }

    @Test
    @DisplayName("* matches anything — the RFC's any-representation wildcard")
    void wildcard() {
        assertThat(BlobController.matchesEtag("*", ETAG)).isTrue();
        assertThat(BlobController.matchesEtag("  *  ", ETAG)).isTrue();
    }

    @Test
    @DisplayName("a comma-separated list matches on any member, with or without spaces")
    void listOfTags() {
        assertThat(BlobController.matchesEtag("\"x\", \"abc123\", \"y\"", ETAG)).isTrue();
        assertThat(BlobController.matchesEtag("\"x\",\"abc123\"", ETAG)).isTrue();
        assertThat(BlobController.matchesEtag("\"x\", \"y\"", ETAG)).isFalse();
    }

    @Test
    @DisplayName("a W/ weak prefix still matches — weak comparison is the right one for a GET")
    void weakPrefix() {
        assertThat(BlobController.matchesEtag("W/\"abc123\"", ETAG)).isTrue();
        assertThat(BlobController.matchesEtag("\"x\", W/\"abc123\"", ETAG)).isTrue();
    }

    @Test
    @DisplayName("a quoteless tag does not match — the quotes are part of the value")
    void quotelessDoesNotMatch() {
        assertThat(BlobController.matchesEtag("abc123", ETAG)).isFalse();
    }
}
