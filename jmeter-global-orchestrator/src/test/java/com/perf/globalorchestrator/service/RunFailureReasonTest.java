package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.MemberState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run-level reason when every worker refuses. "all fan-outs rejected" is
 * the message an operator actually sees first, and on its own it names no
 * cause — the members already know why, so the run must say so too.
 */
@DisplayName("RunService — a wholly-rejected launch says WHY")
class RunFailureReasonTest {

    @SuppressWarnings("unchecked")
    private static String reasonFor(Map<String, RunService.FanoutOutcome> outcomes) throws Exception {
        Method m = RunService.class.getDeclaredMethod("allRejectedReason", Map.class);
        m.setAccessible(true);
        return (String) m.invoke(null, outcomes);
    }

    private static Map<String, RunService.FanoutOutcome> outcomes(String... reasons) {
        Map<String, RunService.FanoutOutcome> out = new LinkedHashMap<>();
        for (int i = 0; i < reasons.length; i++) {
            out.put("w" + i, new RunService.FanoutOutcome(MemberState.FAILED, 502, reasons[i]));
        }
        return out;
    }

    @Test
    @DisplayName("workers agreeing on one cause: that cause IS the diagnosis")
    void oneSharedCause() throws Exception {
        String r = reasonFor(outcomes(
                "{\"error\":\"ARTIFACT_FETCH_FAILED\",\"message\":\"could not fetch testPlan blob 01ABC\"}",
                "{\"error\":\"ARTIFACT_FETCH_FAILED\",\"message\":\"could not fetch testPlan blob 01ABC\"}"));

        assertThat(r).contains("all 2 fan-out(s) rejected")
                     .contains("ARTIFACT_FETCH_FAILED")
                     .contains("01ABC");
    }

    @Test
    @DisplayName("workers disagreeing is itself the signal — say how many, and name one")
    void differingCauses() throws Exception {
        String r = reasonFor(outcomes("disk full", "connection refused"));

        assertThat(r).contains("2 different reasons").contains("disk full");
    }

    @Test
    @DisplayName("no reason at all still produces a usable line, never a null or a crash")
    void noReasons() throws Exception {
        assertThat(reasonFor(outcomes(null, ""))).isEqualTo("all 2 fan-out(s) rejected");
    }

    @Test
    @DisplayName("a worker's raw error body cannot overflow STATE_REASON and fail the state write")
    void longReasonIsClipped() throws Exception {
        String r = reasonFor(outcomes("x".repeat(9000)));
        assertThat(r).hasSizeLessThanOrEqualTo(4000).endsWith("…");
    }
}
