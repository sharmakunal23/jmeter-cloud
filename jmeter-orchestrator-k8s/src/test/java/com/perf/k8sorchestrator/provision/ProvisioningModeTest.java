package com.perf.k8sorchestrator.provision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProvisioningMode — defaults to STATIC and fails loudly on a typo")
class ProvisioningModeTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("unset / blank means STATIC — the platform default since 2026-07-27, when "
            + "operator-managed fleets became the norm rather than the exception")
    void unsetDefaultsToStatic(String raw) {
        assertThat(ProvisioningMode.parse(raw)).isEqualTo(ProvisioningMode.STATIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATIC", "static", "Static", "  static  "})
    @DisplayName("case-insensitive and trimmed — operators set this by hand in env files")
    void parsesStaticLeniently(String raw) {
        assertThat(ProvisioningMode.parse(raw)).isEqualTo(ProvisioningMode.STATIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DYNAMIC", "dynamic", " Dynamic "})
    void parsesDynamicLeniently(String raw) {
        assertThat(ProvisioningMode.parse(raw)).isEqualTo(ProvisioningMode.DYNAMIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"statics", "STATIC_FLEET", "off", "true", "manual"})
    @DisplayName("an unrecognised value throws rather than defaulting — a typo must not silently "
            + "arm the provisioner against a fleet the operator believes is protected")
    void rejectsUnknownValue(String raw) {
        assertThatThrownBy(() -> ProvisioningMode.parse(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ProvisioningMode.PROPERTY)
                .hasMessageContaining(raw);
    }

    @Test
    void predicatesAreMutuallyExclusive() {
        assertThat(ProvisioningMode.DYNAMIC.isDynamic()).isTrue();
        assertThat(ProvisioningMode.DYNAMIC.isStatic()).isFalse();
        assertThat(ProvisioningMode.STATIC.isStatic()).isTrue();
        assertThat(ProvisioningMode.STATIC.isDynamic()).isFalse();
    }
}
