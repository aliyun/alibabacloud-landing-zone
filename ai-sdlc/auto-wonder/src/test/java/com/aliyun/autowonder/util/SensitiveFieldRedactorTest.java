package com.aliyun.autowonder.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveFieldRedactorTest {

    @Test
    void recursivelyRedactsSensitiveJsonFields() {
        String body = """
                {"appSecret":"top-secret","nested":[{"externalUserId":"staff-001"},
                {"app_secret":"snake-secret","credentialRef":"kms-reference"}],"safe":"visible"}
                """;

        String redacted = new String(
                SensitiveFieldRedactor.redactJson(body.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertFalse(redacted.contains("top-secret"));
        assertFalse(redacted.contains("staff-001"));
        assertFalse(redacted.contains("snake-secret"));
        assertFalse(redacted.contains("kms-reference"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("visible"));
    }

    @Test
    void failsClosedForNonJsonAndMalformedSensitiveBody() {
        byte[] body = "plain appSecret=top-secret payload".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = """
                {"appSecret":"broken-secret","externalUserId":"full-user-id"
                """.getBytes(StandardCharsets.UTF_8);

        byte[] redacted = SensitiveFieldRedactor.redactJson(body);
        byte[] malformedRedacted = SensitiveFieldRedactor.redactJson(malformed);

        assertEquals("[REDACTED]", new String(redacted, StandardCharsets.UTF_8));
        assertEquals("[REDACTED]", new String(malformedRedacted, StandardCharsets.UTF_8));
        assertFalse(new String(malformedRedacted, StandardCharsets.UTF_8)
                .contains("broken-secret"));
        assertFalse(new String(malformedRedacted, StandardCharsets.UTF_8)
                .contains("full-user-id"));
    }
}
