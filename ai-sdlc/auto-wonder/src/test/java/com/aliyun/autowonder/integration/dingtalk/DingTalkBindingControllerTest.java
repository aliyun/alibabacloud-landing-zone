package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingUpsertRequest;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DingTalkBindingControllerTest {

    private final DingTalkBindingService svc = mock(DingTalkBindingService.class);
    private final DingTalkStreamClientManager streamClientManager =
            mock(DingTalkStreamClientManager.class);
    private final PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
    private final com.aliyun.autowonder.im.PlatformImChannelConfigService imConfigs =
            mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class);
    private final DingTalkBindingController ctrl =
            new DingTalkBindingController(svc, streamClientManager, brandingService, imConfigs);

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void rejectsDingTalkWritesWhenPlatformSelectsFeishu() {
        doThrow(new com.aliyun.autowonder.common.error.BizException(
                com.aliyun.autowonder.common.error.ErrorCode.PARAM_INVALID)).when(imConfigs).requireSelected("DINGTALK");
        assertThrows(com.aliyun.autowonder.common.error.BizException.class, () -> ctrl.create(new BindingUpsertRequest()));
        assertThrows(com.aliyun.autowonder.common.error.BizException.class, () -> ctrl.update(1L, new BindingUpsertRequest()));
        verifyNoInteractions(svc, streamClientManager);
    }

    @Test
    void viewMasksSecretAndBuildsCallbackUrl() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(7L);
        row.setAppKey("ak");
        row.setRobotCode("rc");
        row.setAgentId(3L);
        row.setTransportMode("HTTP_CALLBACK");
        row.setCallbackToken("tok123");
        row.setStreamEnv("ONLINE");
        row.setStatus("ENABLED");
        doAnswer(inv -> {
            BindingView view = inv.getArgument(1);
            view.setStreamStatus("CONNECTED");
            view.setStreamError(null);
            view.setStreamStatusUpdatedAt(1784810000000L);
            return null;
        }).when(svc).applyStreamStatus(eq(row), any(BindingView.class));
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.example.com");

        BindingView v = ctrl.toView(row);

        assertEquals("****", v.getAppSecretMasked());
        assertEquals("https://wonder.example.com/api/integrations/dingtalk/callback?token=tok123",
                v.getCallbackUrl());
        assertEquals("ONLINE", v.getStreamEnv());
        assertEquals("CONNECTED", v.getStreamStatus());
        assertEquals(1784810000000L, v.getStreamStatusUpdatedAt());
    }

    @Test
    void callbackUrlFollowsTheBrandingDomainWithoutRestart() {
        DingtalkRobotBindingDO row = binding(7L, "HTTP_CALLBACK", "ENABLED");
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.example.com");

        assertEquals("https://wonder.example.com/api/integrations/dingtalk/callback?token=tok",
                ctrl.toView(row).getCallbackUrl());

        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://daily.auto-wonder.example.com");

        assertEquals("https://daily.auto-wonder.example.com/api/integrations/dingtalk/callback?token=tok",
                ctrl.toView(row).getCallbackUrl());
    }

    @Test
    void createEnabledStreamBindingStartsClientAfterCreate() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        BindingUpsertRequest req = request("STREAM", "ENABLED");
        DingtalkRobotBindingDO row = binding(7L, "STREAM", "ENABLED");
        when(svc.create(1L, 9L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED")).thenReturn(row);

        BindingView view = ctrl.create(req).getData();

        assertEquals(7L, view.getId());
        InOrder inOrder = inOrder(svc, streamClientManager);
        inOrder.verify(svc).create(1L, 9L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED");
        inOrder.verify(streamClientManager).start(row);
    }

    @Test
    void createDoesNotStartDisabledOrHttpCallbackBinding() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        BindingUpsertRequest httpReq = request("HTTP_CALLBACK", "ENABLED");
        DingtalkRobotBindingDO httpRow = binding(7L, "HTTP_CALLBACK", "ENABLED");
        when(svc.create(1L, 9L, "ak", "sk", "rc", 3L, "HTTP_CALLBACK", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED")).thenReturn(httpRow);
        BindingUpsertRequest disabledReq = request("STREAM", "DISABLED");
        disabledReq.setRobotCode("rc-disabled");
        DingtalkRobotBindingDO disabledRow = binding(8L, "STREAM", "DISABLED");
        disabledRow.setRobotCode("rc-disabled");
        when(svc.create(1L, 9L, "ak", "sk", "rc-disabled", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "DISABLED")).thenReturn(disabledRow);

        ctrl.create(httpReq);
        ctrl.create(disabledReq);

        verify(streamClientManager, never()).start(any());
    }

    @Test
    void updateStopsOldClientThenStartsUpdatedStreamBinding() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        BindingUpsertRequest req = request("STREAM", "ENABLED");
        DingtalkRobotBindingDO oldRow = binding(7L, "STREAM", "ENABLED");
        oldRow.setAppKey("old-ak");
        DingtalkRobotBindingDO updatedRow = binding(7L, "STREAM", "ENABLED");
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.update(1L, 9L, 7L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED")).thenReturn(updatedRow);

        ctrl.update(7L, req);

        InOrder inOrder = inOrder(svc, streamClientManager);
        inOrder.verify(svc).get(1L, 7L);
        inOrder.verify(svc).update(1L, 9L, 7L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED");
        inOrder.verify(streamClientManager).stop(oldRow);
        inOrder.verify(streamClientManager).start(updatedRow);
    }

    @Test
    void updateToDisabledStopsOldClientAndDoesNotStartUpdatedBinding() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        BindingUpsertRequest req = request("STREAM", "DISABLED");
        DingtalkRobotBindingDO oldRow = binding(7L, "STREAM", "ENABLED");
        DingtalkRobotBindingDO updatedRow = binding(7L, "STREAM", "DISABLED");
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.update(1L, 9L, 7L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "DISABLED")).thenReturn(updatedRow);

        ctrl.update(7L, req);

        verify(streamClientManager).stop(oldRow);
        verify(streamClientManager, never()).start(any());
    }

    @Test
    void deleteStopsExistingClientAfterDelete() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        DingtalkRobotBindingDO oldRow = binding(7L, "STREAM", "ENABLED");
        when(svc.get(1L, 7L)).thenReturn(oldRow);

        ctrl.delete(7L);

        InOrder inOrder = inOrder(svc, streamClientManager);
        inOrder.verify(svc).get(1L, 7L);
        inOrder.verify(svc).delete(1L, 7L);
        inOrder.verify(streamClientManager).stop(oldRow);
    }

    private BindingUpsertRequest request(String transportMode, String status) {
        BindingUpsertRequest req = new BindingUpsertRequest();
        req.setAppKey("ak");
        req.setAppSecret("sk");
        req.setRobotCode("rc");
        req.setAgentId(3L);
        req.setTransportMode(transportMode);
        req.setStreamEnv("ONLINE");
        req.setCallbackToken("tok");
        req.setBaseUrl("https://gw");
        req.setRegionId("cn-hangzhou");
        req.setStatus(status);
        return req;
    }

    private DingtalkRobotBindingDO binding(Long id, String transportMode, String status) {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(id);
        row.setAppKey("ak");
        row.setRobotCode("rc");
        row.setAgentId(3L);
        row.setTransportMode(transportMode);
        row.setCallbackToken("tok");
        row.setStreamEnv("ONLINE");
        row.setStatus(status);
        row.setBaseUrl("https://gw");
        row.setRegionId("cn-hangzhou");
        return row;
    }
}
