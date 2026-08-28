package com.perf.globalorchestrator.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionPropertiesTest {

    @Test
    @DisplayName("id=url entries are routed, bare ids are direct, order is kept, duplicates keep the first")
    void parsesRoutedAndDirect() {
        RegionProperties p = new RegionProperties(
                " na-east=http://na-east-control-plane:30088/ , na-west , na-east=http://other ,, eu-1=https://eu ");

        assertThat(p.ids()).containsExactly("na-east", "na-west", "eu-1");
        assertThat(p.urlOf("na-east")).contains("http://na-east-control-plane:30088");
        assertThat(p.urlOf("na-west")).isEmpty();
        assertThat(p.urlOf("eu-1")).contains("https://eu");
        assertThat(p.urlOf("nope")).isEmpty();
        assertThat(p.routed()).containsOnlyKeys("na-east", "eu-1");
    }

    @Test
    void emptyMeansNoRegions() {
        assertThat(new RegionProperties("").ids()).isEmpty();
        assertThat(new RegionProperties(null).ids()).isEmpty();
    }

    @Test
    void anEntryWithoutAnIdIsRejected() {
        assertThatThrownBy(() -> new RegionProperties("=http://x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
