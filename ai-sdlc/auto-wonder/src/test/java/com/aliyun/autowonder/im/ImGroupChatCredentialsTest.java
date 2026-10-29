package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImGroupChatCredentialsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesVersionedCredentialBundle() {
        ImGroupChatCredentials credentials = ImGroupChatCredentials.parse(json,
                "{\"v\":1,\"appSecret\":\"secret\",\"verificationToken\":\"token\",\"encryptKey\":\"key\"}");

        assertEquals("secret", credentials.appSecret());
        assertEquals("token", credentials.verificationToken());
        assertEquals("key", credentials.encryptKey());
        assertFalse(credentials.isEmpty());
    }

    @Test
    void treatsMissingFieldsAsNullAndBlankBundleAsEmpty() {
        ImGroupChatCredentials credentials =
                ImGroupChatCredentials.parse(json, "{\"v\":1,\"verificationToken\":null}");
        assertNull(credentials.appSecret());
        assertNull(credentials.verificationToken());
        assertNull(credentials.encryptKey());
        assertTrue(credentials.isEmpty());
        assertTrue(ImGroupChatCredentials.parse(json, "{\"v\":1}").isEmpty());
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertKeyed("im.group_credentials_version_unsupported", "{\"appSecret\":\"s\"}");
        assertKeyed("im.group_credentials_version_unsupported", "{\"v\":2,\"appSecret\":\"s\"}");
        assertKeyed("im.group_credentials_version_unsupported", "{\"v\":\"1\",\"appSecret\":\"s\"}");
        assertKeyed("im.group_credentials_version_unsupported", "{\"v\":1.5,\"appSecret\":\"s\"}");
    }

    @Test
    void rejectsMalformedOrNonTextBundles() {
        assertKeyed("im.group_credentials_invalid", "[]");
        assertKeyed("im.group_credentials_invalid", "\"text\"");
        assertKeyed("im.group_credentials_invalid", "{\"v\":1,\"appSecret\":42}");
        assertKeyed("im.group_credentials_invalid", "{\"v\":1,\"appSecret\":{\"nested\":true}}");
        assertKeyed("im.group_credentials_invalid", "not-json");
    }

    @Test
    void serializesOnlyPresentFields() throws Exception {
        String serialized = new ImGroupChatCredentials("secret", " ", null).toJson(json);
        var node = json.readTree(serialized);

        assertEquals(1, node.get("v").asInt());
        assertEquals("secret", node.get("appSecret").asText());
        assertFalse(node.has("verificationToken"));
        assertFalse(node.has("encryptKey"));
    }

    @Test
    void mergePrefersNewValuesAndKeepsStoredOnes() {
        ImGroupChatCredentials current =
                new ImGroupChatCredentials("old-secret", "old-token", "old-key");

        ImGroupChatCredentials merged = current.merge("new-secret", " ", null, false);
        assertEquals("new-secret", merged.appSecret());
        assertEquals("old-token", merged.verificationToken());
        assertEquals("old-key", merged.encryptKey());

        ImGroupChatCredentials fromEmpty =
                new ImGroupChatCredentials(null, null, null).merge(null, "token", "key", false);
        assertNull(fromEmpty.appSecret());
        assertEquals("token", fromEmpty.verificationToken());
        assertEquals("key", fromEmpty.encryptKey());
    }

    @Test
    void mergeCanClearEncryptKey() {
        ImGroupChatCredentials current =
                new ImGroupChatCredentials("secret", "token", "old-key");
        ImGroupChatCredentials merged =
                current.merge(null, null, "ignored", true);

        assertNull(merged.encryptKey());
        assertEquals("secret", merged.appSecret());
        assertEquals("token", merged.verificationToken());
    }

    private void assertKeyed(String expectedKey, String payload) {
        BizException error = assertThrows(BizException.class,
                () -> ImGroupChatCredentials.parse(json, payload));
        assertEquals("28001", error.getCode());
        assertEquals(expectedKey, error.getMessageKey());
    }
}
