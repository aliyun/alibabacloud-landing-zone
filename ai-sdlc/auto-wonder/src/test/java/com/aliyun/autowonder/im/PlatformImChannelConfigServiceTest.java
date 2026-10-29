package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.dto.GroupChatConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImSelectionVO;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateGroupChatConfigRequest;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformImChannelConfigServiceTest {

    @Test
    void ownerCanCreateDingTalkConfigWithEncryptedSecretAndSafeResponse() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.encrypt("app-secret")).thenReturn("kms://encrypted");
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(dao, secretCrypto);

        var result = service.updateDingTalk(100L, request(true, " app-key ", "app-secret", " robot-code "));

        ArgumentCaptor<PlatformImChannelConfigDO> captor = ArgumentCaptor.forClass(PlatformImChannelConfigDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals("DINGTALK", captor.getValue().getProvider());
        assertEquals("NOTIFY", captor.getValue().getPurpose());
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
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY"))
                .thenReturn(row(true, "old-key", "kms://existing", "old-robot"));
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(dao, secretCrypto);

        service.updateDingTalk(100L, request(true, "new-key", "  ", "new-robot"));

        verify(secretCrypto, never()).encrypt(anyString());
        InOrder order = inOrder(dao);
        order.verify(dao).upsert(argThat(config -> config.getCredentialRef() == null));
        order.verify(dao).findByProviderAndPurpose("DINGTALK", "NOTIFY");
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

    @Test
    void updateSwitchesProviderOnlyWhenAnotherChannelWasSelected() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY"))
                .thenReturn(row(true, "app-key", "kms://existing", "robot-code"));
        when(dao.selectedProvider()).thenReturn("FEISHU");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        var result = service.updateDingTalk(100L, request(true, "app-key", null, "robot-code"));

        verify(dao).disableOthers("DINGTALK", "NOTIFY", 100L);
        verify(dao).switchProvider("DINGTALK");
        verify(dao, never()).selectProvider(anyString());
        assertTrue(result.isSelected());

        reset(dao);
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY"))
                .thenReturn(row(true, "app-key", "kms://existing", "robot-code"));
        when(dao.selectedProvider()).thenReturn("DINGTALK");

        service.updateDingTalk(100L, request(true, "app-key", null, "robot-code"));

        verify(dao).selectProvider("DINGTALK");
        verify(dao, never()).switchProvider(anyString());
    }

    @Test
    void updateRejectsNotifyIdentityThatReusesGroupChatApplication() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO group = groupRow("DINGTALK", 1, "shared-key", "shared-robot");
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(group);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        BizException error = assertThrows(BizException.class, () -> service.updateDingTalk(100L,
                request(true, "shared-key", "secret", "robot-code")));

        assertEquals("10409", error.getCode());
        assertEquals("im.notify_identity_conflict", error.getMessageKey());
        verify(dao, never()).upsert(any());
    }

    @Test
    void updateIncrementsRouteVersionWhenIdentityOrEnabledChanges() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO existing = row(true, "old-key", "kms://existing", "old-robot");
        existing.setRouteVersion(3L);
        PlatformImChannelConfigDO effective = row(true, "new-key", "kms://existing", "new-robot");
        effective.setRouteVersion(4L);
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY"))
                .thenReturn(existing, effective);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        var result = service.updateDingTalk(100L, request(true, "new-key", null, "new-robot"));

        verify(dao).upsert(argThat(config -> config.getRouteVersion() == 4L));
        assertEquals(4L, result.getRouteVersion());
    }

    @Test
    void selectionDefaultsToDingTalkWithoutRow() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        assertEquals("DINGTALK", service.selectedProvider());
        assertNull(service.selectedProviderOrNull());
        PlatformImSelectionVO selection = service.selection();
        assertEquals("DINGTALK", selection.getProvider());
        assertEquals(0L, selection.getGeneration());
        assertNull(selection.getActivatedAt());
        assertEquals(0L, service.selectionGeneration());
        assertNull(service.selectionActivatedAt());
        assertNull(service.lockSelection());
    }

    @Test
    void selectionRowIsNormalizedAndExposed() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        Date activatedAt = new Date(1784810000000L);
        ImSelectionDO row = new ImSelectionDO();
        row.setProvider("feishu");
        row.setGeneration(7L);
        row.setActivatedAt(activatedAt);
        when(dao.selection()).thenReturn(row);
        when(dao.selectedProvider()).thenReturn("feishu");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        PlatformImSelectionVO selection = service.selection();

        assertEquals("FEISHU", selection.getProvider());
        assertEquals(7L, selection.getGeneration());
        assertEquals(activatedAt, selection.getActivatedAt());
        assertEquals(7L, service.selectionGeneration());
        assertEquals(activatedAt, service.selectionActivatedAt());
        assertEquals("FEISHU", service.selectedProvider());
        assertEquals("FEISHU", service.selectedProviderOrNull());
    }

    @Test
    void selectProviderRejectsStaleGeneration() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        ImSelectionDO current = new ImSelectionDO();
        current.setProvider("DINGTALK");
        current.setGeneration(3L);
        when(dao.lockSelection()).thenReturn(current);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        BizException error = assertThrows(BizException.class,
                () -> service.selectProvider(1L, "FEISHU", 2L));

        assertEquals("10409", error.getCode());
        assertEquals("im.selection_generation_conflict", error.getMessageKey());
        verify(dao, never()).switchProvider(anyString());
    }

    @Test
    void selectProviderSwitchesChannelAndReturnsNewGeneration() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        ImSelectionDO current = new ImSelectionDO();
        current.setProvider("FEISHU");
        current.setGeneration(2L);
        ImSelectionDO switched = new ImSelectionDO();
        switched.setProvider("dingtalk");
        switched.setGeneration(3L);
        when(dao.lockSelection()).thenReturn(current);
        when(dao.selection()).thenReturn(switched);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        PlatformImSelectionVO result = service.selectProvider(1L, "DINGTALK", 2L);

        assertEquals("DINGTALK", result.getProvider());
        assertEquals(3L, result.getGeneration());
        verify(dao).disableOthers("DINGTALK", "NOTIFY", 1L);
        verify(dao).switchProvider("DINGTALK");
        verify(dao, never()).selectProvider(anyString());
    }

    @Test
    void selectProviderSameChannelOnlyTouchesGeneration() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        ImSelectionDO current = new ImSelectionDO();
        current.setProvider("FEISHU");
        current.setGeneration(5L);
        when(dao.lockSelection()).thenReturn(current);
        when(dao.selection()).thenReturn(current);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        assertEquals(5L, service.selectProvider(1L, "FEISHU", null).getGeneration());

        verify(dao).selectProvider("FEISHU");
        verify(dao, never()).switchProvider(anyString());
        verify(dao, never()).disableOthers(anyString(), anyString(), anyLong());
    }

    @Test
    void selectProviderToleratesMissingSelectionRow() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        PlatformImSelectionVO result = service.selectProvider(1L, "DINGTALK", 0L);

        assertEquals("DINGTALK", result.getProvider());
        assertEquals(0L, result.getGeneration());
        verify(dao).switchProvider("DINGTALK");
    }

    @Test
    void requireSelectedRejectsOtherChannel() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        when(dao.selectedProvider()).thenReturn("FEISHU");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        service.requireSelected("feishu");
        BizException error = assertThrows(BizException.class,
                () -> service.requireSelected("DINGTALK"));
        assertEquals("10001", error.getCode());
        assertEquals("im.channel_selected_required", error.getMessageKey());
    }

    @Test
    void groupChatReportsConfiguredIdentityAndCredentialFlags() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigDO config = groupRow("DINGTALK", 1, "shared-key", "shared-robot");
        config.setVersion(4);
        config.setRouteVersion(2L);
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(config);
        when(dao.selectedProvider()).thenReturn("DINGTALK");
        ImSelectionDO selection = new ImSelectionDO();
        selection.setGeneration(9L);
        when(dao.selection()).thenReturn(selection);
        when(secretCrypto.decrypt("kms://ref")).thenReturn(
                "{\"v\":1,\"appSecret\":\"s\",\"verificationToken\":\"t\",\"encryptKey\":\"e\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());

        GroupChatConfigVO vo = service.groupChat("dingtalk");

        assertEquals("DINGTALK", vo.getProvider());
        assertTrue(vo.isConfigured());
        assertTrue(vo.isDesiredEnabled());
        assertTrue(vo.isReady());
        assertTrue(vo.isSelected());
        assertEquals("shared-key", vo.getAppKey());
        assertEquals("shared-robot", vo.getRobotCode());
        assertTrue(vo.isSecretConfigured());
        assertTrue(vo.isVerificationTokenConfigured());
        assertTrue(vo.isEncryptKeyConfigured());
        assertEquals(4, vo.getVersion());
        assertEquals(2L, vo.getRouteVersion());
        assertEquals(9L, vo.getSelectionGeneration());
    }

    @Test
    void groupChatWithoutConfigIsUnconfigured() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        GroupChatConfigVO vo = service.groupChat("FEISHU");

        assertEquals("FEISHU", vo.getProvider());
        assertFalse(vo.isConfigured());
        assertFalse(vo.isDesiredEnabled());
        assertFalse(vo.isReady());
        assertFalse(vo.isSelected());
        assertNull(vo.getAppKey());
        assertNull(vo.getVersion());
        assertEquals(0L, vo.getSelectionGeneration());
    }

    @Test
    void groupChatReadFailureIsLoggedSafely() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenThrow(new IllegalStateException("database down"));
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        assertThrows(AlreadyLoggedException.class, () -> service.groupChat("DINGTALK"));
    }

    @Test
    void updateGroupChatCreatesSharedIdentityAndEncryptsBundle() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        AtomicReference<PlatformImChannelConfigDO> saved = new AtomicReference<>();
        when(dao.lockSelection()).thenReturn(new ImSelectionDO());
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(dao).upsert(any());
        when(secretCrypto.encrypt(anyString())).thenReturn("kms://bundle");
        when(secretCrypto.decrypt("kms://bundle")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());

        UpdateGroupChatConfigRequest request = groupRequest(true, "shared-key", "shared-robot", "s");
        GroupChatConfigVO vo = service.updateGroupChat(100L, "DINGTALK", request);

        ArgumentCaptor<PlatformImChannelConfigDO> captor =
                ArgumentCaptor.forClass(PlatformImChannelConfigDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals("GROUP_CHAT", captor.getValue().getPurpose());
        assertEquals("shared-key", captor.getValue().getAppKey());
        assertEquals("shared-robot", captor.getValue().getRobotCode());
        assertEquals("kms://bundle", captor.getValue().getCredentialRef());
        assertEquals(100L, captor.getValue().getCreatorId());
        assertTrue(vo.isConfigured());
        assertTrue(vo.isReady());
        assertTrue(vo.isSelected());
        assertTrue(vo.isSecretConfigured());
        assertEquals(0L, vo.getRouteVersion());
    }

    @Test
    void sharedBundleSupportsFullLengthSecretWithCommunityEncryption() {
        var properties = new com.aliyun.autowonder.security.crypto.SecretCryptoProperties();
        properties.setMasterKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        SecretCrypto crypto = new com.aliyun.autowonder.security.crypto.AesGcmSecretCrypto(properties);
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        AtomicReference<PlatformImChannelConfigDO> saved = new AtomicReference<>();
        when(dao.lockSelection()).thenReturn(new ImSelectionDO());
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(dao).upsert(any());
        var service = new PlatformImChannelConfigService(dao, crypto, new ObjectMapper());
        String secret = "s".repeat(1024);

        assertTrue(service.updateGroupChat(100L, "DINGTALK",
                groupRequest(true, "shared-key", "shared-robot", secret)).isReady());

        assertTrue(saved.get().getCredentialRef().length() > 1024);
        assertEquals(secret, service.decryptGroupChatCredentials(saved.get()).appSecret());
    }

    @Test
    void updateGroupChatRejectsVersionConflictBeforeWriting() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO existing = groupRow("DINGTALK", 1, "key", "robot");
        existing.setVersion(2);
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(existing);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        UpdateGroupChatConfigRequest request = groupRequest(true, null, null, null);
        request.setVersion(3);

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "DINGTALK", request));

        assertEquals("10409", error.getCode());
        assertEquals("im.channel_version_conflict", error.getMessageKey());
        verify(dao, never()).upsert(any());
    }

    @Test
    void updateGroupChatAllowsApplicationIdentityChangeWithNewCredentials() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigDO existing = groupRow("DINGTALK", 1, "old-key", "old-robot");
        existing.setRouteVersion(3L);
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(existing);
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"old-secret\"}");
        when(secretCrypto.encrypt(anyString())).thenReturn("kms://new");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());

        service.updateGroupChat(100L, "DINGTALK",
                groupRequest(true, "new-key", "new-robot", "new-secret"));

        ArgumentCaptor<PlatformImChannelConfigDO> captor =
                ArgumentCaptor.forClass(PlatformImChannelConfigDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals("new-key", captor.getValue().getAppKey());
        assertEquals("new-robot", captor.getValue().getRobotCode());
        // The identity change starts a new route so pending replies of the old bot are canceled.
        assertEquals(4L, captor.getValue().getRouteVersion());
        assertEquals("kms://new", captor.getValue().getCredentialRef());
    }

    @Test
    void updateGroupChatRequiresNewSecretWhenTheApplicationChanges() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(groupRow("DINGTALK", 1, "old-key", "old-robot"));
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "DINGTALK",
                        groupRequest(true, "new-key", "old-robot", null)));

        assertEquals("10001", error.getCode());
        assertEquals("im.group_chat_new_app_secret_required", error.getMessageKey());
        verify(dao, never()).upsert(any());
    }

    @Test
    void updateGroupChatKeepsTheSameAppSecretForARobotCodeOnlyChange() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigDO existing = groupRow("DINGTALK", 1, "same-key", "old-robot");
        existing.setRouteVersion(5L);
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(existing);
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"same-secret\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());

        service.updateGroupChat(100L, "DINGTALK",
                groupRequest(true, "same-key", "new-robot", null));

        ArgumentCaptor<PlatformImChannelConfigDO> captor =
                ArgumentCaptor.forClass(PlatformImChannelConfigDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals("same-key", captor.getValue().getAppKey());
        assertEquals("new-robot", captor.getValue().getRobotCode());
        assertEquals(6L, captor.getValue().getRouteVersion());
        // A blank reference keeps the stored credential bundle in the database.
        assertNull(captor.getValue().getCredentialRef());
    }

    @Test
    void updateGroupChatFeishuApplicationChangeRequiresFreshToken() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(dao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT"))
                .thenReturn(groupRow("FEISHU", 1, "cli_old", null));
        when(secretCrypto.decrypt("kms://ref"))
                .thenReturn("{\"v\":1,\"appSecret\":\"old\",\"verificationToken\":\"vt\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());
        UpdateGroupChatConfigRequest request = groupRequest(true, null, null, "new-secret");
        request.setAppId("cli_new");

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "FEISHU", request));

        assertEquals("10001", error.getCode());
        assertEquals("im.group_chat_new_app_token_required", error.getMessageKey());
        verify(dao, never()).upsert(any());
    }

    @Test
    void updateGroupChatValidatesFeishuAppId() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        UpdateGroupChatConfigRequest request = groupRequest(true, null, null, "s");
        request.setAppId("not-a-feishu-app");

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "FEISHU", request));

        assertEquals("10001", error.getCode());
        assertEquals("im.feishu_app_id_invalid", error.getMessageKey());
        verify(dao, never()).upsert(any());
    }

    @Test
    void updateGroupChatRejectsIdentityAlreadyUsedByNotificationBot() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO notify = row(true, "notify-key", "kms://n", "notify-robot");
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY")).thenReturn(notify);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "DINGTALK",
                        groupRequest(true, "notify-key", "other-robot", "s")));

        assertEquals("im.notify_identity_conflict", error.getMessageKey());
    }

    @Test
    void updateGroupChatRejectsIdentityChangeAwayFromSharedApp() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO existing = groupRow("DINGTALK", 1, "group-key", "group-robot");
        PlatformImChannelConfigDO notify = row(true, "notify-key", "kms://n", "notify-robot");
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(existing);
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY")).thenReturn(notify);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "DINGTALK",
                        groupRequest(true, "group-key", "notify-robot", "s")));

        assertEquals("im.notify_identity_conflict", error.getMessageKey());
    }

    @Test
    void updateGroupChatFeishuRequiresVerificationTokenWhenEnabled() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        AtomicReference<PlatformImChannelConfigDO> saved = new AtomicReference<>();
        when(dao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT"))
                .thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(dao).upsert(any());
        when(secretCrypto.encrypt(anyString())).thenReturn("kms://bundle");
        when(secretCrypto.decrypt("kms://bundle")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());
        UpdateGroupChatConfigRequest request = groupRequest(true, null, null, "s");
        request.setAppId("cli_shared");

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "FEISHU", request));

        assertEquals("28001", error.getCode());
    }

    @Test
    void updateGroupChatCanDisableWithoutCredentials() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO existing = groupRow("DINGTALK", 1, "key", "robot");
        existing.setRouteVersion(2L);
        AtomicReference<PlatformImChannelConfigDO> saved = new AtomicReference<>();
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenAnswer(invocation -> saved.get() == null ? existing : saved.get());
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(dao).upsert(any());
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto);

        GroupChatConfigVO vo = service.updateGroupChat(100L, "DINGTALK",
                groupRequest(false, null, null, null));

        verify(dao).upsert(argThat(config -> config.getEnabled() == 0
                && config.getRouteVersion() == 3L));
        assertFalse(vo.isDesiredEnabled());
        assertFalse(vo.isReady());
    }

    @Test
    void updateGroupChatMergesCredentialsAndCanClearEncryptKey() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigDO existing = groupRow("FEISHU", 1, "cli_shared", null);
        existing.setCredentialRef("kms://old");
        AtomicReference<PlatformImChannelConfigDO> saved = new AtomicReference<>();
        when(dao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT"))
                .thenAnswer(invocation -> saved.get() == null ? existing : saved.get());
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(dao).upsert(any());
        when(secretCrypto.decrypt("kms://old")).thenReturn(
                "{\"v\":1,\"appSecret\":\"old-secret\",\"verificationToken\":\"old-token\",\"encryptKey\":\"old-key\"}");
        when(secretCrypto.encrypt(anyString())).thenReturn("kms://new");
        when(secretCrypto.decrypt("kms://new")).thenReturn(
                "{\"v\":1,\"appSecret\":\"old-secret\",\"verificationToken\":\"old-token\"}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());
        UpdateGroupChatConfigRequest request = groupRequest(true, null, null, null);
        request.setClearEncryptKey(true);

        GroupChatConfigVO vo = service.updateGroupChat(100L, "FEISHU", request);

        ArgumentCaptor<PlatformImChannelConfigDO> captor =
                ArgumentCaptor.forClass(PlatformImChannelConfigDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals("kms://new", captor.getValue().getCredentialRef());
        assertEquals("cli_shared", captor.getValue().getAppKey());
        assertTrue(vo.isReady());
        assertTrue(vo.isVerificationTokenConfigured());
        assertFalse(vo.isEncryptKeyConfigured());
    }

    @Test
    void updateGroupChatRejectsMissingRequest() {
        PlatformImChannelConfigService service = new PlatformImChannelConfigService(
                mock(PlatformImChannelConfigDao.class), mock(SecretCrypto.class));

        BizException error = assertThrows(BizException.class,
                () -> service.updateGroupChat(100L, "DINGTALK", null));

        assertEquals("im.request_required", error.getMessageKey());
    }

    @Test
    void groupChatLookupsFilterByPurposeAndId() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigDO notify = row(true, "key", "kms", "robot");
        when(dao.findById(1L)).thenReturn(notify);
        PlatformImChannelConfigDO group = groupRow("DINGTALK", 1, "key", "robot");
        when(dao.findById(2L)).thenReturn(group);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));

        assertNull(service.findGroupChatById(null));
        assertNull(service.findGroupChatById(1L));
        assertSame(group, service.findGroupChatById(2L));
        assertTrue(service.isGroupChatEnabled("DINGTALK") == false);
    }

    @Test
    void groupChatEnabledAndReadyReflectDesiredState() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(groupRow("DINGTALK", 0, "key", "robot"));
        assertFalse(service.isGroupChatEnabled("DINGTALK"));
        assertFalse(service.isGroupChatReady("DINGTALK"));

        PlatformImChannelConfigDO enabled = groupRow("DINGTALK", 1, "key", "robot");
        when(dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(enabled);
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        assertTrue(service.isGroupChatEnabled("DINGTALK"));
        assertTrue(service.isGroupChatReady("DINGTALK"));
    }

    @Test
    void decryptHelpersHandleMissingReferences() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());

        assertNull(service.decryptSecret(null));
        assertNull(service.decryptSecret(new PlatformImChannelConfigDO()));
        assertNull(service.decryptGroupChatCredentials(null));
        assertNull(service.decryptGroupChatCredentials(new PlatformImChannelConfigDO()));

        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("DINGTALK");
        config.setCredentialRef("kms://ref");
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        assertEquals("{\"v\":1,\"appSecret\":\"s\"}", service.decryptSecret(config));
        assertEquals("s", service.decryptGroupChatCredentials(config).appSecret());
    }

    @Test
    void decryptGroupChatCredentialsRejectsUnsupportedPayload() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("DINGTALK");
        config.setCredentialRef("kms://ref");
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":9}");
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto, new ObjectMapper());

        BizException error = assertThrows(BizException.class,
                () -> service.decryptGroupChatCredentials(config));
        assertEquals("im.group_credentials_version_unsupported", error.getMessageKey());
    }

    @Test
    void findEnabledRequiresSelectedChannelAndDesiredState() {
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        PlatformImChannelConfigDO enabled = row(true, "key", "kms", "robot");
        when(dao.findByProviderAndPurpose("DINGTALK", "NOTIFY")).thenReturn(enabled);

        assertSame(enabled, service.findEnabled("DINGTALK"));
        assertTrue(service.isReady("DINGTALK"));

        when(dao.selectedProvider()).thenReturn("FEISHU");
        assertNull(service.findEnabled("DINGTALK"));
        assertFalse(service.isReady("DINGTALK"));
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

    private static UpdateGroupChatConfigRequest groupRequest(boolean enabled, String appKey,
                                                             String robotCode, String appSecret) {
        UpdateGroupChatConfigRequest request = new UpdateGroupChatConfigRequest();
        request.setEnabled(enabled);
        request.setAppKey(appKey);
        request.setRobotCode(robotCode);
        request.setAppSecret(appSecret);
        return request;
    }

    private static PlatformImChannelConfigDO row(boolean enabled, String appKey,
                                                  String credentialRef, String robotCode) {
        PlatformImChannelConfigDO row = new PlatformImChannelConfigDO();
        row.setProvider("DINGTALK");
        row.setPurpose("NOTIFY");
        row.setEnabled(enabled ? 1 : 0);
        row.setAppKey(appKey);
        row.setCredentialRef(credentialRef);
        row.setRobotCode(robotCode);
        row.setBaseUrl("https://api.dingtalk.com");
        return row;
    }

    private static PlatformImChannelConfigDO groupRow(String provider, int enabled,
                                                       String appKey, String robotCode) {
        PlatformImChannelConfigDO row = new PlatformImChannelConfigDO();
        row.setProvider(provider);
        row.setPurpose("GROUP_CHAT");
        row.setEnabled(enabled);
        row.setAppKey(appKey);
        row.setRobotCode(robotCode);
        row.setCredentialRef("kms://ref");
        return row;
    }
}
