package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformImChannelConfigServiceTest {

    @Test
    void ownerCanCreateDingTalkConfigWithEncryptedSecretAndSafeResponse() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.encrypt("app-secret")).thenReturn("kms://encrypted");
        when(dao.listActive()).thenAnswer(invocation -> {
            PlatformImChannelConfigDO row = row(true, "app-key", "kms://encrypted", "robot-code");
            return List.of(row);
        });
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(dao, secretCrypto);

        var result = service.updateDingTalk(100L, request(true, " app-key ", "app-secret", " robot-code "));

        ArgumentCaptor<PlatformImChannelConfigDO> captor = ArgumentCaptor.forClass(PlatformImChannelConfigDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals("DINGTALK", captor.getValue().getProvider());
        assertEquals("kms://encrypted", captor.getValue().getCredentialRef());
        assertEquals("app-key", captor.getValue().getAppKey());
        assertTrue(result.isSecretConfigured());
        assertTrue(result.isReady());

        JsonNode json = new ObjectMapper().valueToTree(result);
        assertFalse(json.has("appSecret"));
        assertFalse(json.has("credentialRef"));
        assertFalse(json.toString().contains("kms://encrypted"));
        assertFalse(json.toString().contains("app-secret"));
    }

    @Test
    void blankSecretPreservesExistingCredential() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(dao.findByProvider("DINGTALK"))
                .thenReturn(row(true, "old-key", "kms://existing", "old-robot"));
        when(dao.listActive()).thenReturn(List.of(row(true, "new-key", "kms://existing", "new-robot")));
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(dao, secretCrypto);

        service.updateDingTalk(100L, request(true, "new-key", "  ", "new-robot"));

        verify(secretCrypto, never()).encrypt(anyString());
        InOrder order = inOrder(dao);
        order.verify(dao).upsert(argThat(config -> config.getCredentialRef() == null));
        order.verify(dao).findByProvider("DINGTALK");
    }

    @Test
    void mapperAtomicallyRetainsCredentialWhenIncomingValueIsBlank() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/mapping/PlatformImChannelConfigDao.xml")) {
            assertNotNull(stream);
            String mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(mapper.contains(
                    "COALESCE(NULLIF(VALUES(credential_ref), ''), credential_ref)"));
        }
    }

    @Test
    void firstEnabledConfigWithoutSecretIsRejectedAfterAtomicUpsert() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(dao, secretCrypto);

        BizException error = assertThrows(BizException.class,
                () -> service.updateDingTalk(100L, request(true, "app-key", null, "robot-code")));

        assertEquals("28001", error.getCode());
        verify(dao).upsert(argThat(config -> config.getCredentialRef() == null));
    }

    @Test
    void rejectsInvalidLengthsAndNonHttpsBaseUrlForDirectCallers() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(
                dao, secretCrypto);

        UpdateDingTalkChannelRequest request = request(false, "a".repeat(129), null, null);
        assertInvalid(service, request);

        request = request(false, null, "s".repeat(1025), null);
        assertInvalid(service, request);

        request = request(false, null, null, "r".repeat(129));
        assertInvalid(service, request);

        request = request(false, null, null, null);
        request.setBaseUrl("http://api.dingtalk.com");
        assertInvalid(service, request);

        request = request(false, null, null, null);
        request.setBaseUrl("https://user@api.dingtalk.com/proxy");
        assertInvalid(service, request);

        request = request(false, null, null, null);
        request.setBaseUrl("https://api.dingtalk.com/proxy?tenant=one");
        assertInvalid(service, request);

        request = request(false, null, null, null);
        request.setBaseUrl("https://api.dingtalk.com/proxy#fragment");
        assertInvalid(service, request);

        verifyNoInteractions(dao);
    }

    @Test
    void normalizesTrailingSlashesAndPreservesReverseProxyPath() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(
                dao, secretCrypto);
        UpdateDingTalkChannelRequest request = request(false, null, null, null);
        request.setBaseUrl(" https://gateway.example.com/dingtalk/proxy/// ");

        service.updateDingTalk(100L, request);

        verify(dao).upsert(argThat(config ->
                "https://gateway.example.com/dingtalk/proxy".equals(config.getBaseUrl())));
    }

    private static void assertInvalid(PlatformImChannelConfigService service,
                                      UpdateDingTalkChannelRequest request) {
        assertEquals("10001", assertThrows(BizException.class,
                () -> service.updateDingTalk(100L, request)).getCode());
    }

    private static UpdateDingTalkChannelRequest request(boolean enabled, String appKey,
                                                         String appSecret, String robotCode) {
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        request.setEnabled(enabled);
        request.setAppKey(appKey);
        request.setAppSecret(appSecret);
        request.setRobotCode(robotCode);
        request.setBaseUrl(" https://api.dingtalk.com ");
        return request;
    }

    private static PlatformImChannelConfigDO row(boolean enabled, String appKey,
                                                  String credentialRef, String robotCode) {
        PlatformImChannelConfigDO row = new PlatformImChannelConfigDO();
        row.setProvider("DINGTALK");
        row.setEnabled(enabled ? 1 : 0);
        row.setAppKey(appKey);
        row.setCredentialRef(credentialRef);
        row.setRobotCode(robotCode);
        row.setBaseUrl("https://api.dingtalk.com");
        return row;
    }
}
