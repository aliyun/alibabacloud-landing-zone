package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.ImChannelIdentityGuard;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkSharedBindingTest {

    private final DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final ImChannelIdentityGuard guard = mock(ImChannelIdentityGuard.class);
    private final AgentDao agents = mock(AgentDao.class);
    private final SecretCrypto secretCrypto = mock(SecretCrypto.class);
    private final DingTalkBindingService service = new DingTalkBindingService(dao, secretCrypto, null,
            agents, imConfigs, guard);

    @Test
    void createsSharedBindingThatWorksImmediatelyWithoutConfirmation() {
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config());

        DingtalkRobotBindingDO row = service.createShared(1L, 9L, " cid_group ", 7L, null);

        assertEquals("SHARED", row.getBindingMode());
        assertEquals(50L, row.getPlatformConfigId());
        assertEquals("cid_group", row.getGroupId());
        assertEquals("ding-app", row.getAppKey());
        assertEquals("ding-robot", row.getRobotCode());
        assertNull(row.getCredentialRef());
        assertEquals(0L, row.getRouteVersion());
        assertEquals("ENABLED", row.getStatus());
        assertEquals(0, row.getVersion());
        verify(guard).assertGroupAvailable("DINGTALK", 50L, "cid_group", null);
        verify(dao).insert(row);
    }

    @Test
    void sharedCreationFailsClosedWithoutPlatformConfiguration() {
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(null);

        BizException notConfigured = assertThrows(BizException.class,
                () -> service.createShared(1L, 9L, "cid_group", 7L, null));
        assertEquals("integration.group_chat_not_configured", notConfigured.getMessageKey());

        when(agents.findById(7L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.createShared(1L, 9L, "cid_group", 7L, null));
        assertThrows(BizException.class, () -> service.createShared(1L, 9L, "cid_group", null, null));
        when(agents.findById(8L)).thenReturn(agent(2L));
        assertThrows(BizException.class, () -> service.createShared(1L, 9L, "cid_group", 8L, null));
        verify(dao, never()).insert(any());
    }

    @Test
    void sharedCreationRejectsDisabledOrIncompletePlatformConfiguration() {
        when(agents.findById(7L)).thenReturn(agent(1L));

        PlatformImChannelConfigDO disabled = config();
        disabled.setEnabled(0);
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(disabled);
        assertNotConfigured();

        PlatformImChannelConfigDO missingAppKey = config();
        missingAppKey.setAppKey(" ");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(missingAppKey);
        assertNotConfigured();

        PlatformImChannelConfigDO missingRobotCode = config();
        missingRobotCode.setRobotCode(null);
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(missingRobotCode);
        assertNotConfigured();

        PlatformImChannelConfigDO missingCredential = config();
        missingCredential.setCredentialRef("");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(missingCredential);
        assertNotConfigured();

        verify(dao, never()).insert(any());
    }

    private void assertNotConfigured() {
        BizException error = assertThrows(BizException.class,
                () -> service.createShared(1L, 9L, "cid_group", 7L, null));
        assertEquals("integration.group_chat_not_configured", error.getMessageKey());
    }

    @Test
    void duplicateSharedGroupIsReportedAsConflict() {
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config());
        when(dao.insert(any())).thenThrow(new DuplicateKeyException("dup"));

        BizException conflict = assertThrows(BizException.class,
                () -> service.createShared(1L, 9L, "cid_group", 7L, "DISABLED"));
        assertEquals("integration.group_already_bound", conflict.getMessageKey());
    }

    @Test
    void updatesSharedGroupWithoutAnyConfirmationStep() {
        when(agents.findById(7L)).thenReturn(agent(1L));
        DingtalkRobotBindingDO existing = sharedRow();
        when(dao.findById(1L, 5L)).thenReturn(existing);
        when(dao.updateShared(existing)).thenReturn(1);

        DingtalkRobotBindingDO updated = service.updateShared(1L, 9L, 5L, "cid_two", 7L,
                "DISABLED", 4);

        assertEquals("cid_two", updated.getGroupId());
        assertEquals("DISABLED", updated.getStatus());
        assertEquals(4L, updated.getRouteVersion());
        assertEquals(5, updated.getVersion());
        verify(guard).assertGroupAvailable("DINGTALK", 50L, "cid_two", 5L);
    }

    @Test
    void sharedUpdateRejectsStaleVersionLegacyRowsAndLostRace() {
        when(agents.findById(7L)).thenReturn(agent(1L));
        when(dao.findById(1L, 5L)).thenReturn(sharedRow());

        BizException noVersion = assertThrows(BizException.class,
                () -> service.updateShared(1L, 9L, 5L, "cid_two", 7L, null, null));
        assertEquals("integration.binding_version_conflict", noVersion.getMessageKey());

        BizException stale = assertThrows(BizException.class,
                () -> service.updateShared(1L, 9L, 5L, "cid_two", 7L, null, 3));
        assertEquals("integration.binding_version_conflict", stale.getMessageKey());

        DingtalkRobotBindingDO legacy = new DingtalkRobotBindingDO();
        legacy.setId(6L);
        legacy.setBindingMode("LEGACY");
        when(dao.findById(1L, 6L)).thenReturn(legacy);
        BizException immutable = assertThrows(BizException.class,
                () -> service.updateShared(1L, 9L, 6L, "cid_two", 7L, null, 3));
        assertEquals("integration.binding_mode_immutable", immutable.getMessageKey());

        when(dao.findById(1L, 7L)).thenReturn(null);
        BizException missing = assertThrows(BizException.class,
                () -> service.updateShared(1L, 9L, 7L, "cid_two", 7L, null, 3));
        assertEquals("integration.dingtalk_binding_missing", missing.getMessageKey());

        when(dao.findById(1L, 5L)).thenReturn(sharedRow());
        when(dao.updateShared(any())).thenReturn(0);
        BizException race = assertThrows(BizException.class,
                () -> service.updateShared(1L, 9L, 5L, null, null, " ", 4));
        assertEquals("integration.binding_version_conflict", race.getMessageKey());
    }

    @Test
    void sharedSecretResolutionFailsClosedAndCachesByConfigVersion() {
        DingtalkRobotBindingDO shared = sharedRow();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(imConfigs.decryptGroupChatCredentials(any()))
                .thenReturn(new ImGroupChatCredentials("ding-secret", null, null));
        assertEquals("ding-secret", service.decryptSecret(shared));

        var wrongProvider = config();
        wrongProvider.setProvider("FEISHU");
        when(imConfigs.findGroupChatById(50L)).thenReturn(wrongProvider);
        assertThrows(IllegalStateException.class, () -> service.decryptSecret(shared));

        // The identity was replaced in place: the binding keeps working from the current config.
        var changedIdentity = config();
        changedIdentity.setRobotCode("other-robot");
        when(imConfigs.findGroupChatById(50L)).thenReturn(changedIdentity);
        assertEquals("ding-secret", service.decryptSecret(shared));

        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(imConfigs.decryptGroupChatCredentials(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.decryptSecret(shared));
        when(imConfigs.decryptGroupChatCredentials(any()))
                .thenReturn(new ImGroupChatCredentials(" ", null, null));
        assertThrows(IllegalStateException.class, () -> service.decryptSecret(shared));
        when(imConfigs.findGroupChatById(50L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.decryptSecret(shared));

        assertNull(service.findShared(50L, " "));
        assertNull(service.findShared(null, "cid_group"));
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(shared);
        assertEquals(shared, service.findShared(50L, " cid_group "));
    }

    @Test
    void legacyRowsStillDecryptTheirOwnSecret() {
        DingtalkRobotBindingDO legacy = new DingtalkRobotBindingDO();
        legacy.setId(6L);
        legacy.setBindingMode("LEGACY");
        legacy.setCredentialRef("kms://legacy");
        when(secretCrypto.decrypt("kms://legacy")).thenReturn("legacy-secret");
        assertEquals("legacy-secret", service.decryptSecret(legacy));
        assertFalse(service.isShared(legacy));
        assertTrue(service.isShared(sharedRow()));
        assertFalse(service.isShared(null));
    }

    private static DingtalkRobotBindingDO sharedRow() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(5L);
        row.setTenantId(1L);
        row.setBindingMode("SHARED");
        row.setPlatformConfigId(50L);
        row.setGroupId("cid_group");
        row.setAppKey("ding-app");
        row.setRobotCode("ding-robot");
        row.setAgentId(7L);
        row.setRouteVersion(3L);
        row.setStatus("ENABLED");
        row.setVersion(4);
        return row;
    }

    private static AgentDO agent(long tenantId) {
        AgentDO agent = new AgentDO();
        agent.setId(7L);
        agent.setTenantId(tenantId);
        agent.setIsDeleted(0);
        return agent;
    }

    private static PlatformImChannelConfigDO config() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("DINGTALK");
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("ding-app");
        config.setRobotCode("ding-robot");
        config.setCredentialRef("cipher-shared");
        config.setEnabled(1);
        config.setRouteVersion(6L);
        config.setVersion(3);
        return config;
    }
}
