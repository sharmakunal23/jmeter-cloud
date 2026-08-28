package com.perf.globalorchestrator.provision;

import org.junit.jupiter.api.DisplayName;
import com.perf.globalorchestrator.region.RegionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProvisioningProperties — resolved posture the UI and the guards both read")
class ProvisioningPropertiesTest {

    @Test
    @DisplayName("defaults: DYNAMIC, no region override, 'region' vocabulary")
    void defaults() {
        ProvisioningProperties props = new ProvisioningProperties("DYNAMIC", new RegionProperties(""));
        assertThat(props.mode()).isEqualTo(ProvisioningMode.DYNAMIC);
        assertThat(props.isDynamic()).isTrue();
        assertThat(props.isStatic()).isFalse();
        assertThat(props.regions()).isEmpty();
        assertThat(props.regionLabel()).isEqualTo("region");
    }

    @Test
    @DisplayName("static mode flips the UI vocabulary to dataCenter (API/schema keep saying region)")
    void staticModeUsesDataCenterVocabulary() {
        ProvisioningProperties props = new ProvisioningProperties("STATIC", new RegionProperties("na-east,na-west"));
        assertThat(props.isStatic()).isTrue();
        assertThat(props.regionLabel()).isEqualTo("dataCenter");
        assertThat(props.regions()).containsExactly("na-east", "na-west");
    }

    @Test
    @DisplayName("region list is trimmed, blank-free, deduplicated, and order-preserving")
    void parsesRegionList() {
        ProvisioningProperties props =
                new ProvisioningProperties("STATIC", new RegionProperties(" na-east , ,na-west,na-east ,"));
        assertThat(props.regions()).containsExactly("na-east", "na-west");
    }

    @Test
    void regionsAreImmutable() {
        ProvisioningProperties props = new ProvisioningProperties("STATIC", new RegionProperties("na-east"));
        assertThatThrownBy(() -> props.regions().add("sneaky"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("requireDynamic is a no-op in dynamic mode and 409-shaped in static mode")
    void requireDynamicGuard() {
        assertThatCode(() -> new ProvisioningProperties("DYNAMIC", new RegionProperties("")).requireDynamic("spin a worker"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                new ProvisioningProperties("STATIC", new RegionProperties("")).requireDynamic("spin a worker"))
                .isInstanceOf(ProvisioningDisabledException.class)
                .hasMessageContaining("cannot spin a worker")
                .hasMessageContaining("operator-managed");
    }

    @Test
    @DisplayName("an unparseable mode fails construction — the boot must not come up half-armed")
    void unparseableModeFailsFast() {
        assertThatThrownBy(() -> new ProvisioningProperties("statics", new RegionProperties("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledExceptionBodyCarriesTheContract() {
        ProvisioningDisabledException e = new ProvisioningDisabledException("restart worker w-1");
        assertThat(e.toBody())
                .containsEntry("code", ProvisioningDisabledException.CODE)
                .containsEntry("action", "restart worker w-1")
                .containsEntry("provisioningMode", "STATIC")
                .containsKey("message");
    }
}
