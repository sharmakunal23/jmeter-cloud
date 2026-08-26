package com.perf.k8sorchestrator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-function tests for {@link Actor#fromHeader}. */
class ActorTest {

    @Test
    void nullHeaderIsAnonymous() {
        Actor a = Actor.fromHeader(null);
        assertThat(a.name()).isEqualTo(Actor.ANONYMOUS);
        assertThat(a.source()).isEqualTo(Actor.SOURCE_ANONYMOUS);
    }

    @Test
    void emptyHeaderIsAnonymous() {
        Actor a = Actor.fromHeader("");
        assertThat(a.name()).isEqualTo(Actor.ANONYMOUS);
        assertThat(a.source()).isEqualTo(Actor.SOURCE_ANONYMOUS);
    }

    @Test
    void whitespaceOnlyHeaderIsAnonymous() {
        Actor a = Actor.fromHeader("   ");
        assertThat(a.name()).isEqualTo(Actor.ANONYMOUS);
        assertThat(a.source()).isEqualTo(Actor.SOURCE_ANONYMOUS);
    }

    @Test
    void suppliedHeaderIsSelfAttested() {
        Actor a = Actor.fromHeader("alice");
        assertThat(a.name()).isEqualTo("alice");
        assertThat(a.source()).isEqualTo(Actor.SOURCE_HEADER);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        // Proxies can pad header values; the trim keeps the audit actor and
        // the MDC actor (MdcEnrichmentFilter.resolveActor) byte-identical.
        Actor a = Actor.fromHeader("  bob  ");
        assertThat(a.name()).isEqualTo("bob");
        assertThat(a.source()).isEqualTo(Actor.SOURCE_HEADER);
    }
}
