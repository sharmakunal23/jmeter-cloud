package com.perf.orchestrator.parser;

import com.perf.orchestrator.model.JtlRow;
import com.perf.orchestrator.observability.WarningThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("JtlRowParser")
class JtlRowParserTest {

    static final String STANDARD_HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName," +
            "dataType,success,failureMessage,bytes,sentBytes,grpThreads," +
            "allThreads,URL,Latency,IdleTime,Connect";

    // A valid row matching the standard header column order
    static final String VALID_ROW =
            "2025/04/13 14:32:07,187,POST /api/payment,200,OK," +
            "jmeter-worker-0 1-1,text,true,,1024,512,80,80," +
            "https://app/api/payment,185,0,12";

    private JtlRowParser parser;

    @BeforeEach
    void setUp() {
        parser = new JtlRowParser(ColumnIndex.parse(STANDARD_HEADER));
    }

    // -----------------------------------------------------------------------
    // Correct field mapping behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("field mapping")
    class FieldMapping {

        @Test
        @DisplayName("maps all fields from a valid row to the correct JtlRow fields")
        void maps_all_fields_correctly() {
            JtlRow row = parser.parse(VALID_ROW).orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(row.rawTimestamp()).isEqualTo("2025/04/13 14:32:07");
                softly.assertThat(row.elapsedMs()).isEqualTo(187L);
                softly.assertThat(row.label()).isEqualTo("POST /api/payment");
                softly.assertThat(row.responseCode()).isEqualTo("200");
                softly.assertThat(row.threadName()).isEqualTo("jmeter-worker-0 1-1");
                softly.assertThat(row.success()).isTrue();
                softly.assertThat(row.bytes()).isEqualTo(1024L);
                softly.assertThat(row.sentBytes()).isEqualTo(512L);
                softly.assertThat(row.grpThreads()).isEqualTo(80);
                softly.assertThat(row.allThreads()).isEqualTo(80);
                softly.assertThat(row.latencyMs()).isEqualTo(185L);
                softly.assertThat(row.idleTimeMs()).isEqualTo(0L);
                softly.assertThat(row.connectMs()).isEqualTo(12L);
            });
        }

        @Test
        @DisplayName("derives the exact UTC epoch second for a known timestamp — catches timezone bugs")
        void derives_exact_utc_epoch_second_for_known_timestamp() {
            // 2025-01-01T00:00:00Z = 1_735_689_600
            // + 102 days (Jan:31 + Feb:28 + Mar:31 + Apr 1-12:12) = 8_812_800
            // + 14h32m07s = 52_327
            // = 1_744_554_727
            JtlRow row = parser.parse(VALID_ROW).orElseThrow();

            assertThat(row.epochSecond())
                    .as("2025/04/13 14:32:07 UTC must equal exactly 1_744_554_727")
                    .isEqualTo(1_744_554_727L);
        }

        @Test
        @DisplayName("derives a positive epochSecond from the timestamp string")
        void derives_positive_epoch_second() {
            JtlRow row = parser.parse(VALID_ROW).orElseThrow();

            assertThat(row.epochSecond())
                    .as("epoch second must be positive and sensible (post-2020)")
                    .isGreaterThan(1_577_836_800L); // 2020-01-01 in epoch seconds
        }

        @Test
        @DisplayName("maps success=false rows correctly — success field is the authoritative error signal")
        void maps_failed_row_correctly() {
            String failedRow =
                    "2025/04/13 14:32:07,4200,POST /api/payment,503,Service Unavailable," +
                    "jmeter-worker-0 1-1,text,false,Response code was 503,128,512,80,80," +
                    "https://app/api/payment,4198,0,9";

            JtlRow row = parser.parse(failedRow).orElseThrow();

            assertThat(row.success()).isFalse();
            assertThat(row.isError()).isTrue();
            assertThat(row.elapsedMs()).isEqualTo(4200L);
            assertThat(row.failureMessage()).isEqualTo("Response code was 503");
        }
    }

    // -----------------------------------------------------------------------
    // Timestamp caching behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("timestamp caching")
    class TimestampCaching {

        @Test
        @DisplayName("produces the same epochSecond for all rows sharing the same timestamp string")
        void same_timestamp_string_produces_same_epoch_second() {
            // All rows within one second carry the identical timestamp string.
            // The cache must produce a consistent epochSecond for all of them.
            String row1 = VALID_ROW;
            String row2 = VALID_ROW.replace(",187,", ",203,"); // different elapsed, same timestamp

            long epoch1 = parser.parse(row1).orElseThrow().epochSecond();
            long epoch2 = parser.parse(row2).orElseThrow().epochSecond();

            assertThat(epoch1).isEqualTo(epoch2);
        }

        @Test
        @DisplayName("advances epochSecond when the timestamp string changes to the next second")
        void epoch_second_advances_with_timestamp() {
            String secondOne = VALID_ROW; // 14:32:07
            String secondTwo = VALID_ROW.replace("2025/04/13 14:32:07", "2025/04/13 14:32:08");

            long epoch1 = parser.parse(secondOne).orElseThrow().epochSecond();
            long epoch2 = parser.parse(secondTwo).orElseThrow().epochSecond();

            assertThat(epoch2).isEqualTo(epoch1 + 1);
        }
    }

    // -----------------------------------------------------------------------
    // Quoted field handling (failureMessage with commas)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("quoted field handling")
    class QuotedFieldHandling {

        @Test
        @DisplayName("correctly parses a row where failureMessage contains a comma")
        void parses_failure_message_containing_comma() {
            // JMeter wraps failureMessage in quotes when it contains a comma.
            // A naive split(",") would produce too many fields and drop the row.
            String rowWithCommaInMessage =
                    "2025/04/13 14:32:07,4200,POST /api/payment,503,Service Unavailable," +
                    "jmeter-worker-0 1-1,text,false," +
                    "\"Expected 200, but got 503\"," +    // quoted failureMessage
                    "128,512,80,80,https://app/api/payment,4198,0,9";

            Optional<JtlRow> result = parser.parse(rowWithCommaInMessage);

            assertThat(result).isPresent();
            assertThat(result.get().failureMessage()).isEqualTo("Expected 200, but got 503");
        }

        @Test
        @DisplayName("correctly unescapes double-quote sequences within a quoted field")
        void unescapes_double_quotes_in_quoted_field() {
            // RFC 4180: "" inside a quoted field represents a literal "
            String rowWithEscapedQuotes =
                    "2025/04/13 14:32:07,200,GET /api/data,200,OK," +
                    "jmeter-worker-0 1-1,text,true," +
                    "\"Body contained \"\"special\"\" chars\"," +
                    "1024,512,80,80,https://app/api/data,180,0,5";

            Optional<JtlRow> result = parser.parse(rowWithEscapedQuotes);

            assertThat(result).isPresent();
            assertThat(result.get().failureMessage())
                    .isEqualTo("Body contained \"special\" chars");
        }
    }

    // -----------------------------------------------------------------------
    // Non-HTTP response code robustness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("non-HTTP response code rows")
    class NonHttpResponseCode {

        @Test
        @DisplayName("parses a row with a JMeter connection-error response code without throwing")
        void parses_connection_error_row_without_throwing() {
            // JMeter writes strings like "Non HTTP response code: java.net.SocketTimeoutException"
            // when a TCP connection fails. These are valid JTL rows — they must not be dropped.
            String connectionErrorRow =
                    "2025/04/13 14:32:07,30000,POST /api/payment," +
                    "\"Non HTTP response code: java.net.SocketTimeoutException\"," +
                    "\"Non HTTP response message: Read timed out\"," +
                    "jmeter-worker-0 1-1,text,false," +
                    "\"java.net.SocketTimeoutException: Read timed out\"," +
                    "0,512,80,80,https://app/api/payment,29999,0,0";

            Optional<JtlRow> result = parser.parse(connectionErrorRow);

            assertThat(result).isPresent();
            assertThat(result.get().isError()).isTrue();
            assertThat(result.get().isHttpClientError()).isFalse();
            assertThat(result.get().isHttpServerError()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Timestamp timezone behaviour (B6)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("timestamp timezone interpretation")
    class TimestampTimezone {

        @Test
        @DisplayName("default UTC parser produces the correct epoch second for a known timestamp")
        void utc_parser_produces_correct_epoch_second() {
            // 2025/04/13 14:32:07 UTC = 1_744_554_727 (verified independently)
            JtlRow row = parser.parse(VALID_ROW).orElseThrow();

            assertThat(row.epochSecond()).isEqualTo(1_744_554_727L);
        }

        @Test
        @DisplayName("parser with a fixed UTC-4 offset produces epoch 4 hours ahead of UTC equivalent")
        void non_utc_parser_adjusts_epoch_correctly() {
            // Use a fixed offset (UTC-4) rather than America/New_York to avoid
            // DST ambiguity — America/New_York is UTC-4 in summer but UTC-5 in
            // winter, so a test running in January would compute a different
            // expected value than the same test running in July.
            // Fixed offsets (ZoneOffset.ofHours(-4)) are always UTC-4, no DST.
            JtlRowParser fixedOffsetParser = new JtlRowParser(
                    ColumnIndex.parse(STANDARD_HEADER),
                    java.time.ZoneOffset.ofHours(-4));

            JtlRow row = fixedOffsetParser.parse(VALID_ROW).orElseThrow();

            // When the timestamp "2025/04/13 14:32:07" is interpreted as UTC-4,
            // it represents 18:32:07 UTC — 4 hours (14,400s) later than UTC interpretation.
            long utcEpoch      = 1_744_554_727L;
            long expectedEpoch = utcEpoch + 4 * 3600;
            assertThat(row.epochSecond())
                    .as("UTC-4 interpretation of the same timestamp must yield epoch 14,400s later than UTC")
                    .isEqualTo(expectedEpoch);
        }
    }

    // -----------------------------------------------------------------------
    // parseBoolean strict behaviour (B13)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("parseBoolean strict behaviour")
    class ParseBooleanStrict {

        @Test
        @DisplayName("parses lowercase 'true' as success=true")
        void parses_lowercase_true() {
            JtlRow row = parser.parse(VALID_ROW).orElseThrow(); // success field is "true"

            assertThat(row.success()).isTrue();
        }

        @Test
        @DisplayName("parses lowercase 'false' as success=false")
        void parses_lowercase_false() {
            String failedRow = VALID_ROW.replace(",true,", ",false,");

            JtlRow row = parser.parse(failedRow).orElseThrow();

            assertThat(row.success()).isFalse();
        }

        @Test
        @DisplayName("treats non-standard value like 'TRUE' as false — JMeter always writes lowercase")
        void non_standard_value_treated_as_false() {
            // JMeter always writes 'true' or 'false' in lowercase.
            // Any other value (e.g. 'TRUE', '1', 'yes') indicates a format
            // anomaly and is conservatively treated as false (request failed).
            String rowWithUppercase = VALID_ROW.replace(",true,", ",TRUE,");

            JtlRow row = parser.parse(rowWithUppercase).orElseThrow();

            assertThat(row.success())
                    .as("non-lowercase 'TRUE' must be treated as false — flag format anomaly conservatively")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("malformed row resilience")
    class MalformedRowResilience {

        @Test
        @DisplayName("returns empty for a null line without throwing")
        void handles_null_line() {
            assertThat(parser.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for a blank line without throwing")
        void handles_blank_line() {
            assertThat(parser.parse("   ")).isEmpty();
        }

        @Test
        @DisplayName("returns empty for the header line if accidentally re-read")
        void handles_header_line_as_data() {
            // If the file pointer is reset to zero (e.g. after a crash recovery bug),
            // the parser must skip the header quietly rather than crashing.
            assertThat(parser.parse(STANDARD_HEADER)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for a row with too few fields without throwing")
        void handles_row_with_too_few_fields() {
            String truncatedRow = "2025/04/13 14:32:07,187,POST /api/payment";

            assertThat(parser.parse(truncatedRow)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for a row with a non-numeric elapsed without throwing")
        void handles_non_numeric_elapsed() {
            // Corrupted row where elapsed is not a number
            String badElapsed = VALID_ROW.replace(",187,", ",CORRUPT,");

            assertThat(parser.parse(badElapsed)).isEmpty();
        }

        @Test
        @DisplayName("continues to parse subsequent valid rows after encountering a malformed one")
        void recovers_and_parses_after_malformed_row() {
            // The parser is stateless per call — a bad row must not leave it in
            // a broken state that affects the next valid row.
            parser.parse("this,is,completely,wrong");
            Optional<JtlRow> result = parser.parse(VALID_ROW);

            assertThat(result).isPresent();
            assertThat(result.get().label()).isEqualTo("POST /api/payment");
        }
    }

    // -----------------------------------------------------------------------
    // Warning rate-limiting (a systematic format mismatch must not flood logs)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("warning rate-limiting")
    class WarningRateLimiting {

        @Test
        @DisplayName("a flood of field-count-mismatch rows emits a bounded number of warnings, not one per row")
        void field_count_flood_is_rate_limited() {
            java.util.logging.Logger log =
                    java.util.logging.Logger.getLogger(JtlRowParser.class.getName());
            java.util.List<java.util.logging.LogRecord> warnings = new java.util.ArrayList<>();
            java.util.logging.Handler handler = new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord r) {
                    if (r.getLevel() == java.util.logging.Level.WARNING) warnings.add(r);
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            java.util.logging.Level prior = log.getLevel();
            log.addHandler(handler);
            log.setLevel(java.util.logging.Level.ALL);
            try {
                // 5000 rows that all fail the field-count check (1 field vs 17).
                for (int i = 0; i < 5_000; i++) {
                    assertThat(parser.parse("onlyOneField" + i)).isEmpty();
                }
            } finally {
                log.removeHandler(handler);
                log.setLevel(prior);
            }
            // The default throttle emits the first DEFAULT_BURST in each 60s
            // window; the test runs well within one window, so the count is the
            // burst — NOT 5000.
            assertThat(warnings)
                    .as("5000 malformed rows must not produce 5000 warnings")
                    .hasSizeLessThanOrEqualTo(WarningThrottle.DEFAULT_BURST)
                    .isNotEmpty();
        }
    }
}
