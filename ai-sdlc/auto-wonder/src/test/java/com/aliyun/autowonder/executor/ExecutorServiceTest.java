package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.dto.*;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutorServiceTest {

    ExecutorDao executorDao;
    ExecutorRegistry registry;
    TokenService tokenService;
    RedisManager redisManager;
    PresenceManager presenceManager;
    SessionRegistry sessionRegistry;
    ExecutorService service;

    @BeforeEach
    void setUp() {
        executorDao = mock(ExecutorDao.class);
        registry = mock(ExecutorRegistry.class);
        tokenService = mock(TokenService.class);
        redisManager = mock(RedisManager.class);
        presenceManager = mock(PresenceManager.class);
        sessionRegistry = mock(SessionRegistry.class);
        service = new ExecutorService(executorDao, registry, tokenService,
                redisManager, presenceManager, sessionRegistry);
    }

    @Test
    void create_issues_token_and_persists_ref() {
        doAnswer(inv -> { ((ExecutorDO) inv.getArgument(0)).setId(1L); return null; })
                .when(executorDao).insert(any());
        when(tokenService.issue(1L))
                .thenReturn(new TokenService.IssuedToken("plain-abc", "sha256:deadbeef"));
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("mac-cli");
        req.setClientKind("claude-cli");

        IssuedExecutorVO vo = service.create(5L, req, 100L, 7L);

        assertEquals(1L, vo.getId());
        assertEquals("plain-abc", vo.getToken());
        verify(executorDao).insert(argThat((ExecutorDO e) ->
                e.getTenantId() == 100L && e.getAgentId() == 5L
                        && "OFFLINE".equals(e.getStatus())));
        verify(executorDao).updateTokenRef(1L, "sha256:deadbeef");
    }

    @Test
    void create_blank_name_throws() {
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("  ");
        BizException ex = assertThrows(BizException.class, () -> service.create(5L, req, 100L, 7L));
        assertEquals("17002", ex.getCode());
    }

    @Test
    void create_persists_qoder_cn_client_kind_verbatim() {
        doAnswer(inv -> { ((ExecutorDO) inv.getArgument(0)).setId(1L); return null; })
                .when(executorDao).insert(any());
        when(tokenService.issue(1L))
                .thenReturn(new TokenService.IssuedToken("plain-cn", "sha256:cndeadbeef"));
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("cn-cli");
        req.setClientKind("QODER_CN_CLI");

        IssuedExecutorVO vo = service.create(5L, req, 100L, 7L);

        assertEquals(1L, vo.getId());
        verify(executorDao).insert(argThat((ExecutorDO e) ->
                "QODER_CN_CLI".equals(e.getClientKind())));
    }

    @Test
    void list_returns_qoder_cn_client_kind_verbatim() {
        ExecutorDO e = exec(1L, 5L);
        e.setClientKind("QODER_CN_CLI");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("QODER_CN_CLI", vos.get(0).getClientKind());
    }

    @Test
    void list_reflects_online_status() {
        ExecutorDO e1 = exec(1L, 5L);
        ExecutorDO e2 = exec(2L, 5L);
        e1.setAgentName("Alpha");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e1, e2));
        when(registry.isOnline(1L)).thenReturn(true);
        when(registry.isOnline(2L)).thenReturn(false);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("ONLINE", vos.get(0).getStatus());
        assertEquals("Alpha", vos.get(0).getAgentName());
        assertEquals("OFFLINE", vos.get(1).getStatus());
    }

    @Test
    void listAll_returns_executors_with_agent_name() {
        ExecutorDO e1 = exec(1L, 5L);
        e1.setAgentName("Alpha");
        when(executorDao.listAll(100L)).thenReturn(List.of(e1));
        when(registry.isOnline(1L)).thenReturn(true);

        List<ExecutorVO> vos = service.listAll(100L);

        assertEquals(1, vos.size());
        assertEquals(5L, vos.get(0).getAgentId());
        assertEquals("Alpha", vos.get(0).getAgentName());
        assertEquals("ONLINE", vos.get(0).getStatus());
    }

    @Test
    void list_maps_lastConnectIp() {
        ExecutorDO e = exec(1L, 5L);
        e.setLastConnectIp("203.0.113.50");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("203.0.113.50", vos.get(0).getLastConnectIp());
    }

    @Test
    void list_maps_null_lastConnectIp() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertNull(vos.get(0).getLastConnectIp());
    }

    @Test
    void getToken_returns_resolved_plaintext() {
        ExecutorDO e = exec(9L, 5L);
        e.setTokenRef("b64:dGVzdA==");
        when(executorDao.findById(9L)).thenReturn(e);
        when(tokenService.resolve("b64:dGVzdA==")).thenReturn("test");

        String token = service.getToken(9L, 100L);

        assertEquals("test", token);
    }

    @Test
    void getToken_not_found_throws() {
        when(executorDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.getToken(9L, 100L));
        assertEquals("17001", ex.getCode());
    }

    @Test
    void getToken_legacy_hash_throws() {
        ExecutorDO e = exec(9L, 5L);
        e.setTokenRef("sha256:abc");
        when(executorDao.findById(9L)).thenReturn(e);
        when(tokenService.resolve("sha256:abc")).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getToken(9L, 100L));
        assertEquals("17004", ex.getCode());
    }

    @Test
    void delete_not_found_throws() {
        when(executorDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 100L, 7L));
        assertEquals("17001", ex.getCode());
    }

    @Test
    void delete_succeeds() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        when(executorDao.softDelete(9L, 100L, 7L)).thenReturn(1);
        service.delete(9L, 100L, 7L);
        verify(executorDao).softDelete(9L, 100L, 7L);
    }

    @Test
    void delete_wrong_tenant_throws_and_skips_softDelete() {
        // executorDao.findById returns a row owned by tenant 100L, but caller is tenant 999L
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 999L, 7L));
        assertEquals("17001", ex.getCode());
        verify(executorDao, never()).softDelete(anyLong(), anyLong(), anyLong());
    }

    @Test
    void deleteWritesTombstone() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        when(executorDao.softDelete(9L, 100L, 7L)).thenReturn(1);

        service.delete(9L, 100L, 7L);

        verify(redisManager).setIfAbsent("exec:deleted:9", "1", 86400L);
    }

    @Test
    void deleteUnregistersPresence() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));

        service.delete(9L, 100L, 7L);

        verify(presenceManager).unregister(9L, 5L);
    }

    @Test
    void deleteClosesLocalWsSession() throws Exception {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        Session ws = mock(Session.class);
        ExecutorSession es = new ExecutorSession(9L, 5L, 100L, ws);
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(es);

        service.delete(9L, 100L, 7L);

        verify(ws).close();
    }

    @Test
    void deleteBroadcastsSessionClose() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);

        service.delete(9L, 100L, 7L);

        verify(redisManager).publish("node:dispatch:broadcast",
                "{\"type\":\"SESSION_CLOSE\",\"executorId\":9}");
    }

    @Test
    void deleteProceedsWithoutLocalSession() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);

        assertDoesNotThrow(() -> service.delete(9L, 100L, 7L));
        verify(redisManager).setIfAbsent("exec:deleted:9", "1", 86400L);
        verify(presenceManager).unregister(9L, 5L);
    }

    @Test
    void recordLastConnectIp_validIp_callsDao() {
        service.recordLastConnectIp(1L, 100L, "203.0.113.50");
        verify(executorDao).updateLastConnectIp(1L, 100L, "203.0.113.50", null);
    }

    @Test
    void recordLastConnectIp_blankIp_skipsDao() {
        service.recordLastConnectIp(1L, 100L, "  ");
        verify(executorDao, never()).updateLastConnectIp(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void recordLastConnectIp_nullIp_skipsDao() {
        service.recordLastConnectIp(1L, 100L, null);
        verify(executorDao, never()).updateLastConnectIp(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void persistHeartbeatIfNeeded_firstCall_writesToDb() {
        service.persistHeartbeatIfNeeded(1L, 100L);
        verify(executorDao).updateLastHeartbeat(1L, 100L);
    }

    @Test
    void persistHeartbeatIfNeeded_withinThrottleWindow_skipsDb() {
        service.persistHeartbeatIfNeeded(1L, 100L);
        service.persistHeartbeatIfNeeded(1L, 100L);
        service.persistHeartbeatIfNeeded(1L, 100L);
        verify(executorDao, times(1)).updateLastHeartbeat(1L, 100L);
    }

    @Test
    void persistHeartbeatIfNeeded_afterThrottleWindow_writesAgain() {
        service.persistHeartbeatIfNeeded(1L, 100L);
        service.heartbeatPersistedAt.put(1L,
                Instant.now().minusSeconds(ExecutorService.HEARTBEAT_THROTTLE_SECONDS + 1));

        service.persistHeartbeatIfNeeded(1L, 100L);

        verify(executorDao, times(2)).updateLastHeartbeat(1L, 100L);
    }

    @Test
    void persistHeartbeatIfNeeded_dbException_doesNotThrow() {
        doThrow(new RuntimeException("connection refused"))
                .when(executorDao).updateLastHeartbeat(anyLong(), anyLong());

        assertDoesNotThrow(() -> service.persistHeartbeatIfNeeded(1L, 100L));
    }

    @Test
    void list_maps_lastHeartbeat() {
        ExecutorDO e = exec(1L, 5L);
        e.setLastHeartbeat(new Date());
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertNotNull(vos.get(0).getLastHeartbeat());
    }

    @Test
    void list_maps_null_lastHeartbeat() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertNull(vos.get(0).getLastHeartbeat());
    }

    @Test
    void listAll_maps_lastHeartbeat() {
        ExecutorDO e = exec(1L, 5L);
        e.setLastHeartbeat(new Date());
        when(executorDao.listAll(100L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listAll(100L);

        assertNotNull(vos.get(0).getLastHeartbeat());
    }

    private ExecutorDO exec(long id, long agentId) {
        ExecutorDO e = new ExecutorDO();
        e.setId(id);
        e.setTenantId(100L);
        e.setAgentId(agentId);
        e.setName("n" + id);
        e.setStatus("OFFLINE");
        return e;
    }
}
