package com.perf.k8sorchestrator.domain;

import java.security.SecureRandom;

/**
 * Minimal ULID generator — 26-char Crockford-base32, lexicographically
 * sortable by creation time. Mirrors the document-service's util; not
 * shared because each subsystem builds independently.
 */
public final class Ulid {

    private static final char[] CROCKFORD =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Regex matching the 26-char Crockford-base32 shape this class emits.
     * Kept in sync with {@link #isValid(String)} — reused as a Spring
     * path-variable constraint ({@code {runId:} + Ulid.PATTERN + }}) so a
     * malformed id is rejected at routing (404) before it reaches a
     * controller or a DB round-trip. Compile-time constant so it can sit in
     * a {@code @GetMapping} attribute.
     */
    public static final String PATTERN = "[0-9A-HJKMNP-TV-Z]{26}";

    private Ulid() {}

    public static String generate() {
        long ts = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long high = 0;
        for (int i = 0; i < 5; i++) high = (high << 8) | (random[i] & 0xFFL);
        long low = 0;
        for (int i = 5; i < 10; i++) low = (low << 8) | (random[i] & 0xFFL);
        for (int i = 17; i >= 10; i--) {
            out[i] = CROCKFORD[(int) (high & 0x1F)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = CROCKFORD[(int) (low & 0x1F)];
            low >>>= 5;
        }
        return new String(out);
    }

    public static boolean isValid(String s) {
        if (s == null || s.length() != 26) return false;
        for (int i = 0; i < 26; i++) {
            char c = s.charAt(i);
            if (!isCrockfordChar(c)) return false;
        }
        return true;
    }

    private static boolean isCrockfordChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'A' && c <= 'H') return true;
        return c == 'J' || c == 'K' || c == 'M' || c == 'N'
                || (c >= 'P' && c <= 'T')
                || (c >= 'V' && c <= 'Z');
    }
}
