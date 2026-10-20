package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.*;
import com.aliyun.autowonder.access.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutorRestartServiceTest {
    ExecutorDao dao = mock(ExecutorDao.class);
    ExecutorRegistry registry = mock(ExecutorRegistry.class);
    PresenceManager presence = mock(PresenceManager.class);
    RedisManager redis = mock(RedisManager.class);
    SessionRegistry sessions = mock(SessionRegistry.class);
    ExecutorLaunchConfigService launchConfigs = mock(ExecutorLaunchConfigService.class);
    com.aliyun.autowonder.branding.PlatformBrandingService branding = mock(com.aliyun.autowonder.branding.PlatformBrandingService.class);
    ExecutorRestartService service = new ExecutorRestartService(dao, registry, presence, redis, sessions, launchConfigs, branding);
    Map<String, String> values = new HashMap<>();
    ExecutorSession session = new ExecutorSession(7, 8, 1, null);
    @BeforeEach void setup() {
        ExecutorDO executor = new ExecutorDO(); executor.setId(7L); executor.setTenantId(1L);
        executor.setLastStartedAt(Date.from(Instant.now().minusSeconds(3600)));
        when(dao.findById(7L)).thenReturn(executor);
        when(registry.isOnline(7L)).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(true);
        when(redis.setIfAbsent(anyString(), anyString(), anyLong())).thenAnswer(a -> values.putIfAbsent(a.getArgument(0), a.getArgument(1)) == null);
        when(redis.getString(anyString())).thenAnswer(a -> values.get(a.getArgument(0)));
        doAnswer(a -> { values.put(a.getArgument(0), a.getArgument(1)); return null; }).when(redis).setWithExpire(anyString(), anyString(), anyLong());
        doAnswer(a -> { values.remove(a.getArgument(0)); return null; }).when(redis).del(anyString());
    }
    @Test void explicitPoliciesAreValidatedAndRequireTheNewCapability() {
        var config = new ExecutorLaunchConfigService.LaunchConfig(); config.memoryMode = "platform";
        when(launchConfigs.requireCompleteConfig(7L, 1L, 2)).thenReturn(config);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        BizException invalid = assertThrows(BizException.class, () -> service.applyLaunchCommand(7, 1, 3, 2, "force"));
        assertEquals("Invalid restart mode for applying a startup command.", com.aliyun.autowonder.common.result.Result.fail(invalid).getMessage());
        when(presence.supportsProtocolFeature(7L, "EXECUTOR_RESTART_POLICY_V1")).thenReturn(false);
        for (String mode : List.of("FORCE", "WHEN_IDLE")) {
            BizException legacy = assertThrows(BizException.class, () -> service.applyLaunchCommand(7, 1, 3, 2, mode));
            assertEquals("This client does not support restart policies. Upgrade the client first.", com.aliyun.autowonder.common.result.Result.fail(legacy).getMessage());
        }
        verifyNoInteractions(redis);
    }

    @Test void idleProgressRenewsOwnershipAndResetsTheRestartDeadlineWithoutRewritingHistory() {
        var config = new ExecutorLaunchConfigService.LaunchConfig(); config.memoryMode = "platform";
        when(launchConfigs.requireCompleteConfig(7L, 1L, 2)).thenReturn(config);
        when(redis.eval(anyString(), anyList(), anyList())).thenAnswer(a -> {
            List<String> keys = a.getArgument(1); List<String> args = a.getArgument(2);
            return Objects.equals(values.get(keys.getFirst()), args.getFirst()) ? 1L : 0L;
        });
        JSONObject request = service.applyLaunchCommand(7, 1, 3, 2, "WHEN_IDLE");
        var wire = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).publish(anyString(), wire.capture());
        assertEquals("WHEN_IDLE", JSONObject.parseObject(wire.getValue()).getJSONObject("launch").getString("restartMode"));
        assertEquals(request.getString("requestId"), values.get(ExecutorRestartService.lock(7)));
        JSONObject progress = new JSONObject(); progress.put("requestId", request.getString("requestId"));
        progress.put("status", "WAITING_IDLE"); progress.put("message", "raw client progress");
        service.onResult(session, progress);
        JSONObject state = JSONObject.parseObject(values.get(ExecutorRestartService.key(7)));
        state.put("issuedAt", Instant.now().minusSeconds(7200).toString());
        values.put(ExecutorRestartService.key(7), state.toJSONString());
        assertEquals("WAITING_IDLE", service.status(7).getString("status"));
        assertThrows(BizException.class, () -> service.applyLaunchCommand(7, 1, 3, 2, "FORCE"));
        progress.put("status", "RESTARTING"); service.onResult(session, progress);
        assertEquals("RESTARTING", service.status(7).getString("status"));
        progress.put("status", "WAITING_IDLE"); service.onResult(session, progress);
        assertEquals("RESTARTING", service.status(7).getString("status"), "late waiting frames cannot regress a restart");
        state = JSONObject.parseObject(values.get(ExecutorRestartService.key(7)));
        state.put("progressAt", Instant.now().minusSeconds(700).toString());
        String raw = state.toJSONString(); values.put(ExecutorRestartService.key(7), raw);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        assertEquals("TIMED_OUT", service.status(7).getString("status"));
        assertEquals("No client progress or restart heartbeat was received for the online application. Check the client.", service.status(7).getString("message"));
        assertEquals(raw, values.get(ExecutorRestartService.key(7)));
    }

    @Test void stalePolicyOwnerCannotRenewOrReleaseAnotherRequest() {
        var config = new ExecutorLaunchConfigService.LaunchConfig(); config.memoryMode = "platform";
        when(launchConfigs.requireCompleteConfig(7L, 1L, 2)).thenReturn(config);
        JSONObject request = service.applyLaunchCommand(7, 1, 3, 2, "FORCE");
        values.put(ExecutorRestartService.lock(7), "new-owner");
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        JSONObject frame = new JSONObject(); frame.put("requestId", request.getString("requestId")); frame.put("status", "FAILED");
        service.onResult(session, frame);
        assertEquals("REQUESTED", service.status(7).getString("status"));
        assertEquals("new-owner", values.get(ExecutorRestartService.lock(7)));
        verify(redis, never()).releaseLock(anyString(), anyString());
    }

    @Test void rejectsForeignOfflineLegacyAndDuplicateRequests() {
        assertThrows(BizException.class, () -> service.request(7, 2, 3, false));
        verifyNoInteractions(redis);
        when(registry.isOnline(7L)).thenReturn(false);
        assertThrows(BizException.class, () -> service.request(7, 1, 3, false));
        when(registry.isOnline(7L)).thenReturn(true);
        when(presence.supportsProtocolFeature(7L, "EXECUTOR_RESTART_V1")).thenReturn(false);
        assertThrows(BizException.class, () -> service.request(7, 1, 3, false));
        when(presence.supportsProtocolFeature(7L, "EXECUTOR_RESTART_V1")).thenReturn(true);
        service.request(7, 1, 3, false);
        assertThrows(BizException.class, () -> service.request(7, 1, 3, false));
        verify(redis, times(1)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_RESTART"));
    }
    @Test void requiresNewCorrelatedHeartbeatForSuccess() {
        JSONObject request = service.request(7, 1, 3, true);
        JSONObject frame = new JSONObject(); frame.put("requestId", request.getString("requestId")); frame.put("status", "RESTARTING");
        service.onResult(session, frame);
        assertEquals("RESTARTING", service.status(7).getString("status"));
        JSONObject hb = new JSONObject(); hb.put("startedAt", dao.findById(7L).getLastStartedAt().toInstant().toString()); hb.put("restartRequestId", request.getString("requestId"));
        service.onHeartbeat(session, hb);
        assertEquals("RESTARTING", service.status(7).getString("status"));
        hb.put("startedAt", Instant.now().toString()); hb.put("restartRequestId", "unrelated"); service.onHeartbeat(session, hb);
        assertEquals("RESTARTING", service.status(7).getString("status"));
        hb.put("restartRequestId", request.getString("requestId")); service.onHeartbeat(session, hb);
        assertEquals("COMPLETED", service.status(7).getString("status"));
        service.onResult(session, frame);
        assertEquals("COMPLETED", service.status(7).getString("status"));
    }
    @Test void rejectedClientResponseAllowsRetryAndIgnoresForeignRequest() {
        JSONObject request = service.request(7, 1, 3, false);
        JSONObject frame = new JSONObject(); frame.put("requestId", "foreign"); frame.put("status", "FAILED");
        service.onResult(session, frame);
        assertEquals("REQUESTED", service.status(7).getString("status"));
        frame.put("requestId", request.getString("requestId")); frame.put("message", "busy"); service.onResult(session, frame);
        assertEquals("FAILED", service.status(7).getString("status"));
        assertDoesNotThrow(() -> service.request(7, 1, 3, false));
    }
    @Test void ignoresAbsentOrInvalidClientStartupTimes() {
        service.onHeartbeat(session, new JSONObject());
        JSONObject hb = new JSONObject(); hb.put("startedAt", "invalid"); service.onHeartbeat(session, hb);
        hb.put("startedAt", Instant.now().plusSeconds(600).toString()); service.onHeartbeat(session, hb);
        verify(dao, never()).updateLastStartedAt(anyLong(), anyLong(), any());
    }
    @org.junit.jupiter.api.AfterEach void clearLocale() {
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        com.aliyun.autowonder.context.AutoWonderContext.destroy();
    }
    @Test void systemRestartResponsesRenderWithoutChangingRedisOrClientFrame() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        JSONObject request = service.request(7, 1, 3, false);
        assertEquals("SYSTEM", request.getString("messageOrigin"));
        assertEquals("Request sent. Waiting for the client response.", request.getString("message"));
        JSONObject stored = JSONObject.parseObject(values.get(ExecutorRestartService.key(7)));
        assertEquals("已发送，等待客户端响应", stored.getString("message"));
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        JSONObject wire = JSONObject.parseObject(payload.getValue());
        assertEquals("已发送，等待客户端响应", wire.getString("message"));
        assertFalse(wire.containsKey("messageOrigin"));
        JSONObject hb = new JSONObject(); hb.put("startedAt", Instant.now().toString());
        hb.put("restartRequestId", request.getString("requestId")); service.onHeartbeat(session, hb);
        assertEquals("The client has restarted and is online.", service.status(7).getString("message"));
        assertEquals("客户端已重新启动并上线", JSONObject.parseObject(values.get(ExecutorRestartService.key(7))).getString("message"));
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.CHINA);
        assertEquals("客户端已重新启动并上线", service.status(7).getString("message"));
    }
    @Test void sendFailureAndTimeoutAreOwnedButLegacyAndUnknownOriginsRemainRaw() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        doThrow(new IllegalStateException("raw socket error")).when(redis).publish(anyString(), anyString());
        JSONObject failed = service.request(7, 1, 3, false);
        assertEquals("FAILED", failed.getString("status"));
        assertEquals("Send failed. Try again later.", failed.getString("message"));
        assertEquals("发送失败，请稍后重试", JSONObject.parseObject(values.get(ExecutorRestartService.key(7))).getString("message"));
        for (String origin : new String[]{null, "CLIENT", "UNKNOWN", "__proto__"}) {
            JSONObject legacy = new JSONObject(); legacy.put("status", "FAILED");
            legacy.put("message", "发送失败，请稍后重试"); if (origin != null) legacy.put("messageOrigin", origin);
            String raw = legacy.toJSONString(); values.put(ExecutorRestartService.key(7), raw);
            assertEquals("发送失败，请稍后重试", service.status(7).getString("message"));
            assertEquals(raw, values.get(ExecutorRestartService.key(7)));
        }
        JSONObject active = new JSONObject(); active.put("status", "REQUESTED"); active.put("message", "<b>client old text</b>");
        active.put("issuedAt", Instant.now().minusSeconds(700).toString());
        String raw = active.toJSONString(); values.put(ExecutorRestartService.key(7), raw);
        assertEquals("TIMED_OUT", service.status(7).getString("status"));
        assertEquals("No heartbeat was received after the restart. Check the client.", service.status(7).getString("message"));
        assertEquals("SYSTEM", service.status(7).getString("messageOrigin"));
        assertEquals(raw, values.get(ExecutorRestartService.key(7)), "a projected timeout never rewrites Redis history");
        active.put("status", "NEW_STATUS"); active.put("messageOrigin", "SYSTEM"); active.put("message", "<b>unknown</b>");
        values.put(ExecutorRestartService.key(7), active.toJSONString());
        assertEquals("<b>unknown</b>", service.status(7).getString("message"));
    }
    @Test void clientCannotDeclareSystemOriginEvenWithSameStatusAndSameLookingText() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        for (String text : List.of("发送失败，请稍后重试", "<b>客户端原文</b>", "x".repeat(1100))) {
            JSONObject request = service.request(7, 1, 3, false);
            JSONObject frame = new JSONObject(); frame.put("requestId", request.getString("requestId"));
            frame.put("status", "FAILED"); frame.put("message", text); frame.put("messageOrigin", "SYSTEM");
            service.onResult(session, frame);
            JSONObject result = service.status(7);
            assertEquals("CLIENT", result.getString("messageOrigin"));
            assertEquals(text.substring(0, Math.min(1000, text.length())), result.getString("message"));
            assertEquals("SYSTEM", frame.getString("messageOrigin"), "do not mutate inbound client frame");
            assertEquals(result.getString("message"), JSONObject.parseObject(values.get(ExecutorRestartService.key(7))).getString("message"));
        }
    }
    @Test void requestValidationRendersBothLocalesWithOriginalParam400() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        when(registry.isOnline(7L)).thenReturn(false);
        BizException error = assertThrows(BizException.class, () -> service.request(7, 1, 3, false));
        var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error);
        assertEquals(400, response.getStatusCodeValue());
        assertEquals("The Executor is offline and cannot be restarted remotely.", response.getBody().getMessage());
        assertEquals("执行器离线，无法远程重启", error.getMessage());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.CHINA);
        assertEquals(error.getMessage(), com.aliyun.autowonder.common.result.Result.fail(error).getMessage());
    }
    @Test void restartAndOnlineApplyAreRoleGovernedWhileSavedCommandStaysAdministrator() throws Exception {
        // 重启与在线应用（按保存配置重启）由读写级 + 角色 EXECUTOR 只读域治理；
        // 生成/保存自定义启动命令携带令牌语义，仍要求管理员。
        var method = ExecutorController.class.getMethod("restart", Long.class, ExecutorController.RestartRequest.class);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, method.getAnnotation(RequireWorkspaceAccess.class).value());
        var applyMethod = ExecutorController.class.getMethod("applyLaunchCommand", Long.class,
                com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchCommandRequest.class);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, applyMethod.getAnnotation(RequireWorkspaceAccess.class).value());
        var saveMethod = ExecutorController.class.getMethod("saveLaunchCommand", Long.class,
                com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchCommandRequest.class);
        assertEquals(WorkspaceAccessLevel.ADMIN, saveMethod.getAnnotation(RequireWorkspaceAccess.class).value());
    }

    @Test void appliesOnlySavedConfigurationAndConfirmsTheExactVersionAfterRestart() {
        var config = new ExecutorLaunchConfigService.LaunchConfig();
        config.runtimeVersion = "0.3.9";
        config.workspaceRoot = "/data/new workspace";
        config.memoryMode = "platform";
        config.maxConcurrentDispatches = 3;
        when(launchConfigs.requireCompleteConfig(7L, 1L, 9)).thenReturn(config);
        JSONObject request = service.applyLaunchCommand(7, 1, 3, 9);
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        JSONObject frame = JSONObject.parseObject(payload.getValue());
        JSONObject launch = frame.getJSONObject("launch");
        assertEquals("0.3.9", launch.getString("runtimeVersion"));
        assertEquals("/data/new workspace", launch.getString("workspaceRoot"));
        assertEquals(3, launch.getInteger("maxConcurrentDispatches"));
        assertFalse(launch.containsKey("token"));
        assertFalse(frame.containsKey("command"));
        assertEquals(9, request.getInteger("launchConfigVersion"));
        verify(presence, atLeastOnce()).supportsProtocolFeature(7L, "EXECUTOR_LAUNCH_COMMAND_V1");

        JSONObject hb = new JSONObject(); hb.put("startedAt", Instant.now().toString());
        hb.put("restartRequestId", request.getString("requestId"));
        hb.put("version", "0.3.8"); service.onHeartbeat(session, hb);
        assertEquals("REQUESTED", service.status(7).getString("status"));
        hb.put("version", "0.3.9"); service.onHeartbeat(session, hb);
        assertEquals("COMPLETED", service.status(7).getString("status"));
    }

    @Test void staleConfigurationAndLegacyClientsCannotApplyCommands() {
        when(launchConfigs.requireCompleteConfig(7L, 1L, 1))
                .thenThrow(new BizException(com.aliyun.autowonder.common.error.ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT));
        assertThrows(BizException.class, () -> service.applyLaunchCommand(7, 1, 3, 1));
        assertThrows(BizException.class, () -> service.applyLaunchCommand(7, 1, 3, null));
        var config = new ExecutorLaunchConfigService.LaunchConfig(); config.memoryMode = "platform";
        when(launchConfigs.requireCompleteConfig(7L, 1L, 2)).thenReturn(config);
        when(presence.supportsProtocolFeature(7L, "EXECUTOR_LAUNCH_COMMAND_V1")).thenReturn(false);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        BizException error = assertThrows(BizException.class, () -> service.applyLaunchCommand(7, 1, 3, 2));
        assertTrue(com.aliyun.autowonder.common.result.Result.fail(error).getMessage().startsWith("This client does not support applying startup commands online."));
        verifyNoInteractions(redis);
    }

    @Test void applyingUnpinnedArgumentsKeepsTheCurrentSourceVersion() {
        var config = new ExecutorLaunchConfigService.LaunchConfig();
        config.memoryMode = "none";
        when(launchConfigs.requireCompleteConfig(7L, 1L, 3)).thenReturn(config);
        when(presence.currentVersion(7L)).thenReturn("dev");
        JSONObject request = service.applyLaunchCommand(7, 1, 3, 3);
        assertEquals("dev", request.getString("targetRuntimeVersion"));
        JSONObject heartbeat = new JSONObject();
        heartbeat.put("startedAt", Instant.now().toString());
        heartbeat.put("restartRequestId", request.getString("requestId"));
        heartbeat.put("version", "dev");
        service.onHeartbeat(session, heartbeat);
        assertEquals("COMPLETED", service.status(7).getString("status"));
        verifyNoInteractions(branding);
    }
}
