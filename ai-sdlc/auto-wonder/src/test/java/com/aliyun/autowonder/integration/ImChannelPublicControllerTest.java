package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.im.ImChannelRuntimeStatusService;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.dto.GroupChatPublicConfigVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImChannelPublicControllerTest {

    @Test
    void exposesConfiguredSharedChannelWithoutSecrets() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        ImChannelRuntimeStatusService statusService = mock(ImChannelRuntimeStatusService.class);
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("DINGTALK");
        config.setEnabled(1);
        config.setAppKey("shared-app");
        config.setRobotCode("shared-robot");
        config.setCredentialRef("kms://never-expose");
        when(configService.selectedProvider()).thenReturn("DINGTALK");
        when(configService.findGroupChat("DINGTALK")).thenReturn(config);
        when(configService.isGroupChatReady("DINGTALK")).thenReturn(true);
        when(configService.selectionGeneration()).thenReturn(3L);
        when(statusService.status("DINGTALK")).thenReturn(
                new ImChannelRuntimeStatusService.RuntimeStatus(
                        ImChannelRuntimeStatusService.Status.DRAINING, 2L, 1L, 0L));
        ImChannelPublicController controller =
                new ImChannelPublicController(configService, statusService);

        GroupChatPublicConfigVO vo = controller.groupConfig().getData();

        assertEquals("DINGTALK", vo.getProvider());
        assertTrue(vo.isConfigured());
        assertTrue(vo.isDesiredEnabled());
        assertTrue(vo.isReady());
        assertEquals("shared-app", vo.getAppKey());
        assertEquals("shared-robot", vo.getRobotCode());
        assertEquals(3L, vo.getSelectionGeneration());
        assertEquals("DRAINING", vo.getStatus());
        assertEquals(2L, vo.getDrainingCount());
        assertEquals(1L, vo.getFailedCount());
        assertEquals(0L, vo.getUnknownCount());
    }

    @Test
    void reportsUnconfiguredChannelAsDisabled() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        ImChannelRuntimeStatusService statusService = mock(ImChannelRuntimeStatusService.class);
        when(configService.selectedProvider()).thenReturn("FEISHU");
        when(configService.findGroupChat("FEISHU")).thenReturn(null);
        when(configService.isGroupChatReady("FEISHU")).thenReturn(false);
        when(configService.selectionGeneration()).thenReturn(0L);
        when(statusService.status("FEISHU")).thenReturn(
                new ImChannelRuntimeStatusService.RuntimeStatus(
                        ImChannelRuntimeStatusService.Status.DISABLED, 0L, 0L, 0L));
        ImChannelPublicController controller =
                new ImChannelPublicController(configService, statusService);

        GroupChatPublicConfigVO vo = controller.groupConfig().getData();

        assertFalse(vo.isConfigured());
        assertFalse(vo.isDesiredEnabled());
        assertFalse(vo.isReady());
        assertNull(vo.getAppKey());
        assertNull(vo.getRobotCode());
        assertEquals("DISABLED", vo.getStatus());
    }
}
