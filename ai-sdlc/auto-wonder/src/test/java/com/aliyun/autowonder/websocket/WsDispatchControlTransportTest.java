package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WsDispatchControlTransportTest {

    @Test
    void sendsPauseToTheOwningExecutor() throws Exception {
        SessionRegistry registry = mock(SessionRegistry.class);
        RedisManager redis = mock(RedisManager.class);
        Session session = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basic);
        when(registry.findByExecutorId(7L))
                .thenReturn(new ExecutorSession(7L, 400L, 100L, session));
        WsDispatchControlTransport transport = new WsDispatchControlTransport(registry, redis);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(55L);
        dispatch.setExecutorId(7L);

        transport.pause(dispatch);

        verify(basic).sendText(argThat(frame -> frame.contains("\"type\":\"TASK_PAUSE\"")
                && frame.contains("\"dispatchId\":55")
                && frame.contains("\"executorId\":7")));
        verify(redis, never()).publish(anyString(), anyString());
    }

    @Test
    void publishesPauseWhenExecutorIsConnectedToAnotherNode() {
        SessionRegistry registry = mock(SessionRegistry.class);
        RedisManager redis = mock(RedisManager.class);
        when(registry.findByExecutorId(7L)).thenReturn(null);
        WsDispatchControlTransport transport = new WsDispatchControlTransport(registry, redis);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(55L);
        dispatch.setExecutorId(7L);

        transport.pause(dispatch);

        verify(redis).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), argThat(frame ->
                frame.contains("\"type\":\"TASK_PAUSE\"")
                        && frame.contains("\"dispatchId\":55")));
    }
}
