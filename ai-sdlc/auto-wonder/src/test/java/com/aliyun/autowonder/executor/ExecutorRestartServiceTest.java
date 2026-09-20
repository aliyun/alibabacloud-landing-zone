package com.aliyun.autowonder.executor;

import com.alibaba.fastjson.JSONObject;
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
    ExecutorRestartService service = new ExecutorRestartService(dao, registry, presence, redis, sessions);
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
    @Test void endpointRequiresAdministrator() throws Exception {
        var method = ExecutorController.class.getMethod("restart", Long.class, ExecutorController.RestartRequest.class);
        assertEquals(WorkspaceAccessLevel.ADMIN, method.getAnnotation(RequireWorkspaceAccess.class).value());
    }
}
