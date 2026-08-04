package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeMcpConnectionTestServiceTest {

    @Test
    void testsStdioMcpThroughRemoteRuntimeAndReturnsItsResult() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        SessionRegistry sessionRegistry = mock(SessionRegistry.class);
        RedisManager redisManager = mock(RedisManager.class);
        RuntimeMcpConnectionTestService service = new RuntimeMcpConnectionTestService(
                executorDao, sessionRegistry, redisManager);
        ExecutorDO executor = executor(7L, 100L);
        Map<String, Object> values = redisStore(redisManager);
        when(executorDao.findById(7L)).thenReturn(executor);

        doAnswer(invocation -> {
            String payload = invocation.getArgument(1);
            String testId = JSON.parseObject(payload).getString("testId");
            service.complete(100L, 7L, testId, true, "连接成功", 9L);
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        RuntimeMcpConnectionTestService.SkillConnectionTestResult result =
                service.test(100L, 7L, "uvx", List.of("alibabacloud.mcp-proxy@latest"));

        assertTrue(result.success);
        assertEquals("连接成功", result.message);
        assertEquals(9L, result.durationMs);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("MCP_CONNECTION_TEST"));
        assertFalse(values.isEmpty());
    }

    @Test
    void sendsToLocalRuntimeWhenItsWebsocketIsOpen() throws Exception {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        SessionRegistry sessionRegistry = mock(SessionRegistry.class);
        RedisManager redisManager = mock(RedisManager.class);
        RuntimeMcpConnectionTestService service = new RuntimeMcpConnectionTestService(
                executorDao, sessionRegistry, redisManager);
        when(executorDao.findById(7L)).thenReturn(executor(7L, 100L));
        redisStore(redisManager);
        Session websocket = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(websocket.isOpen()).thenReturn(true);
        when(websocket.getBasicRemote()).thenReturn(remote);
        when(sessionRegistry.findByExecutorId(7L)).thenReturn(new ExecutorSession(7L, 1L, 100L, websocket));
        doAnswer(invocation -> {
            String testId = JSON.parseObject(invocation.getArgument(0)).getString("testId");
            service.complete(100L, 7L, testId, false, "命令不可用", 4L);
            return null;
        }).when(remote).sendText(anyString());

        RuntimeMcpConnectionTestService.SkillConnectionTestResult result =
                service.test(100L, 7L, "missing", List.of());

        assertFalse(result.success);
        assertEquals("命令不可用", result.message);
        verify(remote).sendText(contains("MCP_CONNECTION_TEST"));
        verify(redisManager, never()).publish(anyString(), anyString());
    }

    @Test
    void rejectsForeignExecutorAndIgnoresMismatchedCompletion() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        RuntimeMcpConnectionTestService service = new RuntimeMcpConnectionTestService(
                executorDao, mock(SessionRegistry.class), redisManager);
        when(executorDao.findById(7L)).thenReturn(executor(7L, 101L));

        assertThrows(RuntimeException.class, () -> service.test(100L, 7L, "uvx", List.of()));

        redisStore(redisManager);
        service.complete(100L, 7L, "unknown", true, "不应接收", 1L);
        verify(redisManager, never()).set(startsWith("mcp:connection:test:result:"), any(), anyInt());
    }

    private static ExecutorDO executor(long id, long tenantId) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        executor.setTenantId(tenantId);
        return executor;
    }

    private static Map<String, Object> redisStore(RedisManager redisManager) {
        Map<String, Object> values = new ConcurrentHashMap<>();
        when(redisManager.set(any(Serializable.class), any(Serializable.class), anyInt()))
                .thenAnswer(invocation -> {
                    Object key = invocation.getArgument(0);
                    values.put(String.valueOf(key), invocation.getArgument(1));
                    return true;
                });
        when(redisManager.get(any(Serializable.class)))
                .thenAnswer(invocation -> {
                    Object key = invocation.getArgument(0);
                    return values.get(String.valueOf(key));
                });
        return values;
    }
}
