package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderModelCatalogServiceTest {

    private static final long WORKSPACE_A = 100L;
    private static final long WORKSPACE_B = 200L;

    @Test
    void readParsesTheNoTtlRedisSnapshotInOriginalArrayOrder() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        Date lastSuccess = new Date(1_725_177_600_123L);
        when(redisManager.getString(scopeKey(WORKSPACE_A, "qoder", "snapshot"))).thenReturn(
                snapshot(WORKSPACE_A, "qoder",
                        List.of(new ProviderModelCatalogItemVO("first", "First model"),
                                new ProviderModelCatalogItemVO("second", "Second model")), 7L, lastSuccess));

        ProviderModelCatalogVO result = service.read(WORKSPACE_A, "qoder");

        assertEquals("qoder", result.getProvider());
        assertEquals(List.of(new ProviderModelCatalogItemVO("first", "First model"),
                new ProviderModelCatalogItemVO("second", "Second model")), result.getModels());
        assertEquals(lastSuccess, result.getLastSuccessfulAt());
        verify(redisManager).getString(scopeKey(WORKSPACE_A, "qoder", "snapshot"));
    }

    @Test
    void readTreatsSnapshotsWithInvalidSourceOrModelsAsAbsent() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        Date lastSuccess = new Date(1_725_177_600_123L);
        List<String> invalidSnapshots = List.of(
                "{\"tenantId\":100,\"provider\":\"qoder\",\"models\":[{}],\"sourceExecutorId\":7,\"lastSuccessfulAt\":"
                        + lastSuccess.getTime() + "}",
                "{\"tenantId\":100,\"provider\":\"qoder\",\"models\":[{\"id\":\"model\",\"name\":\"Model\"}],"
                        + "\"lastSuccessfulAt\":" + lastSuccess.getTime() + "}",
                snapshot(WORKSPACE_A, "qoder", List.of(new ProviderModelCatalogItemVO("model", "Model")), 0L,
                        lastSuccess),
                snapshot(WORKSPACE_A, "qoder", List.of(new ProviderModelCatalogItemVO("  ", "Model")), 7L,
                        lastSuccess),
                snapshot(WORKSPACE_A, "qoder", List.of(new ProviderModelCatalogItemVO("model", "  ")), 7L,
                        lastSuccess),
                snapshot(WORKSPACE_A, "qoder", List.of(new ProviderModelCatalogItemVO("duplicate", "First"),
                        new ProviderModelCatalogItemVO("duplicate", "Second")), 7L, lastSuccess),
                // Another workspace's list never authorises this one, and the provider must match the key too.
                snapshot(WORKSPACE_B, "qoder", List.of(new ProviderModelCatalogItemVO("model", "Model")), 7L,
                        lastSuccess),
                snapshot(WORKSPACE_A, "qodercn", List.of(new ProviderModelCatalogItemVO("model", "Model")), 7L,
                        lastSuccess));
        AtomicInteger snapshotIndex = new AtomicInteger();
        when(redisManager.getString(scopeKey(WORKSPACE_A, "qoder", "snapshot")))
                .thenAnswer(invocation -> invalidSnapshots.get(snapshotIndex.getAndIncrement()));

        for (int ignored = 0; ignored < invalidSnapshots.size(); ignored++) {
            ProviderModelCatalogVO result = assertDoesNotThrow(() -> service.read(WORKSPACE_A, "qoder"));
            assertEquals(List.of(), result.getModels());
            assertNull(result.getLastSuccessfulAt());
        }
    }

    @Test
    void legacyGlobalSnapshotIsNeitherReadNorDeletedForANewWorkspace() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        when(redisManager.getString("model-catalog:snapshot:qoder")).thenReturn(snapshot(WORKSPACE_A, "qoder",
                List.of(new ProviderModelCatalogItemVO("legacy", "Legacy")), 7L, new Date()));

        ProviderModelCatalogVO result = service.read(WORKSPACE_A, "qoder");

        assertEquals(List.of(), result.getModels());
        assertNull(result.getLastSuccessfulAt());
        verify(redisManager).getString(scopeKey(WORKSPACE_A, "qoder", "snapshot"));
        verify(redisManager, never()).getString("model-catalog:snapshot:qoder");
        verify(redisManager, never()).del(any());
    }

    @Test
    void malformedSnapshotIsRefreshDue() {
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put(scopeKey(WORKSPACE_A, "qoder", "snapshot"),
                "{\"tenantId\":100,\"provider\":\"qoder\",\"models\":[{\"id\":\"model\",\"name\":\"Model\"}],"
                        + "\"lastSuccessfulAt\":" + System.currentTimeMillis() + "}");
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_A, "qoder", "refresh")), anyString(),
                eq(120_000L));
    }

    @Test
    void readReturnsEmptyCatalogWhenRedisSnapshotIsMissingBlankMalformedWrongProviderOrMissingTimestamp() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        when(redisManager.getString(scopeKey(WORKSPACE_A, "qoder", "snapshot"))).thenReturn(null, "  ", "not-json",
                snapshot(WORKSPACE_B, "qodercn", List.of(new ProviderModelCatalogItemVO("wrong", "Wrong")), 7L,
                        new Date()),
                "{\"tenantId\":100,\"provider\":\"qoder\",\"models\":[]}");

        for (int ignored = 0; ignored < 5; ignored++) {
            ProviderModelCatalogVO result = service.read(WORKSPACE_A, "qoder");
            assertEquals("qoder", result.getProvider());
            assertEquals(List.of(), result.getModels());
            assertNull(result.getLastSuccessfulAt());
        }
    }

    @Test
    void readRejectsProvidersOutsideTheQoderFamily() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);

        BizException exception = assertThrows(BizException.class, () -> service.read(WORKSPACE_A, "claude"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        verifyNoInteractions(redisManager);
    }

    @Test
    void readRejectsARequestWithoutAWorkspaceInsteadOfFallingBackToAGlobalCatalog() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);

        for (long tenantId : new long[]{0L, -1L}) {
            BizException exception = assertThrows(BizException.class, () -> service.read(tenantId, "qoder"));
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        }

        verifyNoInteractions(redisManager);
    }

    @Test
    void refreshUsesOnlyEligibleSameWorkspaceCandidatesAndReplacesOnlyThatWorkspaceSnapshot() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        SessionRegistry sessionRegistry = mock(SessionRegistry.class);
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put(scopeKey(WORKSPACE_A, "qoder", "snapshot"), snapshot(WORKSPACE_A, "qoder",
                List.of(new ProviderModelCatalogItemVO("old-id", "Old model")), 7L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        state.snapshots.put(scopeKey(WORKSPACE_A, "qodercn", "snapshot"), snapshot(WORKSPACE_A, "qodercn",
                List.of(new ProviderModelCatalogItemVO("cn-id", "CN model")), 8L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        state.snapshots.put(scopeKey(WORKSPACE_B, "qoder", "snapshot"), snapshot(WORKSPACE_B, "qoder",
                List.of(new ProviderModelCatalogItemVO("b-id", "B model")), 9L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);

        ExecutorDO offline = executor(1L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO unknownLease = executor(2L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO noFeature = executor(3L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO active = executor(4L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO wrongProvider = executor(5L, WORKSPACE_A, "QODER_CN_CLI");
        ExecutorDO foreignWorkspace = executor(6L, WORKSPACE_B, "QODER_CLI");
        ExecutorDO first = executor(11L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO second = executor(22L, WORKSPACE_A, "QODER_CLI");
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI"))
                .thenReturn(List.of(offline, unknownLease, noFeature, active, wrongProvider, foreignWorkspace, first,
                        second));
        makeEligible(registry, presenceManager, dispatchDao, unknownLease);
        when(registry.hasNoReportedRunningDispatches(unknownLease.getId())).thenReturn(false);
        makeEligible(registry, presenceManager, dispatchDao, noFeature);
        when(presenceManager.supportsProtocolFeature(noFeature.getId(), "QODER_MODEL_CATALOG_V1")).thenReturn(false);
        makeEligible(registry, presenceManager, dispatchDao, active);
        when(dispatchDao.countActiveByExecutor(active.getId())).thenReturn(1L);
        makeEligible(registry, presenceManager, dispatchDao, wrongProvider);
        makeEligible(registry, presenceManager, dispatchDao, foreignWorkspace);
        makeEligible(registry, presenceManager, dispatchDao, first);
        makeEligible(registry, presenceManager, dispatchDao, second);

        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                sessionRegistry, redisManager, directExecutor(), refreshProperties());
        List<Long> requestedExecutors = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            long executorId = request.getLongValue("executorId");
            requestedExecutors.add(executorId);
            JSONObject result = resultFor(request, attempts.incrementAndGet() == 2,
                    attempts.get() == 2 ? List.of(itemJson("new-id", "New model")) : List.of());
            service.complete(WORKSPACE_A, executorId, result);
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        assertEquals(List.of(11L, 22L), requestedExecutors);
        assertEquals(List.of(new ProviderModelCatalogItemVO("new-id", "New model")),
                service.read(WORKSPACE_A, "qoder").getModels());
        assertEquals(List.of(new ProviderModelCatalogItemVO("cn-id", "CN model")),
                service.read(WORKSPACE_A, "qodercn").getModels());
        assertEquals(List.of(new ProviderModelCatalogItemVO("b-id", "B model")),
                service.read(WORKSPACE_B, "qoder").getModels());
        verify(executorDao).listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI");
        verify(executorDao, never()).listByClientKindAndTenant(WORKSPACE_A, "QODER_CN_CLI");
        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(redisManager).setString(eq(scopeKey(WORKSPACE_A, "qoder", "snapshot")), snapshotJson.capture());
        JSONObject written = JSON.parseObject(snapshotJson.getValue());
        assertEquals(WORKSPACE_A, written.getLongValue("tenantId"));
        assertEquals("qoder", written.getString("provider"));
        assertEquals(22L, written.getLongValue("sourceExecutorId"));
        assertEquals("new-id", written.getJSONArray("models").getJSONObject(0).getString("id"));
        assertFalse(snapshotJson.getValue().contains("old-id"));
        assertFalse(written.containsKey("description"));
        assertTrue(written.containsKey("lastSuccessfulAt"));
        verify(redisManager, never()).setString(eq(scopeKey(WORKSPACE_A, "qodercn", "snapshot")), anyString());
        verify(redisManager, never()).setString(eq(scopeKey(WORKSPACE_B, "qoder", "snapshot")), anyString());
        verify(redisManager, never()).setWithExpire(eq(scopeKey(WORKSPACE_A, "qoder", "snapshot")), anyString(),
                anyLong());
    }

    @Test
    void refreshRejectsACandidateRowThatBelongsToAnotherWorkspace() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO foreign = executor(31L, WORKSPACE_B, "QODER_CLI");
        // Defensive guard: even if the query misbehaves, another workspace's executor is never asked.
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI")).thenReturn(List.of(foreign));
        makeEligible(registry, presenceManager, dispatchDao, foreign);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), refreshProperties());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        verify(redisManager, never()).publish(anyString(), anyString());
        verify(redisManager).setWithExpire(eq(scopeKey(WORKSPACE_A, "qoder", "cooldown")), eq("1"), eq(300L));
        verify(registry, never()).isOnline(31L);
    }

    @Test
    void fourWorkspaceProviderDomainsKeepIndependentSnapshotsCooldownsAndCandidates() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put(scopeKey(WORKSPACE_A, "qoder", "snapshot"), snapshot(WORKSPACE_A, "qoder",
                List.of(new ProviderModelCatalogItemVO("a-qoder-old", "A qoder old")), 41L, new Date(0L)));
        state.snapshots.put(scopeKey(WORKSPACE_A, "qodercn", "snapshot"), snapshot(WORKSPACE_A, "qodercn",
                List.of(new ProviderModelCatalogItemVO("a-cn", "A cn")), 42L, new Date(0L)));
        state.snapshots.put(scopeKey(WORKSPACE_B, "qoder", "snapshot"), snapshot(WORKSPACE_B, "qoder",
                List.of(new ProviderModelCatalogItemVO("b-qoder", "B qoder")), 43L, new Date(0L)));
        state.snapshots.put(scopeKey(WORKSPACE_B, "qodercn", "snapshot"), snapshot(WORKSPACE_B, "qodercn",
                List.of(new ProviderModelCatalogItemVO("b-cn", "B cn")), 44L, new Date(0L)));
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);

        ExecutorDO aQoder = executor(51L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO aCn = executor(52L, WORKSPACE_A, "QODER_CN_CLI");
        ExecutorDO bQoder = executor(53L, WORKSPACE_B, "QODER_CLI");
        ExecutorDO bCn = executor(54L, WORKSPACE_B, "QODER_CN_CLI");
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI")).thenReturn(List.of(aQoder));
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CN_CLI")).thenReturn(List.of(aCn));
        when(executorDao.listByClientKindAndTenant(WORKSPACE_B, "QODER_CLI")).thenReturn(List.of(bQoder));
        when(executorDao.listByClientKindAndTenant(WORKSPACE_B, "QODER_CN_CLI")).thenReturn(List.of(bCn));
        for (ExecutorDO candidate : List.of(aQoder, aCn, bQoder, bCn)) {
            makeEligible(registry, presenceManager, dispatchDao, candidate);
        }
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), refreshProperties());
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            long executorId = request.getLongValue("executorId");
            long tenantId = executorId == 53L || executorId == 54L ? WORKSPACE_B : WORKSPACE_A;
            // A's qoder account is quota-limited and reports only its base model.
            List<JSONObject> models = switch ((int) executorId) {
                case 51 -> List.of(itemJson("a-base", "A base"));
                case 52 -> List.of(itemJson("a-cn-1", "A cn 1"), itemJson("a-cn-2", "A cn 2"));
                case 53 -> List.of(itemJson("b-1", "B 1"), itemJson("b-2", "B 2"), itemJson("b-3", "B 3"));
                default -> List.of(itemJson("b-cn-1", "B cn 1"), itemJson("b-cn-2", "B cn 2"));
            };
            service.complete(tenantId, executorId, resultFor(request, true, models));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_A, "qodercn");
        service.requestRefreshIfDue(WORKSPACE_B, "qodercn");

        assertEquals(List.of(new ProviderModelCatalogItemVO("a-base", "A base")),
                service.read(WORKSPACE_A, "qoder").getModels());
        assertEquals(List.of(new ProviderModelCatalogItemVO("a-cn-1", "A cn 1"),
                        new ProviderModelCatalogItemVO("a-cn-2", "A cn 2")),
                service.read(WORKSPACE_A, "qodercn").getModels());
        // B/qoder was not refreshed in this round and keeps its own complete snapshot untouched.
        assertEquals(List.of(new ProviderModelCatalogItemVO("b-qoder", "B qoder")),
                service.read(WORKSPACE_B, "qoder").getModels());
        assertEquals(List.of(new ProviderModelCatalogItemVO("b-cn-1", "B cn 1"),
                        new ProviderModelCatalogItemVO("b-cn-2", "B cn 2")),
                service.read(WORKSPACE_B, "qodercn").getModels());
        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_A, "qoder", "refresh")), anyString(), anyLong());
        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_B, "qodercn", "refresh")), anyString(),
                anyLong());
        verify(redisManager, never()).tryAcquireLock(eq(scopeKey(WORKSPACE_B, "qoder", "refresh")), anyString(),
                anyLong());
        verify(redisManager, never()).setString(eq(scopeKey(WORKSPACE_B, "qoder", "snapshot")), anyString());
        verify(redisManager, never()).del(eq(scopeKey(WORKSPACE_B, "qoder", "snapshot")));
    }

    @Test
    void workspaceCooldownOnlyBlocksThatWorkspaceAndRetainsItsOwnLastSnapshot() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        Date aLastSuccess = new Date(System.currentTimeMillis() - 86_401_000L);
        state.snapshots.put(scopeKey(WORKSPACE_A, "qoder", "snapshot"), snapshot(WORKSPACE_A, "qoder",
                List.of(new ProviderModelCatalogItemVO("a-last", "A last")), 61L, aLastSuccess));
        when(redisManager.exists(scopeKey(WORKSPACE_A, "qoder", "cooldown"))).thenReturn(true);
        when(redisManager.exists(scopeKey(WORKSPACE_B, "qoder", "cooldown"))).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO bCandidate = executor(62L, WORKSPACE_B, "QODER_CLI");
        when(executorDao.listByClientKindAndTenant(WORKSPACE_B, "QODER_CLI")).thenReturn(List.of(bCandidate));
        makeEligible(registry, presenceManager, dispatchDao, bCandidate);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), refreshProperties());
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            service.complete(WORKSPACE_B, 62L, resultFor(request, true, List.of(itemJson("b-new", "B new"))));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_B, "qoder");

        // A stays in cooldown with its previous snapshot and is never refreshed through B's executor.
        verify(redisManager, never()).tryAcquireLock(eq(scopeKey(WORKSPACE_A, "qoder", "refresh")), anyString(),
                anyLong());
        verify(executorDao, never()).listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI");
        assertEquals(List.of(new ProviderModelCatalogItemVO("a-last", "A last")),
                service.read(WORKSPACE_A, "qoder").getModels());
        assertEquals(aLastSuccess, service.read(WORKSPACE_A, "qoder").getLastSuccessfulAt());
        assertEquals(List.of(new ProviderModelCatalogItemVO("b-new", "B new")),
                service.read(WORKSPACE_B, "qoder").getModels());
    }

    @Test
    void refreshSkipsFreshCatalogCooldownAndContendedLock() {
        RedisManager freshRedis = mock(RedisManager.class);
        RedisState freshState = redisStore(freshRedis);
        freshState.snapshots.put(scopeKey(WORKSPACE_A, "qoder", "snapshot"), snapshot(WORKSPACE_A, "qoder",
                List.of(new ProviderModelCatalogItemVO("fresh", "Fresh")), 1L, new Date()));
        ProviderModelCatalogService freshService = refreshService(mock(ExecutorDao.class),
                mock(ExecutorRegistry.class), mock(PresenceManager.class), mock(DispatchDao.class),
                mock(SessionRegistry.class), freshRedis, directExecutor(), refreshProperties());

        freshService.requestRefreshIfDue(WORKSPACE_A, "qoder");

        verify(freshRedis, never()).tryAcquireLock(anyString(), anyString(), anyLong());

        RedisManager cooldownRedis = mock(RedisManager.class);
        redisStore(cooldownRedis);
        when(cooldownRedis.exists(scopeKey(WORKSPACE_A, "qoder", "cooldown"))).thenReturn(true);
        ProviderModelCatalogService cooldownService = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), cooldownRedis,
                directExecutor(), refreshProperties());

        cooldownService.requestRefreshIfDue(WORKSPACE_A, "qoder");

        verify(cooldownRedis, never()).tryAcquireLock(anyString(), anyString(), anyLong());

        ExecutorDao contendedExecutorDao = mock(ExecutorDao.class);
        RedisManager contendedRedis = mock(RedisManager.class);
        redisStore(contendedRedis);
        when(contendedRedis.exists(anyString())).thenReturn(false);
        when(contendedRedis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        ProviderModelCatalogService contendedService = refreshService(contendedExecutorDao,
                mock(ExecutorRegistry.class), mock(PresenceManager.class), mock(DispatchDao.class),
                mock(SessionRegistry.class), contendedRedis, directExecutor(), refreshProperties());

        contendedService.requestRefreshIfDue(WORKSPACE_A, "qoder");

        verify(contendedExecutorDao, never()).listByClientKindAndTenant(anyLong(), anyString());
    }

    @Test
    void completionRejectsWrongTenantExecutorAndProviderResponses() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO candidate = executor(11L, WORKSPACE_A, "QODER_CLI");
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI")).thenReturn(List.of(candidate));
        makeEligible(registry, presenceManager, dispatchDao, candidate);
        ProviderModelCatalogProperties properties = refreshProperties();
        properties.setRequestTimeoutSeconds(0);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), properties);
        AtomicInteger attempt = new AtomicInteger();
        List<JSONObject> requests = new ArrayList<>();
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            requests.add(request);
            int currentAttempt = attempt.incrementAndGet();
            JSONObject result = resultFor(request, true, List.of(itemJson("new-id", "New model")));
            if (currentAttempt == 1) {
                service.complete(WORKSPACE_B, 11L, result);
            } else if (currentAttempt == 2) {
                service.complete(WORKSPACE_A, 12L, result);
            } else {
                result.put("provider", "qodercn");
                service.complete(WORKSPACE_A, 11L, result);
            }
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.complete(WORKSPACE_A, 11L, JSON.parseObject("{\"requestId\":\"missing\",\"provider\":\"qoder\","
                + "\"success\":true,\"models\":[{\"id\":\"ignored\",\"name\":\"Ignored\"}]}"));

        verify(redisManager, never()).setString(anyString(), anyString());
        assertEquals(3, requests.size());
    }

    @Test
    void completionWritesOnceForTheTicketedWorkspaceAndIgnoresReplays() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO candidate = executor(11L, WORKSPACE_A, "QODER_CLI");
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI")).thenReturn(List.of(candidate));
        makeEligible(registry, presenceManager, dispatchDao, candidate);
        ProviderModelCatalogProperties properties = refreshProperties();
        properties.setRequestTimeoutSeconds(0);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), properties);
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            service.complete(WORKSPACE_B, 11L, resultFor(request, true,
                    List.of(itemJson("foreign", "Foreign"))));
            service.complete(WORKSPACE_A, 11L, resultFor(request, true,
                    List.of(itemJson("new-id", "New model"))));
            service.complete(WORKSPACE_A, 11L, resultFor(request, true,
                    List.of(itemJson("replay", "Replay"))));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(redisManager, times(1)).setString(eq(scopeKey(WORKSPACE_A, "qoder", "snapshot")),
                snapshotJson.capture());
        assertTrue(snapshotJson.getValue().contains("new-id"));
        assertFalse(snapshotJson.getValue().contains("replay"));
        assertFalse(snapshotJson.getValue().contains("foreign"));
        verify(redisManager, never()).setString(eq(scopeKey(WORKSPACE_B, "qoder", "snapshot")), anyString());
    }

    @Test
    void failedAndEmptyResultsRetainThePriorRedisSnapshotWithoutWritingIt() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put(scopeKey(WORKSPACE_A, "qoder", "snapshot"), snapshot(WORKSPACE_A, "qoder",
                List.of(new ProviderModelCatalogItemVO("old-id", "Old model")), 7L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO candidate = executor(11L, WORKSPACE_A, "QODER_CLI");
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI")).thenReturn(List.of(candidate));
        makeEligible(registry, presenceManager, dispatchDao, candidate);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), refreshProperties());
        AtomicInteger responses = new AtomicInteger();
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            service.complete(WORKSPACE_A, 11L, resultFor(request, responses.incrementAndGet() == 2, List.of()));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        assertEquals(List.of(new ProviderModelCatalogItemVO("old-id", "Old model")),
                service.read(WORKSPACE_A, "qoder").getModels());
        verify(redisManager, never()).setString(anyString(), anyString());
        verify(redisManager, times(2))
                .setWithExpire(eq(scopeKey(WORKSPACE_A, "qoder", "cooldown")), eq("1"), eq(300L));
    }

    @Test
    void markerWriteFailureReleasesTheRefreshLock() {
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            throw new IllegalStateException("redis unavailable");
        }).when(redisManager).setWithExpire(eq(scopeKey(WORKSPACE_A, "qoder", "inflight")), eq("1"), anyLong());
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        assertDoesNotThrow(() -> service.requestRefreshIfDue(WORKSPACE_A, "qoder"));

        verify(redisManager).releaseLock(eq(scopeKey(WORKSPACE_A, "qoder", "refresh")), anyString());
    }

    @Test
    void dueRefreshAcquiresItsLockOnlyWhenTheQueuedWorkerStarts() {
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        ControlledExecutor worker = new ControlledExecutor();
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                worker, refreshProperties());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        verify(redisManager, never()).tryAcquireLock(anyString(), anyString(), anyLong());
        worker.runNext();
        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_A, "qoder", "refresh")), anyString(),
                eq(120_000L));
    }

    @Test
    void refreshNeverUsesMoreThanThreeCandidatesWhenConfiguredHigher() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        List<ExecutorDO> candidates = List.of(executor(1L, WORKSPACE_A, "QODER_CLI"),
                executor(2L, WORKSPACE_A, "QODER_CLI"), executor(3L, WORKSPACE_A, "QODER_CLI"),
                executor(4L, WORKSPACE_A, "QODER_CLI"));
        when(executorDao.listByClientKindAndTenant(WORKSPACE_A, "QODER_CLI")).thenReturn(candidates);
        candidates.forEach(candidate -> makeEligible(registry, presenceManager, dispatchDao, candidate));
        ProviderModelCatalogProperties properties = refreshProperties();
        properties.setMaxCandidates(10);
        properties.setRequestTimeoutSeconds(0);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), properties);
        List<Long> requestedExecutors = new ArrayList<>();
        doAnswer(invocation -> {
            requestedExecutors.add(JSON.parseObject(invocation.getArgument(1)).getLongValue("executorId"));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        assertEquals(List.of(1L, 2L, 3L), requestedExecutors);
    }

    @Test
    void repeatedRequestsForOneScopeQueueOneTaskAndDifferentScopesQueueTheirOwn() {
        ControlledExecutor worker = new ControlledExecutor();
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class),
                mock(RedisManager.class), worker, refreshProperties());

        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_A, "qoder");
        service.requestRefreshIfDue(WORKSPACE_A, "qodercn");
        service.requestRefreshIfDue(WORKSPACE_B, "qoder");

        assertEquals(3, worker.size());
        // Unsupported provider and missing workspace never occupy a slot.
        service.requestRefreshIfDue(WORKSPACE_A, "claude");
        service.requestRefreshIfDue(0L, "qoder");
        assertEquals(3, worker.size());
    }

    @Test
    void rejectedSubmissionReleasesTheDedupeSlotSoALaterRoundCanRetry() {
        SwitchableExecutor worker = new SwitchableExecutor();
        worker.rejecting.set(true);
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class),
                mock(RedisManager.class), worker, refreshProperties());

        assertDoesNotThrow(() -> service.requestRefreshIfDue(WORKSPACE_A, "qoder"));

        worker.rejecting.set(false);
        service.requestRefreshIfDue(WORKSPACE_A, "qoder");

        assertEquals(1, worker.size());
    }

    @Test
    void heartbeatRefreshUsesTheWorkspaceOfTheExecutorThatReportedIt() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(executorDao.findById(77L)).thenReturn(executor(77L, WORKSPACE_B, "QODER_CN_CLI"));
        ProviderModelCatalogService service = refreshService(executorDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        service.requestRefreshForExecutor(77L);

        verify(executorDao).listByClientKindAndTenant(WORKSPACE_B, "QODER_CN_CLI");
        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_B, "qodercn", "refresh")), anyString(),
                anyLong());
    }

    @Test
    void heartbeatRefreshSkipsMissingExecutorsWithoutWorkspaceOrWithoutQoderKind() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        when(executorDao.findById(1L)).thenReturn(null);
        when(executorDao.findById(2L)).thenReturn(executor(2L, WORKSPACE_A, "CLAUDE_CODE"));
        ExecutorDO noWorkspace = executor(3L, WORKSPACE_A, "QODER_CLI");
        noWorkspace.setTenantId(null);
        when(executorDao.findById(3L)).thenReturn(noWorkspace);
        ProviderModelCatalogService service = refreshService(executorDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        service.requestRefreshForExecutor(1L);
        service.requestRefreshForExecutor(2L);
        service.requestRefreshForExecutor(3L);

        verify(executorDao, never()).listByClientKindAndTenant(anyLong(), anyString());
        verifyNoInteractions(redisManager);
    }

    @Test
    void heartbeatLookupAndRejectionNeverPropagate() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        when(executorDao.findById(5L)).thenThrow(new IllegalStateException("db down"));
        ProviderModelCatalogService failingLookup = refreshService(executorDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class),
                mock(RedisManager.class), directExecutor(), refreshProperties());

        assertDoesNotThrow(() -> failingLookup.requestRefreshForExecutor(5L));

        SwitchableExecutor rejecting = new SwitchableExecutor();
        rejecting.rejecting.set(true);
        ProviderModelCatalogService rejected = refreshService(executorDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class),
                mock(RedisManager.class), rejecting, refreshProperties());

        assertDoesNotThrow(() -> rejected.requestRefreshForExecutor(5L));
    }

    @Test
    void schedulerEnumeratesEveryWorkspaceProviderPairThatHasALiveExecutor() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        ExecutorDO aQoder = executor(1L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO aQoderDuplicate = executor(2L, WORKSPACE_A, "QODER_CLI");
        ExecutorDO aCn = executor(3L, WORKSPACE_A, "QODER_CN_CLI");
        ExecutorDO bQoder = executor(4L, WORKSPACE_B, "QODER_CLI");
        ExecutorDO unknownKind = executor(5L, WORKSPACE_B, "CLAUDE_CODE");
        ExecutorDO noWorkspace = executor(6L, WORKSPACE_B, "QODER_CLI");
        noWorkspace.setTenantId(null);
        // Arrays.asList tolerates the null row a defensive scan guard must survive.
        when(executorDao.listActiveTenantClientKinds()).thenReturn(java.util.Arrays.asList(aQoder, aQoderDuplicate,
                aCn, bQoder, unknownKind, noWorkspace, null));
        ProviderModelCatalogService service = refreshService(executorDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        service.requestRefreshForDueTenants();

        verify(executorDao).listActiveTenantClientKinds();
        verify(redisManager, atLeastOnce()).tryAcquireLock(eq(scopeKey(WORKSPACE_A, "qoder", "refresh")),
                anyString(), anyLong());
        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_A, "qodercn", "refresh")), anyString(),
                anyLong());
        verify(redisManager).tryAcquireLock(eq(scopeKey(WORKSPACE_B, "qoder", "refresh")), anyString(), anyLong());
        verify(redisManager, never()).tryAcquireLock(eq(scopeKey(WORKSPACE_B, "qodercn", "refresh")), anyString(),
                anyLong());
    }

    @Test
    void schedulerSkipsAnEmptyOrFailedScanInsteadOfRefreshingAGlobalDefault() {
        ExecutorDao emptyDao = mock(ExecutorDao.class);
        when(emptyDao.listActiveTenantClientKinds()).thenReturn(List.of());
        RedisManager emptyRedis = mock(RedisManager.class);
        ProviderModelCatalogService emptyService = refreshService(emptyDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), emptyRedis,
                directExecutor(), refreshProperties());

        emptyService.requestRefreshForDueTenants();

        verifyNoInteractions(emptyRedis);

        ExecutorDao brokenDao = mock(ExecutorDao.class);
        when(brokenDao.listActiveTenantClientKinds()).thenThrow(new IllegalStateException("db down"));
        ProviderModelCatalogService brokenService = refreshService(brokenDao, mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class),
                mock(RedisManager.class), directExecutor(), refreshProperties());

        assertDoesNotThrow(brokenService::requestRefreshForDueTenants);

        ProviderModelCatalogService unconfigured = new ProviderModelCatalogService(mock(RedisManager.class));
        assertDoesNotThrow(unconfigured::requestRefreshForDueTenants);
    }

    private static ProviderModelCatalogService refreshService(ExecutorDao executorDao, ExecutorRegistry registry,
            PresenceManager presenceManager, DispatchDao dispatchDao, SessionRegistry sessionRegistry,
            RedisManager redisManager, Executor workerExecutor, ProviderModelCatalogProperties properties) {
        return new ProviderModelCatalogService(executorDao, registry, presenceManager, dispatchDao, sessionRegistry,
                redisManager, properties, workerExecutor);
    }

    private static ProviderModelCatalogProperties refreshProperties() {
        ProviderModelCatalogProperties properties = new ProviderModelCatalogProperties();
        properties.setRefreshFixedDelayMs(86_400_000L);
        properties.setRefreshLockTtlMs(120_000L);
        properties.setRequestTimeoutSeconds(20);
        properties.setMaxCandidates(3);
        properties.setFailureCooldownSeconds(300L);
        properties.setWorkerQueueCapacity(8);
        return properties;
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }

    private static String scopeKey(long tenantId, String provider, String kind) {
        return "model-catalog:v2:" + tenantId + ":" + provider + ":" + kind;
    }

    private static ExecutorDO executor(long id, long tenantId, String clientKind) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        executor.setTenantId(tenantId);
        executor.setClientKind(clientKind);
        return executor;
    }

    private static String snapshot(long tenantId, String provider, List<ProviderModelCatalogItemVO> models,
            long sourceExecutorId, Date lastSuccessAt) {
        JSONObject value = new JSONObject(true);
        value.put("tenantId", tenantId);
        value.put("provider", provider);
        value.put("models", models);
        value.put("sourceExecutorId", sourceExecutorId);
        value.put("lastSuccessfulAt", lastSuccessAt.getTime());
        return value.toJSONString();
    }

    private static void makeEligible(ExecutorRegistry registry, PresenceManager presenceManager,
            DispatchDao dispatchDao, ExecutorDO executor) {
        when(registry.isOnline(executor.getId())).thenReturn(true);
        when(registry.hasNoReportedRunningDispatches(executor.getId())).thenReturn(true);
        when(presenceManager.supportsProtocolFeature(executor.getId(), "QODER_MODEL_CATALOG_V1")).thenReturn(true);
        when(dispatchDao.countActiveByExecutor(executor.getId())).thenReturn(0L);
    }

    private static JSONObject itemJson(String id, String name) {
        JSONObject item = new JSONObject(true);
        item.put("id", id);
        item.put("name", name);
        return item;
    }

    private static JSONObject resultFor(JSONObject request, boolean success, List<JSONObject> models) {
        JSONObject result = new JSONObject(true);
        result.put("requestId", request.getString("requestId"));
        result.put("provider", request.getString("provider"));
        result.put("success", success);
        JSONArray array = new JSONArray();
        array.addAll(models);
        result.put("models", array);
        return result;
    }

    private static RedisState redisStore(RedisManager redisManager) {
        RedisState state = new RedisState();
        when(redisManager.set(any(Serializable.class), any(Serializable.class), anyInt()))
                .thenAnswer(invocation -> {
                    Object key = invocation.getArgument(0);
                    state.values.put(String.valueOf(key), invocation.getArgument(1));
                    return true;
                });
        when(redisManager.get(any(Serializable.class))).thenAnswer(invocation -> {
            Object key = invocation.getArgument(0);
            return state.values.get(String.valueOf(key));
        });
        when(redisManager.getAndDelete(any(Serializable.class))).thenAnswer(invocation -> {
            Object key = invocation.getArgument(0);
            return state.values.remove(String.valueOf(key));
        });
        doAnswer(invocation -> {
            Object key = invocation.getArgument(0);
            state.values.remove(String.valueOf(key));
            return 1L;
        }).when(redisManager).del(any());
        when(redisManager.getString(anyString())).thenAnswer(invocation -> state.snapshots.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            state.snapshots.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(redisManager).setString(anyString(), anyString());
        return state;
    }

    private static final class RedisState {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, String> snapshots = new LinkedHashMap<>();
    }

    private static final class ControlledExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.addLast(command);
        }

        private int size() {
            return queued.size();
        }

        private void runNext() {
            queued.removeFirst().run();
        }
    }

    private static final class SwitchableExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();
        private final AtomicBoolean rejecting = new AtomicBoolean(false);

        @Override
        public void execute(Runnable command) {
            if (rejecting.get()) {
                throw new RejectedExecutionException("queue full");
            }
            queued.addLast(command);
        }

        private int size() {
            return queued.size();
        }
    }
}
