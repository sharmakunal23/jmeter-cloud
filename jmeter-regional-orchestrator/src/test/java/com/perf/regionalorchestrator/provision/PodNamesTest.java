package com.perf.regionalorchestrator.provision;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PodNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"payments-na-east-worker-1", "a", "w1", "abc-123"})
    void dns1123LabelsAreValid(String name) {
        assertThat(PodNames.isValid(name)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-leading", "trailing-", "Upper", "has.dot", "has_underscore",
            "has/slash", "has space", "host:8080", "..", "a-name-that-is-longer-than-sixty-three-characters-which-is-not-allowed-x"})
    void anythingElseIsRejected(String name) {
        assertThat(PodNames.isValid(name)).isFalse();
    }
}
