package com.aliyun.autowonder.memory.store;

import java.util.Set;
import java.nio.charset.StandardCharsets;

public final class MemoryDocumentValidator {
    public static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");
    private MemoryDocumentValidator() {
    }

    public static void requireValid(String path, String content) {
        MemoryPathValidator.requireSafe(path);
        if (content == null) {
            throw new IllegalArgumentException("memory content is required");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("memory document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        for (int i = 0; i < content.length(); i++) {
            char value = content.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (i + 1 >= content.length() || !Character.isLowSurrogate(content.charAt(++i))) {
                    throw new IllegalArgumentException("memory content is not valid Unicode");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("memory content is not valid Unicode");
            }
        }
    }

    public static void requireCanonicalTopic(String path, String content) {
        requireValid(path, content);
        if ("MEMORY.md".equals(path)) return;
        if (!content.startsWith("---\n")) {
            throw new IllegalArgumentException("memory topic requires YAML frontmatter");
        }
        int closing = content.indexOf("\n---", 4);
        if (closing < 0) {
            throw new IllegalArgumentException("memory topic frontmatter is not closed");
        }
        String type = null;
        String legacyType = null;
        boolean inMetadata = false;
        for (String line : content.substring(4, closing).split("\n", -1)) {
            if (line.startsWith("  ")) {
                if (inMetadata && line.startsWith("  type:")) {
                    legacyType = scalar(line.substring("  type:".length()));
                }
                continue;
            }
            inMetadata = false;
            if (line.startsWith("metadata:")) {
                inMetadata = true;
            } else if (line.startsWith("type:")) {
                type = scalar(line.substring("type:".length()));
            }
        }
        if (type == null) {
            type = legacyType;
        } else if (legacyType != null && !type.equals(legacyType)) {
            throw new IllegalArgumentException("memory topic type declarations conflict");
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("memory topic type is invalid");
        }
    }

    private static String scalar(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
