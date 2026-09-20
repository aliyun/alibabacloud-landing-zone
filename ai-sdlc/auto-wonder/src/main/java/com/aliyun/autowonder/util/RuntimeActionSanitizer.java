package com.aliyun.autowonder.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds one runtime action summary before it reaches a browser. The live activity panel only ever
 * renders allowlisted event fields, so this is the last line of defence against a secret that a
 * runtime embedded in a free-text message, command argument or signed URL.
 */
public final class RuntimeActionSanitizer {

    public static final int DEFAULT_MAX_CHARS = 180;
    private static final String REDACTED = "[REDACTED]";
    private static final String TRUNCATION_MARK = "…";

    /** Signature and credential query parameters of OSS/S3/CDN style download links. */
    private static final Pattern SIGNED_URL_SECRET = Pattern.compile(
            "(?i)([?&](?:signature|x-amz-signature|x-amz-credential|x-amz-security-token|ossaccesskeyid"
                    + "|accesskeyid|accesskeysecret|sig|token|access_token|authtoken|api[-_]?key|secpubk)="
                    + ")[^&#\\s]+");

    /** Authorization headers, bearer tokens and Basic credentials. */
    private static final Pattern CREDENTIAL_HEADER = Pattern.compile(
            "(?i)\\b(authorization\\s*[:=]\\s*|bearer\\s+|basic\\s+)[a-z0-9._~+/-]{8,}=*");

    /** key=value pairs whose key names a secret. */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([a-z0-9_.-]*(?:token|secret|password|passwd|pwd|apikey|api_key|api-key|accesskey"
                    + "|access_key|credential|signature|privatekey|private_key|sessionkey|authcode|mcpsecret"
                    + "|mcp_secret|clientsecret|client_secret)[a-z0-9_.-]*)\\s*[:=]\\s*([^\\s,;\"'}\\]]{4,})");

    /** JWT segments and standalone long opaque strings. */
    private static final Pattern JWT = Pattern.compile("\\beyJ[a-z0-9_-]{6,}\\.[a-z0-9_-]{6,}\\.[a-z0-9_-]{4,}\\b");
    private static final Pattern LONG_OPAQUE = Pattern.compile("\\b[a-z0-9+/_.=-]{32,}\\b", Pattern.CASE_INSENSITIVE);
    /** A full git commit SHA (40 hex) or SHA-256 (64 hex) is a readable identifier, not a secret. */
    private static final Pattern HEX_SHA = Pattern.compile("(?i)^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    /** A filesystem path token: only path-safe characters and no base64 '+''/'=' padding. */
    private static final Pattern PATH_SAFE = Pattern.compile("^[A-Za-z0-9._~/-]+$");

    /** Control characters other than the plain space, including ANSI escapes. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]");

    private RuntimeActionSanitizer() {
    }

    public static String sanitize(String text) {
        return sanitize(text, DEFAULT_MAX_CHARS);
    }

    public static String sanitize(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        // Bound regex work before sanitizing: the tail beyond limit is dropped by truncate() anyway,
        // so cut to a generous multiple of the limit first and never run the patterns on huge input.
        String value = preTruncate(text, limit);
        value = CONTROL_CHARS.matcher(value).replaceAll(" ");
        value = SIGNED_URL_SECRET.matcher(value).replaceAll("$1" + REDACTED);
        value = JWT.matcher(value).replaceAll(REDACTED);
        value = CREDENTIAL_HEADER.matcher(value).replaceAll(REDACTED);
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1=" + REDACTED);
        value = redactLongOpaque(value);
        value = value.replaceAll("\\s{2,}", " ").trim();
        if (value.isEmpty()) {
            return null;
        }
        return truncate(value, limit);
    }

    /** Drops the tail beyond {@code limit * 8} code points before the sanitizing regexes run. */
    private static String preTruncate(String text, int limit) {
        int cap = limit * 8;
        if (text.codePointCount(0, text.length()) <= cap) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, cap));
    }

    /**
     * Redacts standalone long opaque strings but keeps readable git SHAs and filesystem paths
     * (NB-5): a 40/64-hex commit SHA or a path-safe token is an identifier, not a secret.
     */
    static String redactLongOpaque(String value) {
        Matcher matcher = LONG_OPAQUE.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = isKnownSafeToken(token) ? token : REDACTED;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    static boolean isKnownSafeToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (HEX_SHA.matcher(token).matches()) {
            return true;
        }
        return token.indexOf('/') >= 0 && PATH_SAFE.matcher(token).matches();
    }

    /** True when the text still holds a recognizable secret shape after {@link #sanitize}. */
    public static boolean looksSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return SIGNED_URL_SECRET.matcher(text).find()
                || CREDENTIAL_HEADER.matcher(text).find()
                || SECRET_ASSIGNMENT.matcher(text).find()
                || JWT.matcher(text).find();
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        if (text.codePointCount(0, text.length()) <= limit) {
            return text;
        }
        int end = text.offsetByCodePoints(0, limit);
        return text.substring(0, end).stripTrailing() + TRUNCATION_MARK;
    }
}
