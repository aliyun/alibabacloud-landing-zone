package com.aliyun.autowonder.memory.store;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MemoryPathValidator {
    private static final int MAX_PATH_LENGTH = 512;
    private static final Pattern DRIVE_ABSOLUTE = Pattern.compile("^[a-zA-Z]:.*");
    private static final Pattern ENCODED_TRAVERSAL = Pattern.compile("(?i)(%2e|%2f|%5c)");
    private static final Pattern RESERVED = Pattern.compile("(?i)^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])$");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".md");

    private MemoryPathValidator() {
    }

    public static String requireSafe(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw invalid("path is blank");
        }
        String normalized = Normalizer.normalize(rawPath, Normalizer.Form.NFKC);
        if (normalized.length() > MAX_PATH_LENGTH) {
            throw invalid("path is too long");
        }
        if (normalized.startsWith("/") || DRIVE_ABSOLUTE.matcher(normalized).matches()) {
            throw invalid("absolute paths are not allowed");
        }
        if (normalized.indexOf('\\') >= 0) {
            throw invalid("backslashes are not allowed");
        }
        if (ENCODED_TRAVERSAL.matcher(normalized).find()) {
            throw invalid("encoded traversal is not allowed");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (Character.isISOControl(value) || value == 0) {
                throw invalid("control characters are not allowed");
            }
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw invalid("empty or traversal segments are not allowed");
            }
            if (segment.startsWith(".")) {
                throw invalid("hidden path segments are not allowed");
            }
            String stem = segment;
            int dot = stem.indexOf('.');
            if (dot >= 0) {
                stem = stem.substring(0, dot);
            }
            if (RESERVED.matcher(stem).matches()) {
                throw invalid("platform-reserved path segment");
            }
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (ALLOWED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw invalid("autonomous memory documents must be Markdown");
        }
        return normalized;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("unsafe memory path: " + reason);
    }
}
