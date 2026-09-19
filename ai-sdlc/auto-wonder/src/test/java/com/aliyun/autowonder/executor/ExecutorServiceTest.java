package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.dto.*;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.squad.SquadAttributionService;
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
    ExecutorLaunchConfigService launchConfigService;
    ExecutorService service;

    @BeforeEach
    void setUp() {
        executorDao = mock(ExecutorDao.class);
        registry = mock(ExecutorRegistry.class);
        tokenService = mock(TokenService.class);
        redisManager = mock(RedisManager.class);
        presenceManager = mock(PresenceManager.class);
        sessionRegistry = mock(SessionRegistry.class);
        // read() 未打桩 → 返回 null → options.models() 回退到 FALLBACK_MODELS（含 auto / qmodel_38max）
        launchConfigService = new ExecutorLaunchConfigService(executorDao,
                new ExecutorLaunchOptionsService(mock(ProviderModelCatalogService.class)));
        service = new ExecutorService(executorDao, registry, tokenService,
                redisManager, presenceManager, sessionRegistry, launchConfigService);
    }

    @Test
    void create_issues_token_and_persists_ref() {
        doAnswer(inv -> { ((ExecutorDO) inv.getArgument(0)).setId(1L); return null; })
                .when(executorDao).insert(any());
        when(tokenService.issue(1L))
                .thenReturn(new TokenService.IssuedToken("plain-abc", "sha256:deadbeef"));
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("mac-cli");
        req.setClientKind("QODER_CLI");

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
    void create_null_client_kind_is_rejected_before_any_write() {
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("no-kind");
        BizException ex = assertThrows(BizException.class, () -> service.create(5L, req, 100L, 7L));
        assertEquals("17009", ex.getCode());
        verify(executorDao, never()).insert(any());
        verify(tokenService, never()).issue(anyLong());
    }

    @Test
    void create_canonicalizes_the_client_kind_before_persisting() {
        doAnswer(inv -> { ((ExecutorDO) inv.getArgument(0)).setId(1L); return null; })
                .when(executorDao).insert(any());
        when(tokenService.issue(1L))
                .thenReturn(new TokenService.IssuedToken("plain-lower", "sha256:lowerbeef"));
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("lower-cli");
        req.setClientKind(" qoder_cn_cli ");

        IssuedExecutorVO vo = service.create(5L, req, 100L, 7L);

        // 与 MCP 入口一致：落库与回显的都是规范化后的类型
        verify(executorDao).insert(argThat((ExecutorDO e) ->
                "QODER_CN_CLI".equals(e.getClientKind())));
        assertEquals("QODER_CN_CLI", vo.getClientKind());
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
    void create_persists_the_dialog_defaults_when_no_launch_value_is_passed() {
        doAnswer(inv -> { ((ExecutorDO) inv.getArgument(0)).setId(1L); return null; })
                .when(executorDao).insert(any());
        when(tokenService.issue(1L))
                .thenReturn(new TokenService.IssuedToken("plain-def", "sha256:defbeef"));
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("def-cli");
        req.setClientKind("QODER_CLI");

        IssuedExecutorVO vo = service.create(5L, req, 100L, 7L);

        // The row is command-ready the moment it exists, so no default backfill is needed on read.
        verify(executorDao).insert(argThat((ExecutorDO e) ->
                ("{\"model\":\"auto\",\"reasoningEffort\":\"medium\",\"contextWindow\":\"260000\","
                        + "\"memoryMode\":\"platform\",\"maxConcurrentDispatches\":5}").equals(e.getLaunchConfig())));
        assertEquals("auto", vo.getModel());
        assertEquals("medium", vo.getReasoningEffort());
        assertEquals("260000", vo.getContextWindow());
        assertEquals("platform", vo.getMemoryMode());
        assertEquals("QODER_CLI", vo.getClientKind());
        // config_version keeps its database default, so the first optimistic-lock update carries version=1.
        assertEquals(1, vo.getConfigVersion());
    }

    @Test
    void create_persists_explicit_launch_values_verbatim() {
        doAnswer(inv -> { ((ExecutorDO) inv.getArgument(0)).setId(1L); return null; })
                .when(executorDao).insert(any());
        when(tokenService.issue(1L))
                .thenReturn(new TokenService.IssuedToken("plain-exp", "sha256:expbeef"));
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("exp-cli");
        req.setClientKind("QODER_CN_CLI");
        req.setMemoryMode("none");
        req.setModel("lite");
        req.setReasoningEffort("low");
        req.setContextWindow("400000");

        IssuedExecutorVO vo = service.create(5L, req, 100L, 7L);

        verify(executorDao).insert(argThat((ExecutorDO e) ->
                ("{\"model\":\"lite\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\","
                        + "\"memoryMode\":\"none\",\"maxConcurrentDispatches\":5}").equals(e.getLaunchConfig())));
        assertEquals("lite", vo.getModel());
        assertEquals("low", vo.getReasoningEffort());
        assertEquals("400000", vo.getContextWindow());
        assertEquals("none", vo.getMemoryMode());
    }

    @Test
    void create_rejects_an_unavailable_model_without_inserting_or_issuing_a_token() {
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("bad-cli");
        req.setClientKind("QODER_CLI");
        req.setModel("gpt-5");

        BizException ex = assertThrows(BizException.class, () -> service.create(5L, req, 100L, 7L));

        assertEquals("17006", ex.getCode());
        verify(executorDao, never()).insert(any());
        verify(tokenService, never()).issue(anyLong());
    }

    @Test
    void create_rejects_an_unknown_memory_mode_without_inserting() {
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("bad-mem");
        req.setClientKind("QODER_CLI");
        req.setMemoryMode("hybrid");

        BizException ex = assertThrows(BizException.class, () -> service.create(5L, req, 100L, 7L));

        assertEquals("27003", ex.getCode());
        verify(executorDao, never()).insert(any());
    }

    @Test
    void create_non_creatable_client_kind_is_rejected_like_the_mcp_entry() {
        // 工单要求 REST 与 MCP 创建入口校验一致：非 Qoder 系类型不再允许经 REST 落库。
        // 旧行为是创建成功且启动配置只存记忆模式，与 MCP 入口不一致，本次修复消除。
        CreateExecutorRequest req = new CreateExecutorRequest();
        req.setName("legacy-cli");
        req.setClientKind("CLAUDE_CODE");
        req.setMemoryMode("provider-local");

        BizException ex = assertThrows(BizException.class, () -> service.create(5L, req, 100L, 7L));

        assertEquals("17009", ex.getCode());
        verify(executorDao, never()).insert(any());
        verify(tokenService, never()).issue(anyLong());
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
        when(executorDao.listAll(100L, null)).thenReturn(List.of(e1));
        when(registry.isOnline(1L)).thenReturn(true);

        List<ExecutorVO> vos = service.listAll(100L, null);

        assertEquals(1, vos.size());
        assertEquals(5L, vos.get(0).getAgentId());
        assertEquals("Alpha", vos.get(0).getAgentName());
        assertEquals("ONLINE", vos.get(0).getStatus());
    }

    @Test
    void listAll_pushes_squad_filter_to_dao_and_fills_attribution() {
        ExecutorDO e1 = exec(1L, 5L);
        when(executorDao.listAll(100L, List.of(7L))).thenReturn(List.of(e1));
        SquadAttributionService attribution = mock(SquadAttributionService.class);
        service.setSquadAttributionService(attribution);

        List<ExecutorVO> vos = service.listAll(100L, List.of(7L));

        assertEquals(1, vos.size());
        verify(executorDao).listAll(100L, List.of(7L));
        verify(attribution).fillExecutorSquads(100L, vos);
    }

    @Test
    void listByAgent_fills_attribution() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));
        SquadAttributionService attribution = mock(SquadAttributionService.class);
        service.setSquadAttributionService(attribution);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals(1, vos.size());
        verify(attribution).fillExecutorSquads(100L, vos);
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
    void getToken_blank_plaintext_throws() {
        ExecutorDO e = exec(9L, 5L);
        e.setTokenRef("b64:IA==");
        when(executorDao.findById(9L)).thenReturn(e);
        when(tokenService.resolve("b64:IA==")).thenReturn("   ");

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
        when(executorDao.listAll(100L, null)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listAll(100L, null);

        assertNotNull(vos.get(0).getLastHeartbeat());
    }

    @Test
    void list_maps_reported_runtime_version_from_presence() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));
        when(presenceManager.currentVersion(1L)).thenReturn("0.2.152");

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("0.2.152", vos.get(0).getVersion());
    }

    @Test
    void listAll_maps_reported_runtime_version_from_presence() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listAll(100L, null)).thenReturn(List.of(e));
        when(presenceManager.currentVersion(1L)).thenReturn("0.2.153");

        List<ExecutorVO> vos = service.listAll(100L, null);

        assertEquals("0.2.153", vos.get(0).getVersion());
    }

    @Test
    void list_leaves_version_null_when_never_reported() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertNull(vos.get(0).getVersion());
    }

    @Test
    void getDetail_returns_vo_with_live_status() {
        ExecutorDO e = exec(9L, 5L);
        e.setAgentName("Alpha");
        e.setClientKind("QODER_CLI");
        e.setLastConnectIp("203.0.113.50");
        e.setLastHeartbeat(new Date());
        when(executorDao.findById(9L)).thenReturn(e);
        when(registry.isOnline(9L)).thenReturn(true);

        ExecutorVO vo = service.getDetail(9L, 100L);

        assertEquals(9L, vo.getId());
        assertEquals(5L, vo.getAgentId());
        assertEquals("Alpha", vo.getAgentName());
        assertEquals("QODER_CLI", vo.getClientKind());
        assertEquals("ONLINE", vo.getStatus());
        assertEquals("203.0.113.50", vo.getLastConnectIp());
        assertNotNull(vo.getLastHeartbeat());
    }

    @Test
    void getDetail_offline_when_not_registered() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));
        when(registry.isOnline(9L)).thenReturn(false);

        assertEquals("OFFLINE", service.getDetail(9L, 100L).getStatus());
    }

    @Test
    void getDetail_not_found_throws() {
        when(executorDao.findById(9L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getDetail(9L, 100L));

        assertEquals("17001", ex.getCode());
    }

    @Test
    void getDetail_wrong_tenant_throws() {
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));

        BizException ex = assertThrows(BizException.class, () -> service.getDetail(9L, 999L));

        assertEquals("17001", ex.getCode());
    }

    @Test
    void getDetail_null_tenant_throws() {
        ExecutorDO e = exec(9L, 5L);
        e.setTenantId(null);
        when(executorDao.findById(9L)).thenReturn(e);

        BizException ex = assertThrows(BizException.class, () -> service.getDetail(9L, 100L));

        assertEquals("17001", ex.getCode());
    }

    /**
     * {@code upgradeAvailable} is false both when the executor already runs the target and when its version
     * cannot be read at all ({@code 0.2.155-beta.1}, {@code dev}, never reported). Only the first means
     * 无需升级: {@code ExecutorUpdateService.updateOne} rejects an upgrade solely when the comparison
     * succeeded and came out >= 0, so the panel needs {@code versionComparable} to tell the two apart
     * instead of guessing from {@code version != null}.
     */
    @Test
    void getDetail_separates_an_unreadable_version_from_an_up_to_date_one() {
        ExecutorUpdateService updateService = mock(ExecutorUpdateService.class);
        when(updateService.targetVersion()).thenReturn("0.2.160");
        when(updateService.supportsUpgrade(9L)).thenReturn(true);
        service.setUpdateService(updateService);
        when(executorDao.findById(9L)).thenReturn(exec(9L, 5L));

        when(presenceManager.currentVersion(9L)).thenReturn("0.2.155");
        ExecutorVO behind = service.getDetail(9L, 100L);
        assertEquals("0.2.160", behind.getTargetVersion());
        assertTrue(behind.isUpgradeSupported());
        assertTrue(behind.isVersionComparable());
        assertTrue(behind.isUpgradeAvailable());

        when(presenceManager.currentVersion(9L)).thenReturn("0.2.160");
        ExecutorVO current = service.getDetail(9L, 100L);
        assertTrue(current.isVersionComparable());
        assertFalse(current.isUpgradeAvailable(), "already at the target, so there is nothing to do");

        when(presenceManager.currentVersion(9L)).thenReturn("0.2.161");
        ExecutorVO ahead = service.getDetail(9L, 100L);
        assertTrue(ahead.isVersionComparable());
        assertFalse(ahead.isUpgradeAvailable(), "newer than the target");

        for (String reported : new String[]{"0.2.155-beta.1", "dev", null}) {
            when(presenceManager.currentVersion(9L)).thenReturn(reported);
            ExecutorVO unknown = service.getDetail(9L, 100L);
            assertFalse(unknown.isVersionComparable(), String.valueOf(reported) + " cannot be compared");
            assertFalse(unknown.isUpgradeAvailable(),
                    String.valueOf(reported) + " is not proof of being current either");
        }
    }

    @Test
    void listAll_leaves_the_upgrade_flags_alone_without_an_update_service() {
        when(executorDao.listAll(100L, null)).thenReturn(List.of(exec(1L, 5L)));
        when(presenceManager.currentVersion(1L)).thenReturn("0.2.155");

        ExecutorVO vo = service.listAll(100L, null).get(0);

        assertNull(vo.getTargetVersion());
        assertFalse(vo.isVersionComparable());
        assertFalse(vo.isUpgradeAvailable());
    }

    @Test
    void list_maps_reported_model_from_presence() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));
        when(presenceManager.currentModel(1L)).thenReturn("qoder3-coder-plus");

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("qoder3-coder-plus", vos.get(0).getModel());
        assertNull(vos.get(0).getModelName());
    }

    @Test
    void listAll_leaves_model_null_when_never_reported() {
        ExecutorDO e = exec(1L, 5L);
        when(executorDao.listAll(100L, null)).thenReturn(List.of(e));

        List<ExecutorVO> vos = service.listAll(100L, null);

        assertNull(vos.get(0).getModel());
        assertNull(vos.get(0).getModelName());
    }

    @Test
    void list_resolves_model_display_name_from_provider_catalog() {
        ExecutorDO e = exec(1L, 5L);
        e.setClientKind("QODER_CLI");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));
        when(presenceManager.currentModel(1L)).thenReturn("qoder3-coder-plus");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("qoder3-coder-plus", "Qoder3 Coder Plus")), new Date()));
        service.setProviderModelCatalogService(catalog);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("qoder3-coder-plus", vos.get(0).getModel());
        assertEquals("Qoder3 Coder Plus", vos.get(0).getModelName());
    }

    @Test
    void list_keeps_model_name_null_when_model_not_in_catalog() {
        ExecutorDO e = exec(1L, 5L);
        e.setClientKind("QODER_CN_CLI");
        when(executorDao.listAll(100L, null)).thenReturn(List.of(e));
        when(presenceManager.currentModel(1L)).thenReturn("unknown-model");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                List.of(new ProviderModelCatalogItemVO("qoder3-coder-plus", "Qoder3 Coder Plus")), new Date()));
        service.setProviderModelCatalogService(catalog);

        List<ExecutorVO> vos = service.listAll(100L, null);

        assertEquals("unknown-model", vos.get(0).getModel());
        assertNull(vos.get(0).getModelName());
    }

    @Test
    void list_skips_name_resolution_for_client_kind_without_provider_catalog() {
        ExecutorDO e = exec(1L, 5L);
        e.setClientKind("CLAUDE_CODE");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));
        when(presenceManager.currentModel(1L)).thenReturn("claude-sonnet-4");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        service.setProviderModelCatalogService(catalog);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("claude-sonnet-4", vos.get(0).getModel());
        assertNull(vos.get(0).getModelName());
        verify(catalog, never()).read(anyString());
    }

    @Test
    void list_keeps_model_name_null_when_catalog_read_fails() {
        ExecutorDO e = exec(1L, 5L);
        e.setClientKind("QODER_CLI");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(e));
        when(presenceManager.currentModel(1L)).thenReturn("qoder3-coder-plus");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.read("qoder")).thenThrow(new RuntimeException("redis down"));
        service.setProviderModelCatalogService(catalog);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("qoder3-coder-plus", vos.get(0).getModel());
        assertNull(vos.get(0).getModelName());
    }

    @Test
    void list_skips_name_resolution_for_executors_without_a_reported_model() {
        ExecutorDO neverReported = exec(1L, 5L);
        neverReported.setClientKind("QODER_CLI");
        ExecutorDO blankModel = exec(2L, 5L);
        blankModel.setClientKind("QODER_CLI");
        ExecutorDO reported = exec(3L, 5L);
        reported.setClientKind("QODER_CLI");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(neverReported, blankModel, reported));
        when(presenceManager.currentModel(2L)).thenReturn("   ");
        when(presenceManager.currentModel(3L)).thenReturn("qoder3-coder-plus");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("qoder3-coder-plus", "Qoder3 Coder Plus")), new Date()));
        service.setProviderModelCatalogService(catalog);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertNull(vos.get(0).getModel());
        assertNull(vos.get(0).getModelName());
        assertEquals("   ", vos.get(1).getModel());
        assertNull(vos.get(1).getModelName());
        assertEquals("qoder3-coder-plus", vos.get(2).getModel());
        assertEquals("Qoder3 Coder Plus", vos.get(2).getModelName());
        // The two executors without a usable model must not trigger extra catalog reads.
        verify(catalog, times(1)).read("qoder");
    }

    @Test
    void list_ignores_catalog_items_with_missing_id_or_name() {
        ExecutorDO resolved = exec(1L, 5L);
        resolved.setClientKind("QODER_CLI");
        ExecutorDO incomplete = exec(2L, 5L);
        incomplete.setClientKind("QODER_CLI");
        when(executorDao.listByAgent(100L, 5L)).thenReturn(List.of(resolved, incomplete));
        when(presenceManager.currentModel(1L)).thenReturn("qoder3-coder-plus");
        when(presenceManager.currentModel(2L)).thenReturn("no-name");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO(null, "Missing Id"),
                        new ProviderModelCatalogItemVO("no-name", null),
                        new ProviderModelCatalogItemVO("qoder3-coder-plus", "Qoder3 Coder Plus")), new Date()));
        service.setProviderModelCatalogService(catalog);

        List<ExecutorVO> vos = service.listByAgent(5L, 100L);

        assertEquals("Qoder3 Coder Plus", vos.get(0).getModelName());
        assertEquals("no-name", vos.get(1).getModel());
        assertNull(vos.get(1).getModelName());
        verify(catalog, times(1)).read("qoder");
    }

    @Test
    void getDetail_maps_model_and_display_name() {
        ExecutorDO e = exec(9L, 5L);
        e.setClientKind("QODER_CLI");
        when(executorDao.findById(9L)).thenReturn(e);
        when(presenceManager.currentModel(9L)).thenReturn("qoder3-coder-plus");
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("qoder3-coder-plus", "Qoder3 Coder Plus")), new Date()));
        service.setProviderModelCatalogService(catalog);

        ExecutorVO vo = service.getDetail(9L, 100L);

        assertEquals("qoder3-coder-plus", vo.getModel());
        assertEquals("Qoder3 Coder Plus", vo.getModelName());
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
