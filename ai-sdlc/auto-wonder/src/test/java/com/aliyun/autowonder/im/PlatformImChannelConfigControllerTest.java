package com.aliyun.autowonder.im;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.GroupChatConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImChannelConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImSelectionVO;
import com.aliyun.autowonder.im.dto.SelectImChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateGroupChatConfigRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformImChannelConfigControllerTest {

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void listIsVisibleWithoutPlatformAdminCheck() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        List<PlatformImChannelConfigVO> channels = List.of(channel());
        when(configService.list(10000L)).thenReturn(channels);
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        assertEquals(channels, controller.list().getData());

        verify(configService).list(10000L);
        verifyNoInteractions(systemAdminService);
    }

    @Test
    void updateDingTalkRequiresPlatformAdmin() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        PlatformImChannelConfigVO channel = channel();
        when(configService.updateDingTalk(10000L, request)).thenReturn(channel);
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        assertEquals(channel, controller.updateDingTalk(request).getData());

        verify(systemAdminService).requireSystemAdmin(10000L, "配置协作通知", "access.admin.im_denied");
        verify(configService).updateDingTalk(10000L, request);
    }

    @Test
    void updateFeishuRequiresPlatformAdmin() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        PlatformImChannelConfigVO channel = channel();
        when(configService.update(10000L, "FEISHU", request)).thenReturn(channel);
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        assertEquals(channel, controller.updateFeishu(request).getData());

        verify(systemAdminService).requireSystemAdmin(10000L, "配置协作通知", "access.admin.im_denied");
        verify(configService).update(10000L, "FEISHU", request);
    }

    @Test
    void selectionReadDoesNotRequireAdmin() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        PlatformImSelectionVO selection = new PlatformImSelectionVO();
        selection.setProvider("FEISHU");
        selection.setGeneration(2L);
        when(configService.selection()).thenReturn(selection);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        assertEquals(selection, controller.selection().getData());

        verifyNoInteractions(systemAdminService);
    }

    @Test
    void selectingProviderRequiresAdminAndForwardsGeneration() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        SelectImChannelRequest request = new SelectImChannelRequest();
        request.setProvider("DINGTALK");
        request.setExpectedGeneration(4L);
        PlatformImSelectionVO selection = new PlatformImSelectionVO();
        selection.setProvider("DINGTALK");
        selection.setGeneration(5L);
        when(configService.selectProvider(10000L, "DINGTALK", 4L)).thenReturn(selection);
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        assertEquals(selection, controller.selectProvider(request).getData());

        verify(systemAdminService).requireSystemAdmin(10000L, "配置协作渠道",
                "access.admin.im_selection_denied");
        verify(configService).selectProvider(10000L, "DINGTALK", 4L);
    }

    @Test
    void groupChatReadRequiresAdminAndFallsBackToDisabledStatus() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        GroupChatConfigVO vo = new GroupChatConfigVO();
        vo.setProvider("DINGTALK");
        when(configService.groupChat("DINGTALK")).thenReturn(vo);
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        GroupChatConfigVO result = controller.groupChat("DINGTALK").getData();

        assertEquals("DISABLED", result.getStatus());
        verify(systemAdminService).requireSystemAdmin(10000L, "配置共享群聊机器人",
                "access.admin.im_group_chat_denied");
        verify(configService).groupChat("DINGTALK");
    }

    @Test
    void groupChatReadIncludesRuntimeCountsWhenStatusServicePresent() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        ImChannelRuntimeStatusService statusService = mock(ImChannelRuntimeStatusService.class);
        GroupChatConfigVO vo = new GroupChatConfigVO();
        vo.setProvider("FEISHU");
        when(configService.groupChat("FEISHU")).thenReturn(vo);
        when(statusService.status("FEISHU")).thenReturn(
                new ImChannelRuntimeStatusService.RuntimeStatus(
                        ImChannelRuntimeStatusService.Status.DRAINING, 3L, 2L, 1L));
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService,
                        statusService);

        GroupChatConfigVO result = controller.groupChat("FEISHU").getData();

        assertEquals("DRAINING", result.getStatus());
        assertEquals(3L, result.getDrainingCount());
        assertEquals(2L, result.getFailedCount());
        assertEquals(1L, result.getUnknownCount());
    }

    @Test
    void updateGroupChatRequiresAdminAndReturnsStatus() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        ImChannelRuntimeStatusService statusService = mock(ImChannelRuntimeStatusService.class);
        UpdateGroupChatConfigRequest request = new UpdateGroupChatConfigRequest();
        GroupChatConfigVO vo = new GroupChatConfigVO();
        vo.setProvider("DINGTALK");
        when(configService.updateGroupChat(10000L, "DINGTALK", request)).thenReturn(vo);
        when(statusService.status("DINGTALK")).thenReturn(
                new ImChannelRuntimeStatusService.RuntimeStatus(
                        ImChannelRuntimeStatusService.Status.ACTIVE, 0L, 0L, 0L));
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService,
                        statusService);

        assertEquals("ACTIVE", controller.updateGroupChat("DINGTALK", request).getData()
                .getStatus());

        verify(systemAdminService).requireSystemAdmin(10000L, "配置共享群聊机器人",
                "access.admin.im_group_chat_denied");
        verify(configService).updateGroupChat(10000L, "DINGTALK", request);
    }

    private static PlatformImChannelConfigVO channel() {
        PlatformImChannelConfigVO vo = new PlatformImChannelConfigVO();
        vo.setProvider("DINGTALK");
        vo.setEnabled(true);
        vo.setAppKey("ding-app");
        vo.setRobotCode("ding-robot");
        vo.setBaseUrl("https://api.dingtalk.com");
        vo.setSecretConfigured(true);
        vo.setReady(true);
        return vo;
    }
}
