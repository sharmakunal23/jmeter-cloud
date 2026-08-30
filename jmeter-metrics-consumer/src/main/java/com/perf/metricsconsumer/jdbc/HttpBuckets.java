package com.perf.metricsconsumer.jdbc;

import java.util.Map;

/**
 * The wire's {@code statusCodes} map folded into the five fact columns: a key
 * of exactly three digits whose first is {@code 2}–{@code 5} lands in its class
 * bucket, anything else ({@code 1xx}, {@code "99"}, {@code "0200"}, a JMeter
 * {@code Non HTTP response code: …}, a null key) in {@code HTTP_OTHER}; a null
 * count is 0. The per-code detail is not recoverable from the schema.
 */
public record HttpBuckets(long http2xx, long http3xx, long http4xx, long http5xx, long other) {

    public static final HttpBuckets NONE = new HttpBuckets(0, 0, 0, 0, 0);

    public static HttpBuckets fold(Map<String, Long> statusCodes) {
        if (statusCodes == null || statusCodes.isEmpty()) {
            return NONE;
        }
        long h2 = 0, h3 = 0, h4 = 0, h5 = 0, o = 0;
        for (Map.Entry<String, Long> e : statusCodes.entrySet()) {
            long n = e.getValue() == null ? 0 : e.getValue();
            switch (bucket(e.getKey())) {
                case '2' -> h2 += n;
                case '3' -> h3 += n;
                case '4' -> h4 += n;
                case '5' -> h5 += n;
                default -> o += n;
            }
        }
        return new HttpBuckets(h2, h3, h4, h5, o);
    }

    private static char bucket(String code) {
        if (code == null || code.length() != 3) {
            return 'o';
        }
        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return 'o';
            }
        }
        char first = code.charAt(0);
        return first >= '2' && first <= '5' ? first : 'o';
    }
}
