package com.perf.orchestrator.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("PrometheusExporter — text exposition format conformance")
class PrometheusExporterTest {

    @Nested
    @DisplayName("exposition format")
    class Format {

        @Test
        @DisplayName("emits HELP, TYPE, and value lines for every documented series")
        void emits_help_type_value_for_each_series() {
            String body = PrometheusExporter.render(sample(7L, 3L, 2L, 1700000000000L, 4096L, 5_000_000_000L, 5L));

            assertSoftly(softly -> {
                softly.assertThat(body).contains("# HELP orchestrator_rows_parsed_total");
                softly.assertThat(body).contains("# TYPE orchestrator_rows_parsed_total counter");
                softly.assertThat(body).contains("orchestrator_rows_parsed_total 7");

                softly.assertThat(body).contains("# TYPE orchestrator_kafka_last_ack_epoch_ms gauge");
                softly.assertThat(body).contains("orchestrator_kafka_last_ack_epoch_ms 1700000000000");

                softly.assertThat(body).contains("orchestrator_disk_free_bytes 5000000000");

                softly.assertThat(body).contains("# HELP orchestrator_offset_save_failures_total");
                softly.assertThat(body).contains("# TYPE orchestrator_offset_save_failures_total counter");
                softly.assertThat(body).contains("orchestrator_offset_save_failures_total 5");
            });
        }

        @Test
        @DisplayName("every metric has exactly one HELP, one TYPE, and one value line — no duplicates")
        void no_duplicate_series() {
            String body = PrometheusExporter.render(sample(0, 0, 0, 0, 0, 0, 0));
            String[] expectedNames = {
                    "orchestrator_rows_parsed_total",
                    "orchestrator_windows_published_total",
                    "orchestrator_kafka_send_errors_total",
                    "orchestrator_kafka_last_ack_epoch_ms",
                    "orchestrator_upload_inflight_bytes",
                    "orchestrator_disk_free_bytes",
                    "orchestrator_offset_save_failures_total",
            };
            for (String name : expectedNames) {
                assertSoftly(softly -> {
                    softly.assertThat(occurrences(body, "# HELP " + name)).as("HELP " + name).isEqualTo(1);
                    softly.assertThat(occurrences(body, "# TYPE " + name)).as("TYPE " + name).isEqualTo(1);
                    // The value line is the metric name followed by space + a digit.
                    softly.assertThat(matches(body, "(?m)^" + Pattern.quote(name) + " \\d"))
                            .as("value line " + name).isEqualTo(1);
                });
            }
        }

        @Test
        @DisplayName("conforms to the Prometheus 0.0.4 line ordering: HELP, then TYPE, then value")
        void help_type_value_ordering() {
            String body = PrometheusExporter.render(sample(0, 0, 0, 0, 0, 0, 0));
            String name = "orchestrator_rows_parsed_total";

            int help  = body.indexOf("# HELP " + name);
            int type  = body.indexOf("# TYPE " + name);
            int value = body.indexOf("\n" + name + " ");

            assertSoftly(softly -> {
                softly.assertThat(help).isGreaterThanOrEqualTo(0);
                softly.assertThat(type).isGreaterThan(help);
                softly.assertThat(value).isGreaterThan(type);
            });
        }

        @Test
        @DisplayName("ends every line with \\n — Prometheus parsers reject CRLF or no-trailing-newline")
        void newline_terminated() {
            String body = PrometheusExporter.render(sample(1, 0, 0, 0, 0, 0, 0));

            assertThat(body).doesNotContain("\r");
            assertThat(body).endsWith("\n");
        }

        @Test
        @DisplayName("declares the documented Prometheus content type — text/plain; version=0.0.4")
        void content_type_string_is_correct() {
            assertThat(PrometheusExporter.CONTENT_TYPE).isEqualTo("text/plain; version=0.0.4");
        }
    }

    private static OrchestratorCounters sample(long rows, long windows, long errors,
                                               long lastAck, long inflight, long disk,
                                               long offsetSaveFailures) {
        return new OrchestratorCounters(
                rows, windows, errors, lastAck, inflight, disk, offsetSaveFailures);
    }

    private static int occurrences(String hay, String needle) {
        int n = 0, idx = 0;
        while ((idx = hay.indexOf(needle, idx)) != -1) { n++; idx += needle.length(); }
        return n;
    }

    private static int matches(String hay, String regex) {
        Matcher m = Pattern.compile(regex).matcher(hay);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
