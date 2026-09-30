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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderModelCatalogServiceTest {

    @Test
    void readParsesTheNoTtlRedisSnapshotInOriginalArrayOrder() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        Date lastSuccess = new Date(1_725_177_600_123L);
        when(redisManager.getString("model-catalog:snapshot:qoder")).thenReturn(snapshot("qoder",
                List.of(new ProviderModelCatalogItemVO("first", "First model"),
                        new ProviderModelCatalogItemVO("second", "Second model")), 7L, lastSuccess));

        ProviderModelCatalogVO result = service.read("qoder");

        assertEquals("qoder", result.getProvider());
        assertEquals(List.of(new ProviderModelCatalogItemVO("first", "First model"),
                new ProviderModelCatalogItemVO("second", "Second model")), result.getModels());
        assertEquals(lastSuccess, result.getLastSuccessfulAt());
        verify(redisManager).getString("model-catalog:snapshot:qoder");
    }

    @Test
    void readTreatsSnapshotsWithInvalidSourceOrModelsAsAbsent() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        Date lastSuccess = new Date(1_725_177_600_123L);
        List<String> invalidSnapshots = List.of(
                "{\"provider\":\"qoder\",\"models\":[{}],\"sourceExecutorId\":7,\"lastSuccessfulAt\":"
                        + lastSuccess.getTime() + "}",
                "{\"provider\":\"qoder\",\"models\":[{\"id\":\"model\",\"name\":\"Model\"}],\"lastSuccessfulAt\":"
                        + lastSuccess.getTime() + "}",
                snapshot("qoder", List.of(new ProviderModelCatalogItemVO("model", "Model")), 0L, lastSuccess),
                snapshot("qoder", List.of(new ProviderModelCatalogItemVO("  ", "Model")), 7L, lastSuccess),
                snapshot("qoder", List.of(new ProviderModelCatalogItemVO("model", "  ")), 7L, lastSuccess),
                snapshot("qoder", List.of(new ProviderModelCatalogItemVO("duplicate", "First"),
                        new ProviderModelCatalogItemVO("duplicate", "Second")), 7L, lastSuccess));
        AtomicInteger snapshotIndex = new AtomicInteger();
        when(redisManager.getString("model-catalog:snapshot:qoder"))
                .thenAnswer(invocation -> invalidSnapshots.get(snapshotIndex.getAndIncrement()));

        for (int ignored = 0; ignored < invalidSnapshots.size(); ignored++) {
            ProviderModelCatalogVO result = assertDoesNotThrow(() -> service.read("qoder"));
            assertEquals(List.of(), result.getModels());
            assertNull(result.getLastSuccessfulAt());
        }
    }

    @Test
    void malformedSnapshotIsRefreshDue() {
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put("model-catalog:snapshot:qoder",
                "{\"provider\":\"qoder\",\"models\":[{\"id\":\"model\",\"name\":\"Model\"}],\"lastSuccessfulAt\":"
                        + System.currentTimeMillis() + "}");
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        service.requestRefreshIfDue("qoder");

        verify(redisManager).tryAcquireLock(eq("model-catalog:refresh:qoder"), anyString(), eq(120_000L));
    }

    @Test
    void readReturnsEmptyCatalogWhenRedisSnapshotIsMissingBlankMalformedWrongProviderOrMissingTimestamp() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);
        when(redisManager.getString("model-catalog:snapshot:qoder")).thenReturn(null, "  ", "not-json",
                snapshot("qodercn", List.of(new ProviderModelCatalogItemVO("wrong", "Wrong")), 7L, new Date()),
                "{\"provider\":\"qoder\",\"models\":[]}");

        for (int ignored = 0; ignored < 5; ignored++) {
            ProviderModelCatalogVO result = assertDoesNotThrow(() -> service.read("qoder"));
            assertEquals("qoder", result.getProvider());
            assertEquals(List.of(), result.getModels());
            assertNull(result.getLastSuccessfulAt());
        }
    }

    @Test
    void readRejectsProvidersOutsideTheQoderFamily() {
        RedisManager redisManager = mock(RedisManager.class);
        ProviderModelCatalogService service = new ProviderModelCatalogService(redisManager);

        BizException exception = assertThrows(BizException.class, () -> service.read("claude"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        verifyNoInteractions(redisManager);
    }

    @Test
    void refreshUsesOnlyEligibleSameProviderCandidatesAndReplacesOnlyThatProvidersFullRedisSnapshot() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        SessionRegistry sessionRegistry = mock(SessionRegistry.class);
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put("model-catalog:snapshot:qoder", snapshot("qoder",
                List.of(new ProviderModelCatalogItemVO("old-id", "Old model")), 7L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        state.snapshots.put("model-catalog:snapshot:qodercn", snapshot("qodercn",
                List.of(new ProviderModelCatalogItemVO("cn-id", "CN model")), 8L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);

        ExecutorDO offline = executor(1L, 100L, "QODER_CLI");
        ExecutorDO unknownLease = executor(2L, 100L, "QODER_CLI");
        ExecutorDO noFeature = executor(3L, 100L, "QODER_CLI");
        ExecutorDO active = executor(4L, 100L, "QODER_CLI");
        ExecutorDO wrongProvider = executor(5L, 100L, "QODER_CN_CLI");
        ExecutorDO first = executor(11L, 100L, "QODER_CLI");
        ExecutorDO second = executor(22L, 100L, "QODER_CLI");
        when(executorDao.listByClientKind("QODER_CLI"))
                .thenReturn(List.of(offline, unknownLease, noFeature, active, wrongProvider, first, second));
        makeEligible(registry, presenceManager, dispatchDao, unknownLease);
        when(registry.hasNoReportedRunningDispatches(unknownLease.getId())).thenReturn(false);
        makeEligible(registry, presenceManager, dispatchDao, noFeature);
        when(presenceManager.supportsProtocolFeature(noFeature.getId(), "QODER_MODEL_CATALOG_V1")).thenReturn(false);
        makeEligible(registry, presenceManager, dispatchDao, active);
        when(dispatchDao.countActiveByExecutor(active.getId())).thenReturn(1L);
        makeEligible(registry, presenceManager, dispatchDao, wrongProvider);
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
            service.complete(100L, executorId, result);
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue("qoder");

        assertEquals(List.of(11L, 22L), requestedExecutors);
        assertEquals(List.of(new ProviderModelCatalogItemVO("new-id", "New model")),
                service.read("qoder").getModels());
        assertEquals(List.of(new ProviderModelCatalogItemVO("cn-id", "CN model")),
                service.read("qodercn").getModels());
        verify(executorDao).listByClientKind("QODER_CLI");
        verify(executorDao, never()).listByClientKind("QODER_CN_CLI");
        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(redisManager).setString(eq("model-catalog:snapshot:qoder"), snapshotJson.capture());
        JSONObject written = JSON.parseObject(snapshotJson.getValue());
        assertEquals("qoder", written.getString("provider"));
        assertEquals(22L, written.getLongValue("sourceExecutorId"));
        assertEquals("new-id", written.getJSONArray("models").getJSONObject(0).getString("id"));
        assertFalse(snapshotJson.getValue().contains("old-id"));
        assertFalse(written.containsKey("description"));
        assertTrue(written.containsKey("lastSuccessfulAt"));
        verify(redisManager, never()).setString(eq("model-catalog:snapshot:qodercn"), anyString());
        verify(redisManager, never()).setWithExpire(eq("model-catalog:snapshot:qoder"), anyString(), anyLong());
    }

    @Test
    void refreshSkipsFreshCatalogCooldownAndContendedLock() {
        RedisManager freshRedis = mock(RedisManager.class);
        RedisState freshState = redisStore(freshRedis);
        freshState.snapshots.put("model-catalog:snapshot:qoder", snapshot("qoder",
                List.of(new ProviderModelCatalogItemVO("fresh", "Fresh")), 1L, new Date()));
        ProviderModelCatalogService freshService = refreshService(mock(ExecutorDao.class),
                mock(ExecutorRegistry.class), mock(PresenceManager.class), mock(DispatchDao.class),
                mock(SessionRegistry.class), freshRedis, directExecutor(), refreshProperties());

        freshService.requestRefreshIfDue("qoder");

        verify(freshRedis, never()).tryAcquireLock(anyString(), anyString(), anyLong());

        RedisManager cooldownRedis = mock(RedisManager.class);
        redisStore(cooldownRedis);
        when(cooldownRedis.exists("model-catalog:cooldown:qoder")).thenReturn(true);
        ProviderModelCatalogService cooldownService = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), cooldownRedis,
                directExecutor(), refreshProperties());

        cooldownService.requestRefreshIfDue("qoder");

        verify(cooldownRedis, never()).tryAcquireLock(anyString(), anyString(), anyLong());

        ExecutorDao contendedExecutorDao = mock(ExecutorDao.class);
        RedisManager contendedRedis = mock(RedisManager.class);
        redisStore(contendedRedis);
        when(contendedRedis.exists(anyString())).thenReturn(false);
        when(contendedRedis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        ProviderModelCatalogService contendedService = refreshService(contendedExecutorDao,
                mock(ExecutorRegistry.class), mock(PresenceManager.class), mock(DispatchDao.class),
                mock(SessionRegistry.class), contendedRedis, directExecutor(), refreshProperties());

        contendedService.requestRefreshIfDue("qoder");

        verify(contendedExecutorDao, never()).listByClientKind(anyString());
    }

    @Test
    void completionRejectsWrongTenantExecutorProviderAndReplayWithoutReplacingSnapshot() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO candidate = executor(11L, 100L, "QODER_CLI");
        when(executorDao.listByClientKind("QODER_CLI")).thenReturn(List.of(candidate));
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
                service.complete(101L, 11L, result);
            } else if (currentAttempt == 2) {
                service.complete(100L, 12L, result);
            } else {
                result.put("provider", "qodercn");
                service.complete(100L, 11L, result);
            }
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue("qoder");
        service.requestRefreshIfDue("qoder");
        service.requestRefreshIfDue("qoder");
        service.complete(100L, 11L, requests.get(0));
        service.complete(100L, 11L, JSON.parseObject("{\"requestId\":\"missing\",\"provider\":\"qoder\",\"success\":true,\"models\":[{\"id\":\"ignored\",\"name\":\"Ignored\"}]}"));

        verify(redisManager, never()).setString(anyString(), anyString());
        assertEquals(3, requests.size());
    }

    @Test
    void failedAndEmptyResultsRetainThePriorRedisSnapshotWithoutWritingIt() {
        ExecutorDao executorDao = mock(ExecutorDao.class);
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        PresenceManager presenceManager = mock(PresenceManager.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        RedisManager redisManager = mock(RedisManager.class);
        RedisState state = redisStore(redisManager);
        state.snapshots.put("model-catalog:snapshot:qoder", snapshot("qoder",
                List.of(new ProviderModelCatalogItemVO("old-id", "Old model")), 7L,
                new Date(System.currentTimeMillis() - 86_401_000L)));
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ExecutorDO candidate = executor(11L, 100L, "QODER_CLI");
        when(executorDao.listByClientKind("QODER_CLI")).thenReturn(List.of(candidate));
        makeEligible(registry, presenceManager, dispatchDao, candidate);
        ProviderModelCatalogService service = refreshService(executorDao, registry, presenceManager, dispatchDao,
                mock(SessionRegistry.class), redisManager, directExecutor(), refreshProperties());
        AtomicInteger responses = new AtomicInteger();
        doAnswer(invocation -> {
            JSONObject request = JSON.parseObject(invocation.getArgument(1));
            service.complete(100L, 11L, resultFor(request, responses.incrementAndGet() == 2, List.of()));
            return null;
        }).when(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), anyString());

        service.requestRefreshIfDue("qoder");
        service.requestRefreshIfDue("qoder");

        assertEquals(List.of(new ProviderModelCatalogItemVO("old-id", "Old model")), service.read("qoder").getModels());
        verify(redisManager, never()).setString(anyString(), anyString());
        verify(redisManager, times(2))
                .setWithExpire(eq("model-catalog:cooldown:qoder"), eq("1"), eq(300L));
    }

    @Test
    void markerWriteFailureReleasesTheRefreshLock() {
        RedisManager redisManager = mock(RedisManager.class);
        redisStore(redisManager);
        when(redisManager.exists(anyString())).thenReturn(false);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            throw new IllegalStateException("redis unavailable");
        }).when(redisManager).setWithExpire(eq("model-catalog:inflight:qoder"), eq("1"), anyLong());
        ProviderModelCatalogService service = refreshService(mock(ExecutorDao.class), mock(ExecutorRegistry.class),
                mock(PresenceManager.class), mock(DispatchDao.class), mock(SessionRegistry.class), redisManager,
                directExecutor(), refreshProperties());

        assertDoesNotThrow(() -> service.requestRefreshIfDue("qoder"));

        verify(redisManager).releaseLock(eq("model-catalog:refresh:qoder"), anyString());
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

        service.requestRefreshIfDue("qoder");

        verify(redisManager, never()).tryAcquireLock(anyString(), anyString(), anyLong());
        worker.runNext();
        verify(redisManager).tryAcquireLock(eq("model-catalog:refresh:qoder"), anyString(), eq(120_000L));
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
        List<ExecutorDO> candidates = List.of(executor(1L, 100L, "QODER_CLI"),
                executor(2L, 100L, "QODER_CLI"), executor(3L, 100L, "QODER_CLI"),
                executor(4L, 100L, "QODER_CLI"));
        when(executorDao.listByClientKind("QODER_CLI")).thenReturn(candidates);
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

        service.requestRefreshIfDue("qoder");

        assertEquals(List.of(1L, 2L, 3L), requestedExecutors);
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

    private static ExecutorDO executor(long id, long tenantId, String clientKind) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        executor.setTenantId(tenantId);
        executor.setClientKind(clientKind);
        return executor;
    }

    private static String snapshot(String provider, List<ProviderModelCatalogItemVO> models, long sourceExecutorId,
            Date lastSuccessAt) {
        JSONObject value = new JSONObject(true);
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

        private void runNext() {
            queued.removeFirst().run();
        }
    }
}
