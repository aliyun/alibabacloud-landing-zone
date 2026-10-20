package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.json.JSON;
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
            service.complete(100L, 7L, testId, true, "连接成功", 9L, List.of(Map.of("name", "search")));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        RuntimeMcpConnectionTestService.SkillConnectionTestResult result =
                service.test(100L, 7L, "uvx", List.of("alibabacloud.mcp-proxy@latest"));

        assertTrue(result.success);
        assertEquals("连接成功", result.message);
        assertEquals(9L, result.durationMs);
        assertEquals("search", result.tools.get(0).get("name"));
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
            service.complete(100L, 7L, testId, false, "命令不可用", 4L, List.of());
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
        service.complete(100L, 7L, "unknown", true, "不应接收", 1L, List.of());
        verify(redisManager, never()).set(startsWith("mcp:connection:test:result:"), any(), anyInt());
    }

    @Test
    void returnsRetryableFailureWhenReadingAnIncompatibleLegacyResult() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        SessionRegistry sessionRegistry = mock(SessionRegistry.class);
        RedisManager redisManager = mock(RedisManager.class);
        RuntimeMcpConnectionTestService service = new RuntimeMcpConnectionTestService(
                executorDao, sessionRegistry, redisManager);
        when(executorDao.findById(7L)).thenReturn(executor(7L, 100L));
        Map<String, Object> values = redisStore(redisManager);
        when(redisManager.get(any(Serializable.class))).thenAnswer(invocation -> {
            Object rawKey = invocation.getArgument(0);
            String key = String.valueOf(rawKey);
            if (key.startsWith("mcp:connection:test:result:") && !key.startsWith("mcp:connection:test:result:v2:")) {
                throw new RuntimeException("deserialize failed");
            }
            return values.get(key);
        });

        RuntimeMcpConnectionTestService.SkillConnectionTestResult result =
                service.test(100L, 7L, "uvx", List.of("alibabacloud.mcp-proxy@latest"));

        assertFalse(result.success);
        assertEquals("测试结果与灰度版本不兼容，请稍后重新测试", result.message);
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"legacy", "invalid", "interrupted", "timeout", "untrusted"})
    void responseOriginComesFromLocalBranchesAndNeverFromStoredPayload(String branch) {
        var dao=mock(ExecutorDao.class);var redis=mock(RedisManager.class);
        var service=new RuntimeMcpConnectionTestService(dao,mock(SessionRegistry.class),redis);
        when(dao.findById(7L)).thenReturn(executor(7L,100L));var values=redisStore(redis);
        when(redis.get(any(Serializable.class))).thenAnswer(inv -> {
            Object rawKey=inv.getArgument(0); String key=String.valueOf(rawKey);
            if(key.startsWith("mcp:connection:test:result:v2:")) {
                if(branch.equals("invalid")) return "not-json";
                if(branch.equals("untrusted")) return "{\"success\":false,\"message\":\"连接测试被中断<b>raw</b>\",\"ownedFailureCode\":\"TEST_INTERRUPTED\",\"ownedFailureArgs\":{\"seconds\":90},\"tools\":[]}";
            }
            if(key.startsWith("mcp:connection:test:result:")&&!key.startsWith("mcp:connection:test:result:v2:")&&branch.equals("legacy")) throw new IllegalStateException("raw deserialize");
            return values.get(key);
        });
        if(branch.equals("interrupted")) Thread.currentThread().interrupt();
        try {
            var result=service.test(100L,7L,"raw-command",List.of("<raw>"));
            var json=JSON.parseObject(JSON.toJSONString(result));
            assertFalse(result.success);
            String code=switch(branch) {case "legacy" -> "LEGACY_RESULT_INCOMPATIBLE";case "invalid" -> "INVALID_RESULT";case "interrupted" -> "TEST_INTERRUPTED";case "timeout" -> "RUNTIME_WAIT_TIMEOUT";default -> null;};
            assertEquals(code,json.getString("ownedFailureCode"));
            if(branch.equals("timeout")) {assertEquals(90,json.getJSONObject("ownedFailureArgs").getInteger("seconds"));assertEquals("Runtime 未在 90 秒内返回；请确认该 Runtime 在线且已更新",result.message);}
            else assertFalse(json.containsKey("ownedFailureArgs"));
            if(branch.equals("untrusted")) assertEquals("连接测试被中断<b>raw</b>",result.message);
        } finally {Thread.interrupted();}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"request","dispatch"})
    void localThrowableOriginKeepsExactClassCauseAndHumanResponse(String branch) {
        var dao=mock(ExecutorDao.class);var redis=mock(RedisManager.class);var skills=mock(SkillDao.class);
        var runtime=new RuntimeMcpConnectionTestService(dao,mock(SessionRegistry.class),redis);
        when(dao.findById(7L)).thenReturn(executor(7L,100L));redisStore(redis);
        var cause=new IllegalArgumentException("<raw> external");
        if(branch.equals("request")) when(redis.set(any(Serializable.class),any(Serializable.class),anyInt())).thenReturn(false);
        else doThrow(cause).when(redis).publish(anyString(),anyString());
        var ex=assertThrows(IllegalStateException.class,()->runtime.test(100L,7L,"raw",List.of()));
        assertEquals(IllegalStateException.class,ex.getClass());assertSame(branch.equals("request") ? null : cause,ex.getCause());
        String raw=branch.equals("request") ? "无法创建 Runtime 测试请求" : "Runtime MCP 测试下发失败";assertEquals(raw,ex.getMessage());
        SkillDO skill=new SkillDO();skill.setId(1L);skill.setTenantId(100L);skill.setType("MCP");skill.setInstallSpec("{}");when(skills.findById(1L)).thenReturn(skill);
        var vo=new SkillConnectionTestService(skills,runtime).test(1L,100L,7L);
        var json=JSON.parseObject(JSON.toJSONString(vo));assertEquals(raw,vo.getMessage());
        assertEquals(branch.equals("request") ? "REQUEST_CREATE_FAILED" : "DISPATCH_FAILED",json.getString("ownedFailureCode"));
    }

    @Test
    void completionKeepsExactRuntimeFrameV2KeysAndLegacySerializationWhileBlankAndHtmlErrorsStayRaw() throws Exception {
        var dao=mock(ExecutorDao.class);var redis=mock(RedisManager.class);var registry=mock(SessionRegistry.class);
        var runtime=new RuntimeMcpConnectionTestService(dao,registry,redis);when(dao.findById(7L)).thenReturn(executor(7L,100L));
        var values=redisStore(redis);var tools=List.of(Map.<String,Object>of("name","<b>raw</b>","description","用户 #7","inputSchema",Map.of("raw","<html>")));
        doAnswer(inv -> {
            var frame=JSON.parseObject(inv.getArgument(1));
            assertEquals(java.util.Set.of("type","testId","executorId","transport","command","args","headers","env","timeoutSeconds"),frame.keySet());
            assertEquals("MCP_CONNECTION_TEST",frame.getString("type"));assertEquals("raw-command",frame.getString("command"));
            String id=frame.getString("testId");runtime.complete(100L,7L,id,false,"连接测试被中断<b>raw</b>",4L,tools);
            var v2=JSON.parseObject((String)values.get("mcp:connection:test:result:v2:"+id));
            assertEquals(java.util.Set.of("success","message","durationMs","tools"),v2.keySet());
            assertEquals("连接测试被中断<b>raw</b>",v2.getString("message"));
            var legacy=values.get("mcp:connection:test:result:"+id);
            var bytes=new java.io.ByteArrayOutputStream();try(var out=new java.io.ObjectOutputStream(bytes)){out.writeObject(legacy);}
            Object restored;try(var in=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){restored=in.readObject();}
            assertEquals(java.util.Set.of("success","message","durationMs"),java.util.Arrays.stream(java.io.ObjectStreamClass.lookup(restored.getClass()).getFields()).map(java.io.ObjectStreamField::getName).collect(java.util.stream.Collectors.toSet()));
            // Force the old-node read path using its unchanged Serializable result object.
            values.remove("mcp:connection:test:result:v2:"+id);
            return null;
        }).when(redis).publish(anyString(),anyString());
        var result=runtime.test(100L,7L,"raw-command",List.of("<raw>"));
        assertFalse(result.success);assertNull(result.ownedFailureCode);assertNull(result.ownedFailureArgs);
        assertEquals("连接测试被中断<b>raw</b>",result.message);assertEquals(tools,result.tools);assertEquals(4L,result.durationMs);
    }

}
