package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Catalog snapshots, refresh coordination and candidate selection are scoped to one workspace and provider, so a
 * quota-limited executor in one workspace can never publish its reduced model list to another. Every entry point takes
 * the tenant explicitly; nothing here falls back to a thread-local workspace or to another workspace's snapshot.
 */
@Service
public class ProviderModelCatalogService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelCatalogService.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final String QODER = "qoder";
    private static final String QODER_CN = "qodercn";
    private static final String CATALOG_FEATURE = "QODER_MODEL_CATALOG_V1";
    /**
     * Versioned key root. Legacy unscoped keys stay untouched and are never read: they cannot prove which workspace
     * produced their list, so a workspace with no v2 snapshot starts from the empty catalog instead.
     */
    private static final String KEY_ROOT = "model-catalog:v2:";
    private static final String SNAPSHOT_KIND = "snapshot";
    private static final String LOCK_KIND = "refresh";
    private static final String INFLIGHT_KIND = "inflight";
    private static final String COOLDOWN_KIND = "cooldown";
    private static final String TICKET_KIND = "ticket";
    private static final String RESULT_KIND = "result";
    private static final long RESULT_POLL_MILLIS = 50L;
    private static final int MAX_CANDIDATES = 3;

    private final ExecutorDao executorDao;
    private final ExecutorRegistry executorRegistry;
    private final PresenceManager presenceManager;
    private final DispatchDao dispatchDao;
    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;
    private final ProviderModelCatalogProperties properties;
    private final Executor workerExecutor;
    /** One queued refresh per workspace/provider; cleared when the task finishes so a later round retries. */
    private final Set<String> pendingRefreshes = ConcurrentHashMap.newKeySet();

    /** Retained for the lightweight read-path tests and callers. */
    public ProviderModelCatalogService(RedisManager redisManager) {
        this(null, null, null, null, null, redisManager, new ProviderModelCatalogProperties(), Runnable::run);
    }

    @Autowired
    public ProviderModelCatalogService(ExecutorDao executorDao, ExecutorRegistry executorRegistry,
            PresenceManager presenceManager, DispatchDao dispatchDao, SessionRegistry sessionRegistry,
            RedisManager redisManager, ProviderModelCatalogProperties properties) {
        this(executorDao, executorRegistry, presenceManager, dispatchDao, sessionRegistry, redisManager, properties,
                newBoundedWorker(properties));
    }

    ProviderModelCatalogService(ExecutorDao executorDao, ExecutorRegistry executorRegistry,
            PresenceManager presenceManager, DispatchDao dispatchDao, SessionRegistry sessionRegistry,
            RedisManager redisManager, ProviderModelCatalogProperties properties, Executor workerExecutor) {
        this.executorDao = executorDao;
        this.executorRegistry = executorRegistry;
        this.presenceManager = presenceManager;
        this.dispatchDao = dispatchDao;
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
        this.properties = properties;
        this.workerExecutor = workerExecutor;
    }

    public ProviderModelCatalogVO read(long tenantId, String provider) {
        requireSupportedProvider(provider);
        requireTenant(tenantId);
        Snapshot snapshot = snapshot(tenantId, provider);
        if (snapshot == null) {
            return new ProviderModelCatalogVO(provider, List.of(), null);
        }
        return new ProviderModelCatalogVO(provider, snapshot.models, snapshot.lastSuccessfulAt);
    }

    /**
     * Queues heartbeat-triggered discovery for the workspace that owns the executor. The tenant comes from the
     * verified executor row, never from the thread or a default workspace.
     */
    public void requestRefreshForExecutor(long executorId) {
        if (!hasRefreshDependencies()) {
            return;
        }
        try {
            workerExecutor.execute(() -> {
                try {
                    ExecutorDO executor = executorDao.findById(executorId);
                    if (executor == null || executor.getTenantId() == null || executor.getTenantId() <= 0) {
                        return;
                    }
                    String provider = providerForClientKind(executor.getClientKind());
                    if (provider != null) {
                        requestRefreshIfDue(executor.getTenantId(), provider);
                    }
                } catch (RuntimeException exception) {
                    log.warn("Provider model catalog heartbeat refresh lookup failed executorId={}", executorId,
                            exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            log.debug("Provider model catalog heartbeat refresh rejected executorId={}", executorId);
        }
    }

    /**
     * Queues one workspace's coordination and the potentially slow runtime exchange on the bounded worker. Repeated
     * requests for the same workspace and provider collapse into the single queued task; the slot is released when
     * that task ends, so a rejected or failed round can be retried instead of being dropped forever.
     */
    public void requestRefreshIfDue(long tenantId, String provider) {
        if (!isSupportedProvider(provider) || tenantId <= 0 || !hasRefreshDependencies()) {
            return;
        }
        String scope = tenantId + ":" + provider;
        if (!pendingRefreshes.add(scope)) {
            return;
        }
        try {
            workerExecutor.execute(() -> {
                try {
                    acquireAndRefresh(tenantId, provider);
                } finally {
                    pendingRefreshes.remove(scope);
                }
            });
        } catch (RejectedExecutionException exception) {
            pendingRefreshes.remove(scope);
            log.debug("Provider model catalog refresh rejected tenantId={} provider={}", tenantId, provider);
        }
    }

    /**
     * Scheduler entry: enumerates every workspace/provider pair that still has a live executor. A pair without any
     * executor is not refreshed, and nothing here depends on an HTTP request context.
     */
    public void requestRefreshForDueTenants() {
        if (!hasRefreshDependencies() || executorDao == null) {
            return;
        }
        List<ExecutorDO> scopes;
        try {
            scopes = executorDao.listActiveTenantClientKinds();
        } catch (RuntimeException exception) {
            log.warn("Provider model catalog tenant scan failed", exception);
            return;
        }
        if (scopes == null) {
            return;
        }
        for (ExecutorDO scope : scopes) {
            if (scope == null || scope.getTenantId() == null || scope.getTenantId() <= 0) {
                continue;
            }
            String provider = providerForClientKind(scope.getClientKind());
            if (provider != null) {
                requestRefreshIfDue(scope.getTenantId(), provider);
            }
        }
    }

    private void acquireAndRefresh(long tenantId, String provider) {
        if (!isRefreshDue(tenantId, provider) || redisManager.exists(cooldownKey(tenantId, provider))) {
            return;
        }
        String lockKey = lockKey(tenantId, provider);
        String owner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(lockKey, owner, properties.getRefreshLockTtlMs())) {
            return;
        }
        String inflightKey = inflightKey(tenantId, provider);
        try {
            redisManager.setWithExpire(inflightKey, "1", lockTtlSeconds());
        } catch (RuntimeException exception) {
            releaseRefresh(lockKey, inflightKey, owner);
            log.warn("Provider model catalog refresh setup failed tenantId={} provider={}", tenantId, provider,
                    exception);
            return;
        }
        refresh(tenantId, provider, owner);
    }

    /** Accepts only the single, tenant/executor/provider-bound response ticketed by a refresh worker. */
    public void complete(long tenantId, long executorId, JSONObject result) {
        if (result == null || tenantId <= 0) {
            return;
        }
        String requestId = trim(result.getString("requestId"));
        if (requestId == null) {
            return;
        }
        Ticket ticket;
        try {
            ticket = redisManager.getAndDelete(ticketKey(tenantId, requestId));
        } catch (RuntimeException exception) {
            log.warn("Provider model catalog result ticket consume failed requestId={}", requestId, exception);
            return;
        }
        String provider = trim(result.getString("provider"));
        if (ticket == null || ticket.tenantId != tenantId || ticket.executorId != executorId
                || !isSupportedProvider(provider) || !ticket.provider.equals(provider)) {
            return;
        }
        boolean success = Boolean.TRUE.equals(result.getBoolean("success"));
        List<CatalogItem> models = success ? normalizeModels(result.get("models")) : List.of();
        CatalogResult catalogResult = new CatalogResult(success && !models.isEmpty(), models);
        try {
            redisManager.set(resultKey(tenantId, requestId), catalogResult, ticketTtlSeconds());
        } catch (RuntimeException exception) {
            log.warn("Provider model catalog result write failed requestId={}", requestId, exception);
        }
    }

    private void refresh(long tenantId, String provider, String owner) {
        String lockKey = lockKey(tenantId, provider);
        String inflightKey = inflightKey(tenantId, provider);
        boolean refreshed = false;
        try {
            for (ExecutorDO executor : selectCandidates(tenantId, provider)) {
                CatalogResult result = requestCatalog(tenantId, provider, executor);
                if (result == null || !result.success || result.models.isEmpty()) {
                    continue;
                }
                writeSnapshot(tenantId, provider, executor.getId(), result.models);
                refreshed = true;
                break;
            }
            if (!refreshed) {
                redisManager.setWithExpire(cooldownKey(tenantId, provider), "1",
                        properties.getFailureCooldownSeconds());
            }
        } catch (RuntimeException exception) {
            log.warn("Provider model catalog refresh failed tenantId={} provider={}", tenantId, provider, exception);
            redisManager.setWithExpire(cooldownKey(tenantId, provider), "1",
                    properties.getFailureCooldownSeconds());
        } finally {
            releaseRefresh(lockKey, inflightKey, owner);
        }
    }

    private void releaseRefresh(String lockKey, String inflightKey, String owner) {
        try {
            redisManager.del(inflightKey);
        } finally {
            redisManager.releaseLock(lockKey, owner);
        }
    }

    private List<ExecutorDO> selectCandidates(long tenantId, String provider) {
        String clientKind = clientKindForProvider(provider);
        if (clientKind == null) {
            return List.of();
        }
        List<ExecutorDO> all = executorDao.listByClientKindAndTenant(tenantId, clientKind);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int maxCandidates = Math.min(MAX_CANDIDATES, Math.max(0, properties.getMaxCandidates()));
        List<ExecutorDO> candidates = new ArrayList<>(maxCandidates);
        for (ExecutorDO executor : all) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            if (!isEligibleCandidate(executor, clientKind, tenantId)) {
                continue;
            }
            candidates.add(executor);
        }
        return candidates;
    }

    /** Second guard on top of the tenant-scoped SQL: a row from another workspace is never a candidate. */
    private boolean isEligibleCandidate(ExecutorDO executor, String clientKind, long tenantId) {
        if (executor == null || executor.getId() == null || executor.getTenantId() == null
                || !Objects.equals(executor.getTenantId(), tenantId)
                || !clientKind.equals(executor.getClientKind())) {
            return false;
        }
        long executorId = executor.getId();
        return executorRegistry.isOnline(executorId)
                && executorRegistry.hasNoReportedRunningDispatches(executorId)
                && presenceManager.supportsProtocolFeature(executorId, CATALOG_FEATURE)
                && dispatchDao.countActiveByExecutor(executorId) == 0L;
    }

    private CatalogResult requestCatalog(long tenantId, String provider, ExecutorDO executor) {
        String requestId = UUID.randomUUID().toString();
        if (!redisManager.set(ticketKey(tenantId, requestId),
                new Ticket(tenantId, executor.getId(), provider), ticketTtlSeconds())) {
            return null;
        }
        try {
            JSONObject frame = new JSONObject(true);
            frame.put("type", "QODER_MODEL_CATALOG_REQUEST");
            frame.put("requestId", requestId);
            frame.put("executorId", executor.getId());
            frame.put("provider", provider);
            send(executor.getId(), frame.toJSONString());
            return waitForResult(tenantId, requestId);
        } catch (Exception exception) {
            log.debug("Provider model catalog request transport failed tenantId={} provider={} executorId={}",
                    tenantId, provider, executor.getId(), exception);
            return null;
        } finally {
            redisManager.del(ticketKey(tenantId, requestId));
            redisManager.del(resultKey(tenantId, requestId));
        }
    }

    private CatalogResult waitForResult(long tenantId, String requestId) {
        long timeoutMillis = Math.max(0L, properties.getRequestTimeoutSeconds()) * 1_000L;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            CatalogResult result;
            try {
                result = redisManager.get(resultKey(tenantId, requestId));
            } catch (RuntimeException exception) {
                log.debug("Provider model catalog result read failed requestId={}", requestId, exception);
                return null;
            }
            if (result != null) {
                return result;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(Math.min(RESULT_POLL_MILLIS,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (true);
    }

    private void send(long executorId, String payload) throws Exception {
        ExecutorSession session = sessionRegistry.findByExecutorId(executorId);
        if (session != null && session.getSession() != null && session.getSession().isOpen()) {
            session.sendText(payload);
            return;
        }
        redisManager.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
    }

    private void writeSnapshot(long tenantId, String provider, long sourceExecutorId, List<CatalogItem> models) {
        try {
            Snapshot snapshot = new Snapshot();
            snapshot.tenantId = tenantId;
            snapshot.provider = provider;
            snapshot.models = models.stream()
                    .map(model -> new ProviderModelCatalogItemVO(model.id, model.name))
                    .toList();
            snapshot.sourceExecutorId = sourceExecutorId;
            snapshot.lastSuccessfulAt = new Date();
            redisManager.setString(snapshotKey(tenantId, provider), JSON_MAPPER.writeValueAsString(snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider model catalog serialization failed", exception);
        }
    }

    private boolean isRefreshDue(long tenantId, String provider) {
        Snapshot snapshot = snapshot(tenantId, provider);
        if (snapshot == null) {
            return true;
        }
        return System.currentTimeMillis() - snapshot.lastSuccessfulAt.getTime() >= properties.getRefreshFixedDelayMs();
    }

    private Snapshot snapshot(long tenantId, String provider) {
        String raw = redisManager.getString(snapshotKey(tenantId, provider));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Snapshot snapshot = JSON_MAPPER.readValue(raw, Snapshot.class);
            return isValidSnapshot(tenantId, provider, snapshot) ? snapshot : null;
        } catch (JsonProcessingException exception) {
            log.warn("Provider model catalog snapshot is invalid tenantId={} provider={}", tenantId, provider);
            return null;
        }
    }

    /** A snapshot whose recorded workspace or provider disagrees with its key is unusable, not a fallback. */
    private static boolean isValidSnapshot(long tenantId, String provider, Snapshot snapshot) {
        if (snapshot == null || snapshot.tenantId != tenantId || !provider.equals(snapshot.provider)
                || snapshot.sourceExecutorId <= 0 || snapshot.models == null || snapshot.models.isEmpty()
                || snapshot.lastSuccessfulAt == null) {
            return false;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ProviderModelCatalogItemVO model : snapshot.models) {
            if (model == null) {
                return false;
            }
            String id = trim(model.getId());
            String name = trim(model.getName());
            if (id == null || name == null || !ids.add(id)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRefreshDependencies() {
        return executorDao != null && executorRegistry != null && presenceManager != null && dispatchDao != null
                && sessionRegistry != null && redisManager != null && properties != null;
    }

    private static List<CatalogItem> normalizeModels(Object rawModels) {
        if (!(rawModels instanceof Collection<?> models)) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<CatalogItem> normalized = new ArrayList<>();
        for (Object rawModel : models) {
            if (!(rawModel instanceof JSONObject model)) {
                continue;
            }
            String id = trim(model.getString("id"));
            String name = trim(model.getString("name"));
            if (id == null || name == null || !ids.add(id)) {
                continue;
            }
            normalized.add(new CatalogItem(id, name));
        }
        return normalized;
    }

    public static String providerForClientKind(String clientKind) {
        if ("QODER_CLI".equals(clientKind)) {
            return QODER;
        }
        if ("QODER_CN_CLI".equals(clientKind)) {
            return QODER_CN;
        }
        return null;
    }

    private static String clientKindForProvider(String provider) {
        if (QODER.equals(provider)) {
            return "QODER_CLI";
        }
        if (QODER_CN.equals(provider)) {
            return "QODER_CN_CLI";
        }
        return null;
    }

    private static boolean isSupportedProvider(String provider) {
        return QODER.equals(provider) || QODER_CN.equals(provider);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String scopeKey(long tenantId, String provider, String kind) {
        return KEY_ROOT + tenantId + ":" + provider + ":" + kind;
    }

    private static String requestKey(long tenantId, String kind, String requestId) {
        return KEY_ROOT + tenantId + ":" + kind + ":" + requestId;
    }

    private static String snapshotKey(long tenantId, String provider) {
        return scopeKey(tenantId, provider, SNAPSHOT_KIND);
    }

    private static String lockKey(long tenantId, String provider) {
        return scopeKey(tenantId, provider, LOCK_KIND);
    }

    private static String inflightKey(long tenantId, String provider) {
        return scopeKey(tenantId, provider, INFLIGHT_KIND);
    }

    private static String cooldownKey(long tenantId, String provider) {
        return scopeKey(tenantId, provider, COOLDOWN_KIND);
    }

    private static String ticketKey(long tenantId, String requestId) {
        return requestKey(tenantId, TICKET_KIND, requestId);
    }

    private static String resultKey(long tenantId, String requestId) {
        return requestKey(tenantId, RESULT_KIND, requestId);
    }

    private int ticketTtlSeconds() {
        return Math.max(1, properties.getTicketTtlSeconds());
    }

    private long lockTtlSeconds() {
        return Math.max(1L, (properties.getRefreshLockTtlMs() + 999L) / 1_000L);
    }

    private void requireSupportedProvider(String provider) {
        if (!isSupportedProvider(provider)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.catalog.provider_invalid", null, "仅支持 qoder 或 qodercn provider");
        }
    }

    private void requireTenant(long tenantId) {
        if (tenantId <= 0) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.catalog.workspace_required", null,
                    "缺少有效工作空间，无法读取模型目录");
        }
    }

    private static Executor newBoundedWorker(ProviderModelCatalogProperties properties) {
        int queueCapacity = Math.max(1, properties.getWorkerQueueCapacity());
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "providerModelCatalogRefresh");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void destroy() {
        if (workerExecutor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    private static final class Snapshot {
        public long tenantId;
        public String provider;
        public List<ProviderModelCatalogItemVO> models;
        public long sourceExecutorId;
        public Date lastSuccessfulAt;
    }

    private static final class Ticket implements Serializable {
        private final long tenantId;
        private final long executorId;
        private final String provider;

        private Ticket(long tenantId, long executorId, String provider) {
            this.tenantId = tenantId;
            this.executorId = executorId;
            this.provider = provider;
        }
    }

    private static final class CatalogResult implements Serializable {
        private final boolean success;
        private final List<CatalogItem> models;

        private CatalogResult(boolean success, List<CatalogItem> models) {
            this.success = success;
            this.models = List.copyOf(models);
        }
    }

    private static final class CatalogItem implements Serializable {
        private final String id;
        private final String name;

        private CatalogItem(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
