package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser-cache rule on the per-run metrics reads: a finished run's numbers
 * are immutable, so the browser may keep them; a live run's change every window,
 * so it must not. Getting this backwards freezes a running test's charts in the
 * one place no server-side fix can reach.
 */
@DisplayName("RunController — browser cache headers follow terminal state")
class RunControllerCacheHeadersTest {

    private static Run runIn(RunState state) {
        return new Run("01JRUNCACHEHDR000000000001", "na-east", "plan", null,
                "checkout", "tester", state, null,
                Instant.parse("2026-08-31T10:00:00Z"), Instant.parse("2026-08-31T10:00:01Z"),
                state.isTerminal() ? Instant.parse("2026-08-31T10:05:00Z") : null,
                false, List.of());
    }

    private static String cacheControlOf(RunState state) {
        ResponseEntity<Object> resp = RunController.browserCacheable(runIn(state)).body(null);
        return resp.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RunState.class)
    @DisplayName("every state gets the header its mutability calls for")
    void headerFollowsTerminalState(RunState state) {
        String cacheControl = cacheControlOf(state);
        assertThat(cacheControl).isNotNull();
        if (state.isTerminal()) {
            assertThat(cacheControl)
                    .as("a finished run is immutable, so the browser may keep it — privately")
                    .contains("max-age=" + RunController.TERMINAL_RUN_BROWSER_CACHE_SECONDS)
                    .contains("private")
                    .doesNotContain("no-store");
        } else {
            assertThat(cacheControl)
                    .as("a live run's metrics move every window — caching them freezes the charts")
                    .contains("no-store");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the window is well under the server-side hour, because a purge cannot reach a browser cache")
    void windowIsShorterThanTheServerSideCache() {
        assertThat(RunController.TERMINAL_RUN_BROWSER_CACHE_SECONDS).isLessThan(3600L).isPositive();
    }
}
