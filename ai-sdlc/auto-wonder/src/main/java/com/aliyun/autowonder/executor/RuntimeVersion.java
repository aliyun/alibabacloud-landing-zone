package com.aliyun.autowonder.executor;

import java.util.OptionalInt;

/**
 * Stable {@code X.Y.Z} runtime version comparison shared by the manual and automatic upgrade paths.
 * Anything that does not parse is reported as unknown rather than assumed older, so a mis-reported
 * client is never silently downgraded or force-upgraded.
 */
final class RuntimeVersion {

    private RuntimeVersion() {
    }

    /** Returns negative when {@code reported} is behind {@code target}, zero when equal, empty when either is unparseable. */
    static OptionalInt compare(String reported, String target) {
        long[] left = parse(reported);
        long[] right = parse(target);
        if (left == null || right == null) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < 3; i++) {
            if (left[i] != right[i]) {
                return OptionalInt.of(Long.compare(left[i], right[i]));
            }
        }
        return OptionalInt.of(0);
    }

    /** True only when {@code reported} is a parseable stable version strictly below {@code target}. */
    static boolean isBehind(String reported, String target) {
        return compare(reported, target).orElse(1) < 0;
    }

    private static long[] parse(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        long[] numbers = new long[3];
        for (int i = 0; i < 3; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 19) {
                return null;
            }
            long number = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                // Nineteen digits can still exceed Long.MAX_VALUE, so the accumulation itself is guarded.
                if (number > (Long.MAX_VALUE - (c - '0')) / 10) {
                    return null;
                }
                number = number * 10 + (c - '0');
            }
            numbers[i] = number;
        }
        return numbers;
    }
}
