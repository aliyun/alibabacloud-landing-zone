package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class WsGuidanceTransportTest {
    @Test
    void sendsGuidanceFrameToTargetExecutor() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RedisManager redis = mock(RedisManager.class);
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(ws.isOpen()).thenReturn(true);
        when(ws.getBasicRemote()).thenReturn(remote);
        when(sessions.findByExecutorId(10005L)).thenReturn(new ExecutorSession(10005L, 40013L, 100L, ws));
        WsGuidanceTransport transport = new WsGuidanceTransport(sessions, redis);

        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setWorkitemId(50L);
        guidance.setDispatchId(91L);
        guidance.setExecutorId(10005L);
        guidance.setTargetAgentId(40013L);
        transport.send(guidance, "check concurrency");

        verify(remote).sendText(argThat(frame -> frame.contains("\"type\":\"TASK_GUIDANCE\"")
                && frame.contains("\"guidanceId\":701") && frame.contains("check concurrency")));
        verifyNoInteractions(redis);
    }
}
