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

    /** Bodyless failures — a status code and nothing else, as a dead relay produces. */
    private static Map<String, RunService.FanoutOutcome> codedOutcomes(int... codes) {
        Map<String, RunService.FanoutOutcome> out = new LinkedHashMap<>();
        for (int i = 0; i < codes.length; i++) {
            out.put("w" + i, new RunService.FanoutOutcome(MemberState.FAILED, codes[i], null));
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
    @DisplayName("no reason and no status code still produces a usable line, never a null or a crash")
    void noReasons() throws Exception {
        assertThat(reasonFor(codedOutcomes(0, 0))).isEqualTo("all 2 fan-out(s) rejected");
    }

    @Test
    @DisplayName("a bodyless transport failure names the status code — 'all fan-outs rejected' alone points nowhere")
    void statusCodeWhenNoBody() throws Exception {
        assertThat(reasonFor(codedOutcomes(502, 502)))
                .isEqualTo("all 2 fan-out(s) rejected — HTTP 502 from every worker with no reason body");
        assertThat(reasonFor(codedOutcomes(502, 503)))
                .contains("HTTP 502/503").contains("no reason body");
    }

    @Test
    @DisplayName("a worker's raw error body is passed through whole — the column clamp is the repository's")
    void longReasonIsNotClippedHere() throws Exception {
        // Bounding STATE_REASON belongs to RunRepository (OracleBind.text), which
        // every write of the column goes through. Clamping here as well means two
        // clamps, and the wrong one gets fixed. OracleBindTest owns the width.
        String r = reasonFor(outcomes("x".repeat(9000)));
        assertThat(r).hasSizeGreaterThan(4000).contains("all 1 fan-out(s) rejected");
    }
}
