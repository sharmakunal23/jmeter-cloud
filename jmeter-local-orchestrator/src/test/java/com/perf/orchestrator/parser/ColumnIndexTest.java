package com.perf.orchestrator.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ColumnIndex")
class ColumnIndexTest {

    static final String STANDARD_HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName," +
            "dataType,success,failureMessage,bytes,sentBytes,grpThreads," +
            "allThreads,URL,Latency,IdleTime,Connect";

    // -----------------------------------------------------------------------
    // Column lookup behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("column position lookup")
    class ColumnPositionLookup {

        @Test
        @DisplayName("resolves each column to its correct zero-based position in the standard header")
        void resolves_standard_jmeter_column_positions() {
            ColumnIndex idx = ColumnIndex.parse(STANDARD_HEADER);

            // Spot-check key positions — these are the columns the aggregator
            // accesses on every row, so getting them wrong is catastrophic.
            assertThat(idx.indexOf("timeStamp")).isEqualTo(0);
            assertThat(idx.indexOf("elapsed")).isEqualTo(1);
            assertThat(idx.indexOf("label")).isEqualTo(2);
            assertThat(idx.indexOf("success")).isEqualTo(7);
            assertThat(idx.indexOf("URL")).isEqualTo(13);
            assertThat(idx.indexOf("Latency")).isEqualTo(14);
            assertThat(idx.indexOf("Connect")).isEqualTo(16);
        }

        @Test
        @DisplayName("correctly counts 17 columns in the standard JMeter header")
        void column_count_matches_standard_header() {
            ColumnIndex idx = ColumnIndex.parse(STANDARD_HEADER);
            assertThat(idx.columnCount()).isEqualTo(17);
        }

        @Test
        @DisplayName("throws a descriptive exception when a caller requests a column absent from the header")
        void throws_descriptive_error_on_unknown_column_request() {
            ColumnIndex idx = ColumnIndex.parse(STANDARD_HEADER);

            assertThatThrownBy(() -> idx.indexOf("nonExistentColumn"))
                    .isInstanceOf(ColumnIndexException.class)
                    .hasMessageContaining("nonExistentColumn");
        }
    }

    // -----------------------------------------------------------------------
    // Resilience to column order variation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when column order differs from the standard")
    class WhenColumnOrderDiffers {

        @Test
        @DisplayName("resolves columns by name regardless of their position in the header")
        void resolves_by_name_not_by_position() {
            // Some JMeter configurations write columns in a different order.
            // The index must be position-agnostic — it reads position from
            // the actual header, not from a hardcoded assumption.
            String reorderedHeader =
                    "label,timeStamp,elapsed,responseCode,responseMessage,threadName," +
                    "dataType,success,failureMessage,bytes,sentBytes,grpThreads," +
                    "allThreads,URL,Latency,IdleTime,Connect";

            ColumnIndex idx = ColumnIndex.parse(reorderedHeader);

            assertThat(idx.indexOf("label")).isEqualTo(0);
            assertThat(idx.indexOf("timeStamp")).isEqualTo(1);
            assertThat(idx.indexOf("elapsed")).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts headers with extra columns at the end — JMeter may add plugin columns")
        void accepts_extra_columns_after_required_ones() {
            String extendedHeader = STANDARD_HEADER + ",customPlugin,anotherPlugin";

            ColumnIndex idx = ColumnIndex.parse(extendedHeader);

            // Required columns still resolve correctly
            assertThat(idx.indexOf("timeStamp")).isEqualTo(0);
            // Extra columns are accessible too
            assertThat(idx.indexOf("customPlugin")).isEqualTo(17);
            assertThat(idx.columnCount()).isEqualTo(19);
        }
    }

    // -----------------------------------------------------------------------
    // Required column validation behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when a required column is missing from the header")
    class WhenRequiredColumnIsMissing {

        @ParameterizedTest(name = "refuses to build index when {0} is absent")
        @ValueSource(strings = {
                "timeStamp", "elapsed", "label", "responseCode",
                "success", "bytes", "Latency", "URL", "Connect"
        })
        void refuses_to_build_index_for_header_missing_required_column(String missingColumn) {
            // Remove the column from the standard header
            String truncatedHeader = STANDARD_HEADER
                    .replace("," + missingColumn, "")
                    .replace(missingColumn + ",", "");

            assertThatThrownBy(() -> ColumnIndex.parse(truncatedHeader))
                    .isInstanceOf(ColumnIndexException.class)
                    .hasMessageContaining(missingColumn);
        }

        @Test
        @DisplayName("names all missing columns at once rather than stopping at the first")
        void reports_all_missing_columns_in_one_error() {
            // A header with only timestamp and label — 15 required columns missing
            String sparseHeader = "timeStamp,label";

            assertThatThrownBy(() -> ColumnIndex.parse(sparseHeader))
                    .isInstanceOf(ColumnIndexException.class)
                    .hasMessageContainingAll("elapsed", "responseCode", "success", "bytes");
        }
    }

    // -----------------------------------------------------------------------
    // Header robustness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("header parsing robustness")
    class HeaderParsingRobustness {

        @Test
        @DisplayName("trims whitespace from column names — some JMeter configs add spaces after commas")
        void trims_whitespace_from_column_names() {
            String spacedHeader = STANDARD_HEADER.replace(",", ", ");

            ColumnIndex idx = ColumnIndex.parse(spacedHeader);

            // Columns must resolve by their trimmed names
            assertThat(idx.indexOf("elapsed")).isEqualTo(1);
            assertThat(idx.indexOf("label")).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects null header immediately rather than producing a misleading error later")
        void rejects_null_header() {
            assertThatThrownBy(() -> ColumnIndex.parse(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
