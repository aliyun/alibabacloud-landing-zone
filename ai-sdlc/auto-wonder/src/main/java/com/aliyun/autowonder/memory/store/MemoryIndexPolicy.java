package com.aliyun.autowonder.memory.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryIndexPolicy {
    public static final int MAX_INDEX_LINES = 200;
    /** Claude Code 2.1.272 native client constant (decimal KB, not KiB). */
    public static final int MAX_INDEX_BYTES = 25_000;
    public static final int NEAR_INDEX_LINES = MAX_INDEX_LINES * 90 / 100;
    public static final int NEAR_INDEX_BYTES = MAX_INDEX_BYTES * 90 / 100;
    public static final int TARGET_INDEX_LINES = MAX_INDEX_LINES * 80 / 100;
    public static final int TARGET_INDEX_BYTES = MAX_INDEX_BYTES * 80 / 100;
    public static final int MAX_ENTRY_CODE_POINTS = 200;

    private static final Pattern ENTRY = Pattern.compile("^- \\[[^]\\r\\n]+]\\(([^)\\r\\n]+)\\)(?:\\s+[—-]\\s+.+)?$");
    private static final Pattern SCOPE_MARKER = Pattern.compile("^<!-- (?:ORG|SQUAD|AGENT) memory -->$");

    private MemoryIndexPolicy() {
    }

    public static Measurement measure(String input) {
        String content = input == null ? "" : input;
        int totalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        int totalLines = lineCount(content);

        String lineBounded = firstLines(content, MAX_INDEX_LINES);
        boolean truncatedByLines = totalLines > MAX_INDEX_LINES;
        String loaded = utf8Prefix(lineBounded, MAX_INDEX_BYTES);
        boolean truncatedByBytes = lineBounded.getBytes(StandardCharsets.UTF_8).length > MAX_INDEX_BYTES;
        int loadedBytes = loaded.getBytes(StandardCharsets.UTF_8).length;
        int loadedLines = lineCount(loaded);
        int omittedLines = Math.max(0, totalLines - loadedLines);
        int firstOmittedLine = 0;
        if (truncatedByBytes) {
            firstOmittedLine = Math.max(1, loadedLines);
        } else if (truncatedByLines) {
            firstOmittedLine = MAX_INDEX_LINES + 1;
        }

        Headroom headroom = totalLines > MAX_INDEX_LINES || totalBytes > MAX_INDEX_BYTES
                ? Headroom.OVER_LIMIT
                : totalLines >= NEAR_INDEX_LINES || totalBytes >= NEAR_INDEX_BYTES
                ? Headroom.NEAR_LIMIT : Headroom.OK;
        return new Measurement(content, loaded, totalLines, loadedLines, totalBytes, loadedBytes,
                firstOmittedLine, omittedLines, truncatedByLines, truncatedByBytes, headroom);
    }

    public static List<Violation> validateIndex(String input, Predicate<String> targetExists) {
        String content = input == null ? "" : input;
        List<Violation> violations = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        String[] lines = content.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || SCOPE_MARKER.matcher(line).matches()) {
                continue;
            }
            if (line.indexOf('`') >= 0) {
                violations.add(new Violation("INVALID_ENTRY", i + 1, null));
                continue;
            }
            if (line.codePointCount(0, line.length()) > MAX_ENTRY_CODE_POINTS) {
                violations.add(new Violation("ENTRY_TOO_LONG", i + 1, null));
            }
            Matcher matcher = ENTRY.matcher(line);
            if (!matcher.matches()) {
                violations.add(new Violation("INVALID_ENTRY", i + 1, null));
                continue;
            }
            String target = matcher.group(1);
            try {
                target = MemoryPathValidator.requireSafe(target);
            } catch (IllegalArgumentException error) {
                violations.add(new Violation("UNSAFE_TARGET", i + 1, target));
                continue;
            }
            if (!targets.add(target)) {
                violations.add(new Violation("DUPLICATE_TARGET", i + 1, target));
            }
            if (!targetExists.test(target)) {
                violations.add(new Violation("MISSING_TARGET", i + 1, target));
            }
        }
        return List.copyOf(violations);
    }

    private static String firstLines(String content, int maxLines) {
        if (content.isEmpty()) return content;
        int seen = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n' && ++seen > maxLines) {
                return content.substring(0, i);
            }
        }
        return content;
    }

    private static String utf8Prefix(String content, int maxBytes) {
        if (content.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return content;
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            String value = new String(Character.toChars(codePoint));
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) break;
            result.append(value);
            used += size;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int lineCount(String value) {
        if (value.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n' && i + 1 < value.length()) count++;
        }
        return count;
    }

    public enum Headroom { OK, NEAR_LIMIT, OVER_LIMIT }

    public record Measurement(String totalContent, String loadedContent, int totalLines, int loadedLines,
                              int totalBytes, int loadedBytes, int firstOmittedLine, int omittedLines,
                              boolean truncatedByLines, boolean truncatedByBytes, Headroom headroom) {
    }

    public record Violation(String code, int line, String target) {
    }
}
