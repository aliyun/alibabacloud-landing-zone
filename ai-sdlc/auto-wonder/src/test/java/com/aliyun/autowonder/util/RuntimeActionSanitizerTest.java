package com.aliyun.autowonder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeActionSanitizerTest {

    @Test
    void sanitizeNullReturnsNull() {
        assertNull(RuntimeActionSanitizer.sanitize(null));
    }

    @Test
    void sanitizePreservesPlainText() {
        assertEquals("reading file src/Main.java", RuntimeActionSanitizer.sanitize("reading file src/Main.java"));
    }

    @Test
    void sanitizeRedactsSignedUrlSecrets() {
        String input = "https://oss.example.com/file?Signature=abc123secret&Expires=9999";
        String result = RuntimeActionSanitizer.sanitize(input);
        assertFalse(result.contains("abc123secret"));
        assertTrue(result.contains("[REDACTED]"));
        assertTrue(result.contains("Expires=9999"));
    }

    @Test
    void sanitizeRedactsBearerToken() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123";
        String result = RuntimeActionSanitizer.sanitize(input);
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void sanitizeRedactsSecretAssignments() {
        String input = "apiKey=sk-proj-abcdefghijklmnop1234567890";
        String result = RuntimeActionSanitizer.sanitize(input);
        assertFalse(result.contains("sk-proj-abcdefghijklmnop1234567890"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void sanitizeRedactsLongOpaqueStrings() {
        String input = "token a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0 found";
        String result = RuntimeActionSanitizer.sanitize(input);
        assertFalse(result.contains("a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0"));
    }

    @Test
    void sanitizePreservesCommitSha() {
        String sha = "25371cb104ac019fb26674f0c495c410c01e5041";
        String result = RuntimeActionSanitizer.sanitize("pushed commit " + sha + " to branch");
        assertTrue(result.contains(sha), "commit SHA must stay readable: " + result);
        assertFalse(result.contains("[REDACTED]"));
    }

    @Test
    void sanitizePreservesLongSha256() {
        String sha = "a".repeat(64);
        String result = RuntimeActionSanitizer.sanitize("digest " + sha);
        assertTrue(result.contains(sha), "64-hex digest must stay readable: " + result);
    }

    @Test
    void sanitizePreservesArtifactPath() {
        String path = "repos/auto-wonder/src/main/java/com/aliyun/autowonder/util/RuntimeActionSanitizer.java";
        String result = RuntimeActionSanitizer.sanitize("edited " + path);
        assertTrue(result.contains(path), "source path must stay readable: " + result);
        assertFalse(result.contains("[REDACTED]"));
    }

    @Test
    void sanitizeStillRedactsOpaqueBase64Token() {
        // A base64 blob with '=' padding and no path separator is not a SHA or a path.
        String input = "blob QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5QQ== end";
        String result = RuntimeActionSanitizer.sanitize(input);
        assertFalse(result.contains("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5QQ=="));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void sanitizePreTruncatesHugeInput() {
        String input = "word ".repeat(20_000);
        String result = RuntimeActionSanitizer.sanitize(input, 50);
        assertNotNull(result);
        assertTrue(result.codePointCount(0, result.length()) <= 51);
        assertTrue(result.endsWith("…"));
    }

    @Test
    void sanitizeStripsControlCharacters() {
        String input = "hello\u0000world\u001b[31m";
        String result = RuntimeActionSanitizer.sanitize(input);
        assertFalse(result.contains("\u0000"));
        assertFalse(result.contains("\u001b"));
    }

    @Test
    void sanitizeTruncatesLongText() {
        String input = "word ".repeat(60);
        String result = RuntimeActionSanitizer.sanitize(input, 100);
        assertNotNull(result);
        assertTrue(result.codePointCount(0, result.length()) <= 101);
        assertTrue(result.endsWith("…"));
    }

    @Test
    void sanitizeCustomMaxChars() {
        String input = "abcdefghij";
        assertEquals("abcdefghij", RuntimeActionSanitizer.sanitize(input, 20));
        assertTrue(RuntimeActionSanitizer.sanitize(input, 5).endsWith("…"));
    }

    @Test
    void looksSensitiveDetectsSignedUrl() {
        assertTrue(RuntimeActionSanitizer.looksSensitive("https://x.com?Signature=abc123"));
    }

    @Test
    void looksSensitiveDetectsBearer() {
        assertTrue(RuntimeActionSanitizer.looksSensitive("Bearer abcdefghijklmnop"));
    }

    @Test
    void looksSensitiveDetectsSecretAssignment() {
        assertTrue(RuntimeActionSanitizer.looksSensitive("password=supersecret123"));
    }

    @Test
    void looksSensitiveReturnsFalseForPlainText() {
        assertFalse(RuntimeActionSanitizer.looksSensitive("reading file Main.java"));
    }

    @Test
    void looksSensitiveReturnsFalseForNullOrBlank() {
        assertFalse(RuntimeActionSanitizer.looksSensitive(null));
        assertFalse(RuntimeActionSanitizer.looksSensitive(""));
        assertFalse(RuntimeActionSanitizer.looksSensitive("   "));
    }

    @Test
    void truncateNullReturnsNull() {
        assertNull(RuntimeActionSanitizer.truncate(null, 100));
    }

    @Test
    void truncateShortTextUnchanged() {
        assertEquals("hello", RuntimeActionSanitizer.truncate("hello", 10));
    }

    @Test
    void truncateAtExactLimit() {
        assertEquals("hello", RuntimeActionSanitizer.truncate("hello", 5));
    }

    @Test
    void truncateBeyondLimit() {
        String result = RuntimeActionSanitizer.truncate("hello world", 5);
        assertEquals("hello…", result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://oss.aliyuncs.com/bucket/key?OSSAccessKeyId=LTAI123&Signature=abc%2Bdef",
        "https://s3.amazonaws.com/bucket?X-Amz-Signature=deadbeef1234",
        "https://cdn.example.com/f?access_token=longtoken123456789"
    })
    void sanitizeRedactsVariousSignedUrls(String url) {
        String result = RuntimeActionSanitizer.sanitize(url);
        assertTrue(result.contains("[REDACTED]"), "Expected redaction in: " + result);
    }
}
