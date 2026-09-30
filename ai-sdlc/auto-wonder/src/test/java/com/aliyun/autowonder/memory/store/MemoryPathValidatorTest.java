package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPathValidatorTest {

    @Test
    void acceptsCanonicalPrivateAndSharedMarkdownPaths() {
        assertDoesNotThrow(() -> MemoryPathValidator.requireSafe("MEMORY.md"));
        assertDoesNotThrow(() -> MemoryPathValidator.requireSafe("feedback_testing.md"));
        assertDoesNotThrow(() -> MemoryPathValidator.requireSafe("team/squad-10215/project_release.md"));
    }

    @Test
    void rejectsTraversalAbsoluteHiddenControlAndNonMarkdownPaths() {
        for (String path : List.of(
                "../secret.md", "team/../../secret.md", "%2e%2e/secret.md", "%2E%2E/secret.md",
                "/tmp/memory.md", "C:\\memory.md", "team\\memory.md", "team/.hidden.md",
                "Ｃ：＼memory.md", "team／..／secret.md",
                ".hidden/MEMORY.md", "team//memory.md", "team/\u0000bad.md", "topic.txt")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> MemoryPathValidator.requireSafe(path), path);
            assertFalse(error.getMessage().isBlank());
        }
    }

    @Test
    void rejectsPlatformReservedNamesCaseInsensitively() {
        for (String path : List.of("con.md", "team/AUX.md", "team/com1.md", "Lpt9.md")) {
            assertThrows(IllegalArgumentException.class, () -> MemoryPathValidator.requireSafe(path), path);
        }
    }

    @Test
    void documentValidationRejectsNullAndUnpairedSurrogates() {
        assertThrows(IllegalArgumentException.class,
                () -> MemoryDocumentValidator.requireValid("topic.md", null));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryDocumentValidator.requireValid("topic.md", "bad\uD800value"));
        assertDoesNotThrow(() -> MemoryDocumentValidator.requireValid("topic.md", "valid 中 😀"));
        assertThrows(IllegalArgumentException.class, () -> MemoryDocumentValidator.requireValid(
                "topic.md", "x".repeat(MemoryDocumentValidator.MAX_DOCUMENT_BYTES + 1)));
    }

    @Test
    void canonicalTopicUsesClaudeCodeTopLevelTypeAndReadsLegacyNestedType() {
        String canonical = "---\ntype: feedback\n---\n\nUse lane B.\n";
        assertDoesNotThrow(() -> MemoryDocumentValidator.requireCanonicalTopic("feedback.md", canonical));
        assertDoesNotThrow(() -> MemoryDocumentValidator.requireCanonicalTopic("legacy.md",
                "---\nmetadata:\n  type: feedback\n---\nbody"));
        assertThrows(IllegalArgumentException.class, () -> MemoryDocumentValidator.requireCanonicalTopic("feedback.md",
                "---\ntype: feedback\nmetadata:\n  type: project\n---\nbody"));
        assertThrows(IllegalArgumentException.class, () -> MemoryDocumentValidator.requireCanonicalTopic(
                "feedback.md", "plain body"));
    }
}
