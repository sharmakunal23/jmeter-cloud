package com.perf.orchestrator.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one property contract, shared by launch ({@code StartTestRequest}) and
 * runtime updates ({@code UpdatePropertiesRequest}): keys match
 * {@code [A-Za-z_][A-Za-z0-9_.]{0,63}} — no shell metacharacters, no path
 * separators, no leading digit — and values are ≤ 256 chars with no control
 * characters (which also keeps BeanShell statements single-line).
 */
final class JmeterProperties {

    static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,63}");
    static final int MAX_VALUE_LENGTH = 256;

    private JmeterProperties() {}

    /** Returns a defensively-ordered copy (LinkedHashMap) so command lines stay reproducible. */
    static Map<String, String> validate(Map<String, String> raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(raw.size());
        raw.forEach((k, v) -> {
            if (k == null || !KEY_PATTERN.matcher(k).matches()) {
                throw new IllegalArgumentException(
                        "properties key '" + k + "' is invalid — must match "
                        + "[A-Za-z_][A-Za-z0-9_.]{0,63}");
            }
            if (v == null) {
                throw new IllegalArgumentException(
                        "properties value for key '" + k + "' is null");
            }
            if (v.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "properties value for '" + k + "' exceeds "
                        + MAX_VALUE_LENGTH + " chars");
            }
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException(
                            "properties value for '" + k + "' contains a control character");
                }
            }
            out.put(k, v);
        });
        return out;
    }
}
