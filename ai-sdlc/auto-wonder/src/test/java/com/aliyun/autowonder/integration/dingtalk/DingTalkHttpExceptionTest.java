package com.aliyun.autowonder.integration.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DingTalkHttpExceptionTest {

    @Test
    void parsesSafeProviderMetadataButDoesNotExposeRawBody() {
        String raw = "{\"code\":\"authFailed\",\"requestid\":\"req-123\","
                + "\"message\":\"secret and arbitrary provider response\"}";

        DingTalkHttpException error = new DingTalkHttpException(401, raw);

        assertEquals(401, error.getStatus());
        assertEquals("authFailed", error.getProviderCode());
        assertEquals("req-123", error.getProviderRequestId());
        assertFalse(error.getMessage().contains(raw));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void rejectsUnsafeProviderMetadata() {
        DingTalkHttpException error = new DingTalkHttpException(500,
                "{\"code\":\"bad code with spaces\",\"requestid\":\"<unsafe>\"}");

        assertNull(error.getProviderCode());
        assertNull(error.getProviderRequestId());
    }
}
