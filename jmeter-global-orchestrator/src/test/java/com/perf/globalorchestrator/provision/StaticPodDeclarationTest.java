package com.perf.globalorchestrator.provision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StaticPodDeclaration — operator input that becomes a registry key and an HTTP target")
class StaticPodDeclarationTest {

    private static final String NAME = "payments-na-east-worker-1";
    private static final String URL  = "http://payments-na-east-worker-1.workers:8080";

    @Test
    void acceptsATypicalDeclaration() {
        StaticPodDeclaration d = StaticPodDeclaration.of(NAME, URL);
        assertThat(d.podName()).isEqualTo(NAME);
        assertThat(d.baseUrl()).isEqualTo(URL);
    }

    @Test
    @DisplayName("trims surrounding whitespace — operators paste these from a terminal")
    void trimsWhitespace() {
        StaticPodDeclaration d = StaticPodDeclaration.of("  " + NAME + " ", " " + URL + "  ");
        assertThat(d.podName()).isEqualTo(NAME);
        assertThat(d.baseUrl()).isEqualTo(URL);
    }

    @Test
    @DisplayName("normalises a trailing slash so the stored address matches what the client builds")
    void stripsTrailingSlash() {
        assertThat(StaticPodDeclaration.of(NAME, URL + "/").baseUrl()).isEqualTo(URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "worker1",
            "worker-1",
            "payments.na-east.worker_1",
            "a",
            "01worker"
    })
    @DisplayName("accepts real Kubernetes Pod / Docker container names")
    void acceptsRealisticNames(String name) {
        assertThat(StaticPodDeclaration.of(name, URL).podName()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-leadingHyphen",
            "trailingHyphen-",
            "has space",
            "has/slash",
            "has:colon",
            "..",
            "tab\there"
    })
    @DisplayName("rejects names that could never be a real pod — this value becomes a primary key "
            + "and must equal the workerId the worker stamps on its metrics")
    void rejectsMalformedNames(String name) {
        assertThatThrownBy(() -> StaticPodDeclaration.of(name, URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("podName");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> StaticPodDeclaration.of("   ", URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("podName is required");
    }

    @Test
    void rejectsOverlongName() {
        assertThatThrownBy(() -> StaticPodDeclaration.of("a".repeat(254), URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 253");
    }

    @Test
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> StaticPodDeclaration.of(NAME, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker-1:8080", "/api/v1", "worker-1"})
    @DisplayName("rejects a relative address — the control plane has no base to resolve it against")
    void rejectsRelativeUrl(String url) {
        assertThatThrownBy(() -> StaticPodDeclaration.of(NAME, url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://worker-1:8080",
            "gopher://worker-1:70",
            "jar:file:///tmp/x.jar!/"
    })
    @DisplayName("rejects non-HTTP schemes — this URL is one the control plane will fetch")
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> StaticPodDeclaration.of(NAME, url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects embedded credentials — the control plane does not authenticate this way, "
            + "and accepting them would persist a secret in the pod registry")
    void rejectsUserInfo() {
        assertThatThrownBy(() ->
                StaticPodDeclaration.of(NAME, "http://admin:hunter2@worker-1:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://worker-1:8080?x=1",
            "http://worker-1:8080#frag"
    })
    @DisplayName("rejects a query or fragment — it is a base address, not a request")
    void rejectsQueryAndFragment(String url) {
        assertThatThrownBy(() -> StaticPodDeclaration.of(NAME, url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongUrl() {
        assertThatThrownBy(() ->
                StaticPodDeclaration.of(NAME, "http://" + "a".repeat(520) + ":8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 512");
    }

    @Test
    @DisplayName("https and an explicit path are both fine — private clouds front workers oddly")
    void acceptsHttpsAndPath() {
        assertThat(StaticPodDeclaration.of(NAME, "https://gw.dc/workers/w1").baseUrl())
                .isEqualTo("https://gw.dc/workers/w1");
    }
}
