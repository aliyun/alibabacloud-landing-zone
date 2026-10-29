package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.squad.SquadAttributionService;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.websocket.Session;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);
    private static final String BROADCAST_CHANNEL = "node:dispatch:broadcast";
    static final long HEARTBEAT_THROTTLE_SECONDS = 60;
    final ConcurrentHashMap<Long, Instant> heartbeatPersistedAt = new ConcurrentHashMap<>();

    private final ExecutorDao executorDao;
    private final TokenService tokenService;
    private final RedisManager redisManager;
    private final PresenceManager presenceManager;
    private final SessionRegistry sessionRegistry;
    private final ExecutorLaunchConfigService launchConfigService;
    private SquadAttributionService squadAttributionService;
    private ExecutorRestartService restartService;
    private ExecutorUpdateService updateService;
    private ProviderModelCatalogService providerModelCatalogService;

    @Autowired
    public void setRestartService(ExecutorRestartService service) { this.restartService = service; }

    @Autowired
    public void setUpdateService(ExecutorUpdateService service) { this.updateService = service; }

    @Autowired(required = false)
    public void setProviderModelCatalogService(ProviderModelCatalogService service) {
        this.providerModelCatalogService = service;
    }


    @Autowired(required = false)
    public void setSquadAttributionService(SquadAttributionService squadAttributionService) {
        this.squadAttributionService = squadAttributionService;
    }

    @Autowired
    public ExecutorService(ExecutorDao executorDao,
                           TokenService tokenService, RedisManager redisManager,
                           PresenceManager presenceManager, SessionRegistry sessionRegistry,
                           ExecutorLaunchConfigService launchConfigService) {
        this.executorDao = executorDao;
        this.tokenService = tokenService;
        this.redisManager = redisManager;
        this.presenceManager = presenceManager;
        this.sessionRegistry = sessionRegistry;
        this.launchConfigService = launchConfigService;
    }

    @Transactional
    public IssuedExecutorVO create(long agentId, CreateExecutorRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.EXECUTOR_NAME_REQUIRED);
        }
        // REST 与 MCP 入口共用同一类型闸门并统一大小写：落库前拒绝空/非法类型，历史空值只存在于存量数据。
        req.setClientKind(ExecutorLaunchOptionsService.requireCreatableClientKind(
                req.getClientKind(), ErrorCode.EXECUTOR_CLIENT_KIND_INVALID));
        // 先校验启动配置再落库：非法取值不能留下一个已创建的执行器和一个已签发的 Token。
        ExecutorLaunchConfigService.LaunchConfig launchConfig = launchConfigService.resolveForCreate(req);

        ExecutorDO e = new ExecutorDO();
        e.setTenantId(tenantId);
        e.setAgentId(agentId);
        e.setName(req.getName().trim());
        e.setStatus("OFFLINE");
        e.setClientKind(req.getClientKind());
        e.setCreatorId(userId);
        e.setLaunchConfig(launchConfigService.toJson(launchConfig));
        executorDao.insert(e);

        long id = e.getId();
        TokenService.IssuedToken token = tokenService.issue(id);
        executorDao.updateTokenRef(id, token.getTokenRef());
        log.info("executor registered id={} agentId={}", id, agentId);

        IssuedExecutorVO vo = new IssuedExecutorVO();
        vo.setId(id);
        vo.setAgentId(agentId);
        vo.setName(e.getName());
        vo.setToken(token.getPlaintext());
        vo.setClientKind(e.getClientKind());
        vo.setMemoryMode(launchConfig.memoryMode);
        vo.setMaxConcurrentDispatches(launchConfig.maxConcurrentDispatches);
        vo.setModel(launchConfig.model);
        vo.setReasoningEffort(launchConfig.reasoningEffort);
        vo.setContextWindow(launchConfig.contextWindow);
        // The insert leaves config_version to its database default, so the first update carries version=1.
        vo.setConfigVersion(1);
        return vo;
    }

    public String getToken(long id, long tenantId) {
        ExecutorDO e = executorDao.findById(id);
        if (e == null || e.getTenantId() == null || e.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        String plaintext = tokenService.resolve(e.getTokenRef());
        if (plaintext == null || plaintext.isBlank()) {
            throw new BizException(ErrorCode.EXECUTOR_TOKEN_NOT_RETRIEVABLE);
        }
        return plaintext;
    }

    public void recordLastConnectInfo(long executorId, long tenantId, String ip, String hostname) {
        String cleanIp = ip == null || ip.isBlank() ? null : ip.trim();
        // 每次成功接入都以本次值整体覆盖（含 null）：不早退——两个字段都缺失时也必须清掉
        // 上一次连接留下的机器标识，否则列表会把旧 hostname 拼到新连接的机器上。
        executorDao.updateLastConnectInfo(executorId, tenantId, cleanIp, sanitizeHostname(hostname), null);
    }

    private static final int MAX_HOSTNAME_LENGTH = 128;

    /** 主机名是客户端自报值：去空白与控制字符（防日志伪造），截断到列宽，空结果按未上报处理。 */
    public static String sanitizeHostname(String hostname) {
        if (hostname == null) {
            return null;
        }
        String cleaned = hostname.strip().replaceAll("\\p{Cntrl}", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_HOSTNAME_LENGTH ? cleaned.substring(0, MAX_HOSTNAME_LENGTH) : cleaned;
    }

    public void persistHeartbeatIfNeeded(long executorId, long tenantId) {
        Instant now = Instant.now();
        Instant last = heartbeatPersistedAt.get(executorId);
        if (last != null && now.getEpochSecond() - last.getEpochSecond() < HEARTBEAT_THROTTLE_SECONDS) {
            return;
        }
        try {
            executorDao.updateLastHeartbeat(executorId, tenantId);
            heartbeatPersistedAt.put(executorId, now);
        } catch (Exception e) {
            log.warn("failed to persist executor heartbeat executorId={} tenantId={}",
                    executorId, tenantId, e);
        }
    }

    public ExecutorVO getDetail(long id, long tenantId) {
        ExecutorDO e = executorDao.findById(id);
        if (e == null || e.getTenantId() == null || e.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        ExecutorVO vo = toVO(e, presenceManager.presenceState(e.getId()),
                restartService == null ? null : restartService.status(e.getId()));
        fillModelNames(List.of(vo));
        fillUpdates(tenantId, List.of(vo));
        return vo;
    }

    public List<ExecutorVO> listByAgent(long agentId, long tenantId) {
        return toVOs(executorDao.listByAgent(tenantId, agentId), tenantId);
    }

    public List<ExecutorVO> listAll(long tenantId, List<Long> squadIds) {
        return toVOs(executorDao.listAll(tenantId, squadIds), tenantId);
    }

    /**
     * Maps a whole page with one presence batch and one restart-state batch. Reading each field per
     * row made the list endpoint cost about twenty Redis round trips per executor, which is what
     * made the executor page slow to open.
     */
    private List<ExecutorVO> toVOs(List<ExecutorDO> executors, long tenantId) {
        List<Long> ids = new ArrayList<>(executors.size());
        for (ExecutorDO e : executors) {
            ids.add(e.getId());
        }
        Map<Long, PresenceManager.ExecutorPresence> presences = presenceManager.presenceStates(ids);
        Map<Long, JSONObject> restarts = restartService == null ? Map.of() : restartService.statuses(ids);
        List<ExecutorVO> result = new ArrayList<>(executors.size());
        for (ExecutorDO e : executors) {
            result.add(toVO(e, presences.getOrDefault(e.getId(), PresenceManager.ExecutorPresence.ABSENT),
                    restarts.get(e.getId())));
        }
        fillSquads(tenantId, result);
        fillModelNames(result);
        fillUpdates(tenantId, result);
        return result;
    }

    /** Resolves catalog display names for reported model ids, one catalog read per provider. */
    private void fillModelNames(List<ExecutorVO> result) {
        if (providerModelCatalogService == null) {
            return;
        }
        java.util.Map<String, java.util.Map<String, String>> namesByProvider = new java.util.HashMap<>();
        for (ExecutorVO vo : result) {
            if (vo.getModel() == null || vo.getModel().isBlank()) {
                continue;
            }
            String provider = ProviderModelCatalogService.providerForClientKind(vo.getClientKind());
            if (provider == null) {
                continue;
            }
            String name = namesByProvider.computeIfAbsent(provider, this::catalogNames).get(vo.getModel());
            if (name != null) {
                vo.setModelName(name);
            }
        }
    }

    private java.util.Map<String, String> catalogNames(String provider) {
        try {
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (var item : providerModelCatalogService.read(provider).getModels()) {
                if (item.getId() != null && item.getName() != null) {
                    names.putIfAbsent(item.getId(), item.getName());
                }
            }
            return names;
        } catch (RuntimeException exception) {
            log.debug("model catalog lookup failed provider={}", provider, exception);
            return java.util.Map.of();
        }
    }

    private void fillSquads(long tenantId, List<ExecutorVO> result) {
        if (squadAttributionService != null) {
            squadAttributionService.fillExecutorSquads(tenantId, result);
        }
    }

    @Transactional
    public void delete(long id, long tenantId, long userId) {
        ExecutorDO e = executorDao.findById(id);
        if (e == null || e.getTenantId() == null || e.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        executorDao.softDelete(id, tenantId, userId);

        long agentId = e.getAgentId();

        if (redisManager != null) {
            redisManager.setIfAbsent(ExecutorRegistry.deletedKey(id), "1",
                    ExecutorRegistry.TOMBSTONE_TTL_SECONDS);
        }

        if (presenceManager != null) {
            presenceManager.unregister(id, agentId);
        }

        if (sessionRegistry != null) {
            ExecutorSession es = sessionRegistry.findByExecutorId(id);
            if (es != null && es.getSession() != null) {
                try {
                    es.getSession().close();
                    log.info("closed local WS session for deleted executor {}", id);
                } catch (Exception closeEx) {
                    log.warn("failed to close local session for deleted executor {}", id, closeEx);
                }
            }
        }

        if (redisManager != null) {
            redisManager.publish(BROADCAST_CHANNEL,
                    "{\"type\":\"SESSION_CLOSE\",\"executorId\":" + id + "}");
        }

        log.info("executor deleted id={} agentId={}", id, agentId);
    }

    private ExecutorVO toVO(ExecutorDO e, PresenceManager.ExecutorPresence presence, JSONObject restart) {
        ExecutorVO vo = new ExecutorVO();
        vo.setId(e.getId());
        vo.setAgentId(e.getAgentId());
        vo.setAgentName(e.getAgentName());
        vo.setName(e.getName());
        vo.setStatus(presence.online() ? "ONLINE" : "OFFLINE");
        vo.setClientKind(e.getClientKind());
        vo.setLastConnectIp(e.getLastConnectIp());
        vo.setLastConnectHostname(e.getLastConnectHostname());
        vo.setLastHeartbeat(e.getLastHeartbeat());
        vo.setVersion(presence.version());
        vo.setModel(presence.model());
        vo.setGmtCreate(e.getGmtCreate());
        vo.setLastStartedAt(e.getLastStartedAt());
        Set<String> features = presence.protocolFeatures();
        vo.setRestartSupported(features.contains("EXECUTOR_RESTART_V1"));
        vo.setUpdateRestartSupported(features.contains("EXECUTOR_UPDATE_RESTART_V1"));
        vo.setRestartPolicySupported(features.contains("EXECUTOR_RESTART_POLICY_V1"));
        vo.setLaunchCommandSupported(features.contains("EXECUTOR_LAUNCH_COMMAND_V1"));
        vo.setRestart(restart);
        if (updateService != null) {
            String target = updateService.targetVersion();
            vo.setTargetVersion(target);
            vo.setUpgradeSupported(updateService.supportsUpgrade(features));
            // One comparison feeds both flags: an empty result means the versions could not be read, which
            // is not 无需升级 — updateOne only rejects when the comparison succeeded and came out >= 0.
            OptionalInt comparison = RuntimeVersion.compare(vo.getVersion(), target);
            vo.setVersionComparable(comparison.isPresent());
            vo.setUpgradeAvailable(comparison.isPresent() && comparison.getAsInt() < 0);
        }
        return vo;
    }

    private void fillUpdates(long tenantId, List<ExecutorVO> result) {
        if (updateService == null || result.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(result.size());
        for (ExecutorVO vo : result) {
            ids.add(vo.getId());
        }
        Map<Long, ExecutorUpdateVO> updates = updateService.latestByExecutors(tenantId, ids);
        for (ExecutorVO vo : result) {
            vo.setUpdate(updates.get(vo.getId()));
        }
    }
}
