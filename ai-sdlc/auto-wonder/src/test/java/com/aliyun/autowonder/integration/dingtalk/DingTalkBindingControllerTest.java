package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
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
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final DingTalkBindingController ctrl =
            new DingTalkBindingController(svc, streamClientManager, brandingService, imConfigs);

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void createRequiresPlatformSelectedChannel() {
        doThrow(new BizException(com.aliyun.autowonder.common.error.ErrorCode.PARAM_INVALID))
                .when(imConfigs).requireSelected("DINGTALK");

        assertThrows(BizException.class, () -> ctrl.create(new BindingUpsertRequest()));
        verifyNoInteractions(svc, streamClientManager);
    }

    @Test
    void createRejectsLegacyCredentialsAndMode() {
        BindingUpsertRequest withCredentials = sharedRequest("cid_group");
        withCredentials.setAppKey("legacy-app-key");
        assertForbidden(() -> ctrl.create(withCredentials));

        BindingUpsertRequest legacyMode = sharedRequest("cid_group");
        legacyMode.setBindingMode("LEGACY");
        assertForbidden(() -> ctrl.create(legacyMode));

        assertForbidden(() -> ctrl.create(null));
        verify(svc, never()).createShared(any(), any(), any(), any(), any());
    }

    @Test
    void createSharedBindingNeverStartsWithLegacyStream() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        DingtalkRobotBindingDO row = sharedBinding();
        when(svc.isShared(row)).thenReturn(true);
        when(svc.createShared(1L, 9L, "cid_group", 3L, "ENABLED")).thenReturn(row);

        BindingView view = ctrl.create(sharedRequest("cid_group")).getData();

        assertEquals("SHARED", view.getBindingMode());
        assertEquals(8L, view.getPlatformConfigId());
        assertEquals("cid_group", view.getGroupId());
        assertNull(view.getAppSecretMasked());
        verify(svc).createShared(1L, 9L, "cid_group", 3L, "ENABLED");
        verifyNoInteractions(streamClientManager);
    }

    @Test
    void legacyUpdateRejectsBindingModeConversion() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        DingtalkRobotBindingDO oldRow = legacyBinding();
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.isShared(oldRow)).thenReturn(false);
        BindingUpsertRequest request = legacyRequest("STREAM", "ENABLED");
        request.setBindingMode("SHARED");

        BizException error = assertThrows(BizException.class, () -> ctrl.update(7L, request));

        assertEquals("integration.binding_mode_immutable", error.getMessageKey());
        verify(svc, never()).update(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void legacyUpdateRestartsStreamAfterSuccessfulSave() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        DingtalkRobotBindingDO oldRow = legacyBinding();
        DingtalkRobotBindingDO updatedRow = legacyBinding();
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.isShared(oldRow)).thenReturn(false);
        when(svc.isShared(updatedRow)).thenReturn(false);
        when(svc.update(1L, 9L, 7L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED")).thenReturn(updatedRow);

        BindingView view = ctrl.update(7L, legacyRequest("STREAM", "ENABLED")).getData();

        assertEquals("LEGACY", view.getBindingMode());
        assertEquals("****", view.getAppSecretMasked());
        InOrder order = inOrder(svc, streamClientManager);
        order.verify(svc).get(1L, 7L);
        order.verify(svc).update(1L, 9L, 7L, "ak", "sk", "rc", 3L, "STREAM", "ONLINE",
                "tok", "https://gw", "cn-hangzhou", "ENABLED");
        order.verify(streamClientManager).stop(oldRow);
        order.verify(streamClientManager).start(updatedRow);
    }

    @Test
    void sharedUpdateOnlyChangesGroupAgentAndStatus() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        DingtalkRobotBindingDO oldRow = sharedBinding();
        DingtalkRobotBindingDO updatedRow = sharedBinding();
        updatedRow.setGroupId("cid_new");
        updatedRow.setVersion(2);
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.isShared(oldRow)).thenReturn(true);
        when(svc.isShared(updatedRow)).thenReturn(true);
        when(svc.updateShared(1L, 9L, 7L, "cid_new", 4L, "DISABLED", 1)).thenReturn(updatedRow);
        BindingUpsertRequest request = sharedRequest("cid_new");
        request.setAgentId(4L);
        request.setStatus("DISABLED");
        request.setVersion(1);

        BindingView view = ctrl.update(7L, request).getData();

        assertEquals("cid_new", view.getGroupId());
        assertEquals(2, view.getVersion());
        verify(svc).updateShared(1L, 9L, 7L, "cid_new", 4L, "DISABLED", 1);
        verifyNoInteractions(streamClientManager);
    }

    @Test
    void sharedUpdateRejectsLegacyPayload() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        DingtalkRobotBindingDO oldRow = sharedBinding();
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.isShared(oldRow)).thenReturn(true);
        BindingUpsertRequest request = sharedRequest("cid_group");
        request.setAppSecret("legacy-secret");

        assertForbidden(() -> ctrl.update(7L, request));
        verify(svc, never()).updateShared(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteSharedBindingDoesNotStopLegacyStream() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        DingtalkRobotBindingDO oldRow = sharedBinding();
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.isShared(oldRow)).thenReturn(true);

        ctrl.delete(7L);

        verify(svc).delete(1L, 7L);
        verifyNoInteractions(streamClientManager);
    }

    @Test
    void deleteLegacyBindingStopsStream() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        DingtalkRobotBindingDO oldRow = legacyBinding();
        when(svc.get(1L, 7L)).thenReturn(oldRow);
        when(svc.isShared(oldRow)).thenReturn(false);

        ctrl.delete(7L);

        verify(streamClientManager).stop(oldRow);
    }

    @Test
    void getMissingBindingIsReported() {
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        when(svc.get(1L, 7L)).thenReturn(null);

        assertThrows(com.aliyun.autowonder.common.error.KeyedIllegalArgumentException.class,
                () -> ctrl.get(7L));
    }

    @Test
    void viewMasksSecretAndBuildsLegacyCallbackUrl() {
        DingtalkRobotBindingDO row = legacyBinding();
        doAnswer(inv -> {
            BindingView view = inv.getArgument(1);
            view.setStreamStatus("CONNECTED");
            view.setStreamStatusUpdatedAt(1784810000000L);
            return null;
        }).when(svc).applyStreamStatus(eq(row), any(BindingView.class));
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.example.com");

        BindingView v = ctrl.toView(row);

        assertEquals("****", v.getAppSecretMasked());
        assertEquals("https://wonder.example.com/api/integrations/dingtalk/callback?token=tok",
                v.getCallbackUrl());
        assertEquals("CONNECTED", v.getStreamStatus());
        assertEquals(1784810000000L, v.getStreamStatusUpdatedAt());
    }

    @Test
    void sharedViewOmitsLegacySecretAndCallback() {
        DingtalkRobotBindingDO row = sharedBinding();
        when(svc.isShared(row)).thenReturn(true);

        BindingView v = ctrl.toView(row);

        assertEquals("SHARED", v.getBindingMode());
        assertNull(v.getAppSecretMasked());
        assertNull(v.getCallbackUrl());
        assertEquals(5L, v.getRouteVersion());
        verify(svc, never()).applyStreamStatus(any(), any());
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable action) {
        BizException error = assertThrows(BizException.class, action);
        assertEquals("10001", error.getCode());
        assertEquals("integration.shared_fields_forbidden", error.getMessageKey());
    }

    private static BindingUpsertRequest sharedRequest(String groupId) {
        BindingUpsertRequest req = new BindingUpsertRequest();
        req.setGroupId(groupId);
        req.setAgentId(3L);
        req.setStatus("ENABLED");
        return req;
    }

    private static BindingUpsertRequest legacyRequest(String transportMode, String status) {
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

    private static DingtalkRobotBindingDO legacyBinding() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(7L);
        row.setTenantId(1L);
        row.setBindingMode("LEGACY");
        row.setAppKey("ak");
        row.setRobotCode("rc");
        row.setAgentId(3L);
        row.setTransportMode("STREAM");
        row.setCallbackToken("tok");
        row.setStreamEnv("ONLINE");
        row.setStatus("ENABLED");
        row.setBaseUrl("https://gw");
        row.setRegionId("cn-hangzhou");
        row.setRouteVersion(0L);
        return row;
    }

    private static DingtalkRobotBindingDO sharedBinding() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(7L);
        row.setTenantId(1L);
        row.setBindingMode("SHARED");
        row.setPlatformConfigId(8L);
        row.setGroupId("cid_group");
        row.setAppKey("shared-ak");
        row.setRobotCode("shared-rc");
        row.setAgentId(3L);
        row.setTransportMode("STREAM");
        row.setStreamEnv("ONLINE");
        row.setStatus("ENABLED");
        row.setRouteVersion(5L);
        row.setVersion(1);
        return row;
    }
}
