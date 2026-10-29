package com.aliyun.autowonder.util;

import java.util.Locale;
import java.util.Set;

/** Keeps presigned URLs in logs and diagnostics from leaking their signature material. */
public final class SignedUrlRedactor {

    private static final Set<String> SIGNED_QUERY_PARAMETERS = Set.of(
            "signature",
            "ossaccesskeyid",
            "expires",
            "security-token",
            "token",
            "x-oss-signature",
            "x-oss-credential",
            "x-oss-security-token",
            "x-oss-date",
            "x-oss-expires",
            "x-amz-signature",
            "x-amz-credential",
            "x-amz-security-token",
            "x-amz-date",
            "x-amz-expires");

    private SignedUrlRedactor() {
    }

    public static String redact(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return url;
        }
        StringBuilder redacted = new StringBuilder(url.length())
                .append(url, 0, queryStart + 1);
        String[] pairs = url.substring(queryStart + 1).split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            int separator = pairs[i].indexOf('=');
            String name = separator < 0 ? pairs[i] : pairs[i].substring(0, separator);
            redacted.append(name);
            if (SIGNED_QUERY_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                redacted.append("=***");
            } else if (separator >= 0) {
                redacted.append(pairs[i], separator, pairs[i].length());
            }
        }
        return redacted.toString();
    }
}
