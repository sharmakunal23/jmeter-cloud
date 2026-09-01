package com.perf.globalorchestrator.repo;

import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.support.SqlLobValue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The bindings every repository shares on Oracle: instants go through
 * {@link OffsetDateTime} so a {@code TIMESTAMP WITH TIME ZONE} column never
 * depends on the session time zone, JSON documents go through a
 * {@link SqlLobValue} so a {@code CLOB} of any size binds as a stream, and a
 * NULL inside an expression is bound with an explicit type ({@link #typed}).
 * {@link #refCursor} reads a package procedure's {@code SYS_REFCURSOR} out
 * parameter, and {@link #text} keeps free text inside its column's width.
 */
final class OracleBind {

    private OracleBind() { }

    /** Bind value for a {@code TIMESTAMP WITH TIME ZONE} column; null-safe. */
    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** Reads a {@code TIMESTAMP WITH TIME ZONE} column as an {@link Instant}; null-safe. */
    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Reads a {@code CLOB} JSON column in full ({@code getString} is LOB-aware in ojdbc); null-safe. */
    static String json(ResultSet rs, String column) throws SQLException {
        return rs.getString(column);
    }

    /**
     * A bind whose SQL type is stated explicitly. Needed wherever a possibly-null
     * value sits inside an expression — {@code COALESCE(?, col)}, {@code CASE},
     * a {@code SELECT ? … FROM dual} MERGE source — because Oracle cannot infer
     * the type of a bare NULL there (ORA-17004). A direct column assignment
     * needs no such hint. <b>Never use this for a timestamp</b>: an
     * {@link OffsetDateTime} bound with {@code Types.TIMESTAMP} loses its offset
     * and is re-read in the session zone — write
     * {@code CAST(? AS TIMESTAMP WITH TIME ZONE)} in the SQL and bind {@link #ts}.
     */
    static SqlParameterValue typed(int sqlType, Object value) {
        return new SqlParameterValue(sqlType, value);
    }

    /**
     * Calls a procedure whose last parameter is a {@code SYS_REFCURSOR} OUT and
     * maps every row. {@code bind} sets the IN parameters; {@code outIndex} is
     * the cursor's 1-based position.
     */
    static <T> List<T> refCursor(JdbcTemplate jdbc, String call, Binder bind, int outIndex,
                                 RowMapper<T> mapper) {
        List<T> rows = jdbc.execute(call, (CallableStatementCallback<List<T>>) cs -> {
            bind.apply(cs);
            cs.registerOutParameter(outIndex, Types.REF_CURSOR);
            cs.execute();
            List<T> out = new ArrayList<>();
            try (ResultSet rs = (ResultSet) cs.getObject(outIndex)) {
                int i = 0;
                while (rs.next()) out.add(mapper.mapRow(rs, i++));
            }
            return out;
        });
        return rows == null ? List.of() : rows;
    }

    /** Sets a callable statement's IN parameters. */
    @FunctionalInterface
    interface Binder {
        void apply(CallableStatement cs) throws SQLException;
    }

    /**
     * Free text bounded to its column ({@code VARCHAR2(n CHAR)}): longer
     * values are cut with a marker rather than raising ORA-12899 mid-transaction
     * — a 5,000-character exception message must not fail the state change it
     * explains. Null passes through.
     *
     * <p>The budget is <b>bytes</b>, not characters. With
     * {@code MAX_STRING_SIZE=STANDARD} a {@code VARCHAR2(4000 CHAR)} column
     * still tops out at 4,000 bytes, so 4,000 characters of anything
     * multi-byte — a worker's error body, a name with an accent — is
     * ORA-12899 on a char-counted clamp. The cut never splits a code point.
     */
    static String text(String value, int maxBytes) {
        if (value == null) return value;
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxBytes) return value;
        // "…" is 3 bytes in UTF-8; the encoder stops on a character boundary,
        // so the result is always <= maxBytes.
        CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer in = CharBuffer.wrap(value);
        enc.encode(in, ByteBuffer.allocate(Math.max(0, maxBytes - 3)), true);
        return value.substring(0, in.position()) + "…";
    }

    /**
     * {@code TOTAL_THROUGHPUT} → {@code totalThroughput}: a column label as an API
     * key. Unquoted aliases fold to UPPER, so this is the only way a camelCase key
     * leaves a query — never a quoted alias. A digit takes the capital
     * ({@code HTTP_2XX} → {@code http2xx}), so name such columns with the
     * letter first ({@code TWOXX_COUNT}) if the key must read differently.
     */
    static String camel(String upperSnake) {
        StringBuilder out = new StringBuilder(upperSnake.length());
        boolean up = false;
        for (char c : upperSnake.toCharArray()) {
            if (c == '_') { up = true; continue; }
            out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = false;
        }
        return out.toString();
    }

    /** Byte width of the free-text columns (`stateReason`, `reason`, `errorReason`, `description`). */
    static final int TEXT_CHARS = 4000;
    /** Byte width of the name-like columns (`actor`, `initiatedBy`, names). */
    static final int NAME_CHARS = 255;

    /** Bind value for a {@code CLOB} JSON column; null binds SQL NULL. */
    static SqlParameterValue clob(String json) {
        return new SqlParameterValue(Types.CLOB, json == null ? null : new SqlLobValue(json));
    }
}
