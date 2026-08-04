package com.aliyun.autowonder.im;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.PlatformImChannelConfigVO;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
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
    void listIsVisibleWithoutFirstActiveUserCheck() {
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
    void updateDingTalkRequiresFirstActiveUser() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        PlatformImChannelConfigVO channel = channel();
        when(configService.updateDingTalk(10000L, request)).thenReturn(channel);
        AutoWonderContext.get().setUserId(10000L);
        PlatformImChannelConfigController controller =
                new PlatformImChannelConfigController(configService, systemAdminService);

        assertEquals(channel, controller.updateDingTalk(request).getData());

        verify(systemAdminService).requireFirstActiveUser(10000L, "管理协作通知");
        verify(configService).updateDingTalk(10000L, request);
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
