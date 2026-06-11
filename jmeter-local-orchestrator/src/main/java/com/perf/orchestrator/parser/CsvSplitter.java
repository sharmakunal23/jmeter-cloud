package com.perf.orchestrator.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal RFC 4180-compliant CSV tokenizer.
 *
 * <p>Package-private — this is an implementation detail of the parser layer.
 * External callers should use {@link JtlRowParser}, not this class directly.
 *
 * <p>JMeter wraps any field containing the delimiter ({@code ,}) in double-quotes.
 * The most common case is {@code failureMessage} which can contain arbitrary text
 * including commas. A naive {@code String.split(",")} would produce the wrong
 * field count for such rows.
 *
 * <p>Handles:
 * <ul>
 *   <li>Unquoted fields: {@code 200,OK,some text}</li>
 *   <li>Quoted fields: {@code "text with, comma"}</li>
 *   <li>Escaped double-quotes inside quoted fields: {@code "She said ""hello"""}</li>
 *   <li>Empty fields: {@code 200,,OK} → ["200", "", "OK"]</li>
 * </ul>
 */
final class CsvSplitter {

    private CsvSplitter() {}

    /**
     * Splits a single CSV line into field values.
     *
     * <p>Surrounding quotes are stripped from quoted fields. Escaped double-quotes
     * ({@code ""}) within a quoted field are reduced to a single {@code "}.
     *
     * @param line a single CSV line, must not be null
     * @return ordered list of field values; never null, may be empty for a blank line
     */
    static List<String> split(String line) {
        Objects.requireNonNull(line, "line cannot be null");
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // Peek ahead: "" inside a quoted field is an escaped literal quote
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++; // consume the second quote
                    } else {
                        // Closing quote
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
        }

        // Always add the last field (no trailing comma in well-formed CSV)
        fields.add(field.toString());
        return fields;
    }
}
