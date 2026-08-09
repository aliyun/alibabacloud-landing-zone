package com.aliyun.autowonder.im;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserImIdentityServiceTest {

    @Test
    void globalIdentityUpsertIsTrimmedAndHasNoTenant() throws Exception {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        when(dao.listByUserId(200L)).thenReturn(List.of(identity(200L, "DINGTALK", "staff-001")));
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        service.update(200L, "dingtalk", " staff-001 ");

        ArgumentCaptor<UserImIdentityDO> captor = ArgumentCaptor.forClass(UserImIdentityDO.class);
        verify(dao).upsert(captor.capture());
        assertEquals(200L, captor.getValue().getUserId());
        assertEquals("DINGTALK", captor.getValue().getProvider());
        assertEquals("staff-001", captor.getValue().getExternalUserId());
        assertThrows(NoSuchMethodException.class, () -> UserImIdentityDO.class.getMethod("getTenantId"));

        try (InputStream stream = getClass().getResourceAsStream("/mapping/UserImIdentityDao.xml")) {
            assertNotNull(stream);
            String mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(mapper.contains("tenant_id"));
            assertTrue(mapper.contains("is_deleted = 0"));
        }
    }

    @Test
    void blankIdentitySoftDeletesExistingRecord() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        var result = service.update(200L, "DINGTALK", "  ");

        verify(dao).softDelete(200L, "DINGTALK", 200L);
        verify(dao, never()).upsert(any());
        assertFalse(result.isConfigured());
        assertNull(result.getExternalUserId());
    }

    @Test
    void capabilityRequiresIdentityAndReadyPlatformChannel() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        when(dao.find(200L, "DINGTALK")).thenReturn(identity(200L, "DINGTALK", "staff-001"));
        when(channelService.isReady("DINGTALK")).thenReturn(true);
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        UserImIdentityVO capability = service.capability(200L, "dingtalk");

        assertEquals("DINGTALK", capability.getProvider());
        assertEquals("staff-001", capability.getExternalUserId());
        assertTrue(capability.isConfigured());
        assertTrue(capability.isPlatformReady());
        assertTrue(capability.isTestAvailable());
    }

    @Test
    void capabilityDisablesTestWhenIdentityIsMissing() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        when(channelService.isReady("DINGTALK")).thenReturn(true);
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        UserImIdentityVO capability = service.capability(200L, "DINGTALK");

        assertEquals("DINGTALK", capability.getProvider());
        assertNull(capability.getExternalUserId());
        assertFalse(capability.isConfigured());
        assertTrue(capability.isPlatformReady());
        assertFalse(capability.isTestAvailable());
    }

    @Test
    void updateRejectsExternalIdentityLongerThanDatabaseColumn() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        BizException error = assertThrows(BizException.class,
                () -> service.update(200L, "DINGTALK", "u".repeat(257)));

        assertEquals("10001", error.getCode());
        verifyNoInteractions(dao);
    }

    @Test
    void sendTestUsesSavedIdentityAndCurrentBrandName() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        ImProviderRegistry registry = mock(ImProviderRegistry.class);
        ImProvider provider = mock(ImProvider.class);
        PlatformBrandingService branding = mock(PlatformBrandingService.class);
        when(dao.find(200L, "DINGTALK"))
                .thenReturn(identity(200L, "DINGTALK", "staff-001"));
        when(channelService.isReady("DINGTALK")).thenReturn(true);
        when(registry.require("DINGTALK")).thenReturn(provider);
        when(branding.publicConfig()).thenReturn(new PlatformBrandingVO(
                "Acme Platform", "/logo.png", "teal", "#008080",
                "https://example.com", "https://example.com/api/mcp", "0.2.125", "x.x.x", false));
        UserImIdentityService service =
                new UserImIdentityService(dao, channelService, registry, branding);

        service.sendTest(200L, "dingtalk");

        ArgumentCaptor<ImSendCommand> command = ArgumentCaptor.forClass(ImSendCommand.class);
        verify(provider).send(command.capture());
        assertEquals("DINGTALK", command.getValue().provider());
        assertEquals("staff-001", command.getValue().externalUserId());
        assertEquals("Acme Platform 协作通知测试成功", command.getValue().title());
        assertTrue(command.getValue().markdown().contains("Acme Platform 协作通知测试成功"));
    }

    @Test
    void sendTestRejectsMissingIdentityAndUnavailableChannel() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        ImProviderRegistry registry = mock(ImProviderRegistry.class);
        PlatformBrandingService branding = mock(PlatformBrandingService.class);
        UserImIdentityService service =
                new UserImIdentityService(dao, channelService, registry, branding);

        BizException missing = assertThrows(BizException.class,
                () -> service.sendTest(200L, "dingtalk"));
        assertEquals("28002", missing.getCode());

        when(dao.find(200L, "DINGTALK"))
                .thenReturn(identity(200L, "DINGTALK", "staff-001"));
        when(channelService.isReady("DINGTALK")).thenReturn(false);
        BizException unavailable = assertThrows(BizException.class,
                () -> service.sendTest(200L, "dingtalk"));
        assertEquals("28001", unavailable.getCode());
        verifyNoInteractions(registry, branding);
    }

    @Test
    void sendTestMapsProviderFailureToSafeBusinessError() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        ImProviderRegistry registry = mock(ImProviderRegistry.class);
        ImProvider provider = mock(ImProvider.class);
        PlatformBrandingService branding = mock(PlatformBrandingService.class);
        when(dao.find(200L, "DINGTALK"))
                .thenReturn(identity(200L, "DINGTALK", "staff-secret-id"));
        when(channelService.isReady("DINGTALK")).thenReturn(true);
        when(registry.require("DINGTALK")).thenReturn(provider);
        when(branding.publicConfig()).thenReturn(new PlatformBrandingVO(
                "AutoWonder", "/logo.png", "teal", "#008080",
                "https://example.com", "https://example.com/api/mcp", "0.2.125", "x.x.x", false));
        doThrow(new ImDeliveryException("DINGTALK", false, "authFailed", "req-1",
                new IllegalStateException("secret-value staff-secret-id")))
                .when(provider).send(any());
        UserImIdentityService service =
                new UserImIdentityService(dao, channelService, registry, branding);

        BizException error = assertThrows(BizException.class,
                () -> service.sendTest(200L, "dingtalk"));

        assertEquals("28003", error.getCode());
        assertFalse(error.getMessage().contains("secret-value"));
        assertFalse(error.getMessage().contains("staff-secret-id"));
    }

    private static UserImIdentityDO identity(long userId, String provider, String externalUserId) {
        UserImIdentityDO row = new UserImIdentityDO();
        row.setUserId(userId);
        row.setProvider(provider);
        row.setExternalUserId(externalUserId);
        return row;
    }
}
