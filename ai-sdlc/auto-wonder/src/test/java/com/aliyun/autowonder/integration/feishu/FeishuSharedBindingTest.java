package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.ImChannelIdentityGuard;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuSharedBindingTest {

    private final PlatformImChannelConfigService imConfigs = mock(PlatformImChannelConfigService.class);
    private final ImChannelIdentityGuard guard = mock(ImChannelIdentityGuard.class);

    @Test
    void createsSharedBindingThatWorksImmediatelyFromPlatformCredentials() {
        var f = new FeishuTestSupport();
        var service = sharedService(f);
        var config = config();
        when(imConfigs.findGroupChat("FEISHU")).thenReturn(config);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("shared-secret", "shared-token", null));

        FeishuBinding row = service.save(1L, 9L, null, sharedRequest("oc_group", null));

        assertEquals("SHARED", row.getBindingMode());
        assertEquals(50L, row.getPlatformConfigId());
        assertEquals("oc_group", row.getGroupId());
        assertEquals("cli_shared", row.getAppId());
        assertNull(row.getCredentialRef());
        assertEquals(0L, row.getRouteVersion());
        assertEquals(0, row.getVersion());
        assertEquals(9L, row.getModifierId());
        verify(guard).assertGroupAvailable("FEISHU", 50L, "oc_group", null);

        FeishuBinding persisted = f.dao.find(1L, row.getId());
        assertEquals("SHARED", persisted.getBindingMode());
        assertEquals(row.getGroupId(), persisted.getGroupId());
        assertEquals(new FeishuBindingService.Secrets("shared-secret", "shared-token", null),
                service.secrets(persisted));
        assertFalse(service.encryptKeyConfigured(persisted));
        assertEquals("SHARED:50:3", service.credentialCacheKey(persisted));
        assertEquals(row.getId(), service.findShared(50L, "oc_group").getId());
        assertNull(service.findShared(50L, " "));
    }

    @Test
    void rejectsLegacyPayloadsOnTheSharedPath() {
        var f = new FeishuTestSupport();
        var service = sharedService(f);

        BizException legacyFields = assertThrows(BizException.class, () -> service.createShared(1L, 9L,
                new FeishuBindingService.Request("cli_shared", null, null, null, false, 7L,
                        null, null, "oc_group", "SHARED")));
        assertEquals("integration.shared_fields_forbidden", legacyFields.getMessageKey());

        BizException legacyMode = assertThrows(BizException.class, () -> service.createShared(1L, 9L,
                new FeishuBindingService.Request(null, null, null, null, false, 7L,
                        null, null, "oc_group", "LEGACY")));
        assertEquals("integration.shared_fields_forbidden", legacyMode.getMessageKey());
    }

    @Test
    void rejectsSharedCreationWhenPlatformConfigIsDisabledOrIncomplete() {
        var f = new FeishuTestSupport();
        var service = sharedService(f);

        PlatformImChannelConfigDO disabled = config();
        disabled.setEnabled(0);
        when(imConfigs.findGroupChat("FEISHU")).thenReturn(disabled);
        assertNotConfigured(service);

        PlatformImChannelConfigDO missingAppKey = config();
        missingAppKey.setAppKey(" ");
        when(imConfigs.findGroupChat("FEISHU")).thenReturn(missingAppKey);
        assertNotConfigured(service);

        PlatformImChannelConfigDO missingCredential = config();
        missingCredential.setCredentialRef(null);
        when(imConfigs.findGroupChat("FEISHU")).thenReturn(missingCredential);
        assertNotConfigured(service);

        when(imConfigs.findGroupChat("FEISHU")).thenReturn(null);
        assertNotConfigured(service);
    }

    private static void assertNotConfigured(FeishuBindingService service) {
        BizException error = assertThrows(BizException.class,
                () -> service.createShared(1L, 9L, sharedRequest("oc_group", null)));
        assertEquals("integration.group_chat_not_configured", error.getMessageKey());
    }

    @Test
    void duplicateGroupIsRejectedAndGroupChangeKeepsTheBindingUsable() {
        var f = new FeishuTestSupport();
        var service = sharedService(f);
        var config = config();
        when(imConfigs.findGroupChat("FEISHU")).thenReturn(config);

        FeishuBinding row = service.save(1L, 9L, null, sharedRequest("oc_group", null));
        BizException duplicate = assertThrows(BizException.class,
                () -> service.save(1L, 9L, null, sharedRequest("oc_group", null)));
        assertEquals("integration.group_already_bound", duplicate.getMessageKey());

        FeishuBinding updated = service.save(1L, 9L, row.getId(),
                new FeishuBindingService.Request(null, null, null, null, false, 7L,
                        null, row.getVersion(), "oc_group2", "SHARED"));

        assertEquals("oc_group2", updated.getGroupId());
        assertEquals(1L, updated.getRouteVersion());
        assertEquals(1, updated.getVersion());
        verify(guard).assertGroupAvailable("FEISHU", 50L, "oc_group2", row.getId());

        BizException stale = assertThrows(BizException.class,
                () -> service.save(1L, 9L, row.getId(), new FeishuBindingService.Request(
                        null, null, null, null, false, 7L, null, row.getVersion(),
                        "oc_group3", "SHARED")));
        assertEquals("integration.feishu_binding_changed", stale.getMessageKey());
    }

    @Test
    void sharedSecretsFailClosedWhenPlatformConfigIsUnavailable() {
        var f = new FeishuTestSupport();
        var service = sharedService(f);
        var config = config();
        when(imConfigs.findGroupChat("FEISHU")).thenReturn(config);
        FeishuBinding row = service.save(1L, 9L, null, sharedRequest("oc_group", null));

        when(imConfigs.findGroupChatById(50L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.secrets(row));

        var wrongProvider = config();
        wrongProvider.setProvider("DINGTALK");
        when(imConfigs.findGroupChatById(50L)).thenReturn(wrongProvider);
        assertThrows(IllegalStateException.class, () -> service.secrets(row));
        assertFalse(service.encryptKeyConfigured(row));
        assertEquals("SHARED:50:3", service.credentialCacheKey(row));

        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(imConfigs.decryptGroupChatCredentials(config)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.secrets(row));
        assertThrows(IllegalStateException.class, () -> service.secrets((PlatformImChannelConfigDO) null));
    }

    private FeishuBindingService sharedService(FeishuTestSupport f) {
        return new FeishuBindingService(f.dao, f.keys, f.agents, f.json, imConfigs, guard);
    }

    private static FeishuBindingService.Request sharedRequest(String groupId, Integer version) {
        return new FeishuBindingService.Request(null, null, null, null, false, 7L,
                "ENABLED", version, groupId, "SHARED");
    }

    private static PlatformImChannelConfigDO config() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("FEISHU");
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("cli_shared");
        config.setCredentialRef("cipher-shared");
        config.setEnabled(1);
        config.setRouteVersion(4L);
        config.setVersion(3);
        return config;
    }
}
