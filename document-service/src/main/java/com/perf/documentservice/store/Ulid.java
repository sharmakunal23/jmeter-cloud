package com.perf.documentservice.store;

import java.security.SecureRandom;

/**
 * Minimal ULID generator — 48-bit timestamp plus 80-bit randomness as 26
 * URL-safe Crockford-base32 chars, lexicographically sortable by creation time.
 * Hand-rolled to avoid a dependency for a 50-line algorithm.
 */
public final class Ulid {

    private static final char[] CROCKFORD =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The shape {@link #generate()} emits, kept in sync with
     * {@link #isValid(String)}.
     *
     * <p>Used as a Spring path-variable constraint, so a malformed blobId or
     * runId is rejected at routing with a 404 before reaching the store. It must
     * stay a compile-time constant to sit inside a {@code @GetMapping}.
     */
    public static final String PATTERN = "[0-9A-HJKMNP-TV-Z]{26}";

    private Ulid() {}

    /** Generates a new ULID using the system clock + cryptographic randomness. */
    public static String generate() {
        return generate(System.currentTimeMillis());
    }

    /** Package-private for testability — same generation logic at a fixed timestamp. */
    static String generate(long timestampMs) {
        if (timestampMs < 0 || timestampMs > 0xFFFF_FFFF_FFFFL) {
            // ULID timestamp is 48 bits = ~10889 AD. Anything else is a programmer error.
            throw new IllegalArgumentException("timestamp out of ULID range: " + timestampMs);
        }
        char[] out = new char[26];

        // Encode 48-bit timestamp into chars 0-9 (10 chars × 5 bits = 50 bits;
        // top 2 bits are zero by construction since timestampMs fits in 48).
        long ts = timestampMs;
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (ts & 0x1F)];
            ts >>>= 5;
        }

        // 80-bit randomness across chars 10-25 (16 chars × 5 bits = 80 bits).
        // Read 10 random bytes and unpack as two 40-bit halves.
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long high = 0;  // bytes 0..4 → 40 bits → 8 chars
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (random[i] & 0xFFL);
        }
        long low = 0;   // bytes 5..9 → 40 bits → 8 chars
        for (int i = 5; i < 10; i++) {
            low = (low << 8) | (random[i] & 0xFFL);
        }
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

    /**
     * Validates a string against the ULID format — 26 Crockford chars.
     * Used by the controller to reject malformed path parameters before
     * touching the filesystem.
     */
    public static boolean isValid(String s) {
        if (s == null || s.length() != 26) return false;
        for (int i = 0; i < 26; i++) {
            char c = s.charAt(i);
            if (!isCrockfordChar(c)) return false;
        }
        return true;
    }

    private static boolean isCrockfordChar(char c) {
        // 0-9, A-H, J, K, M, N, P-T, V-Z (case sensitive — generators emit upper).
        if (c >= '0' && c <= '9') return true;
        if (c >= 'A' && c <= 'H') return true;
        return c == 'J' || c == 'K' || c == 'M' || c == 'N'
                || (c >= 'P' && c <= 'T')
                || (c >= 'V' && c <= 'Z');
    }
}
