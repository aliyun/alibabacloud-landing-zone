package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.redis.RedisManager;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);
    private static final String BROADCAST_CHANNEL = "node:dispatch:broadcast";
    static final long HEARTBEAT_THROTTLE_SECONDS = 60;
    final ConcurrentHashMap<Long, Instant> heartbeatPersistedAt = new ConcurrentHashMap<>();

    private final ExecutorDao executorDao;
    private final ExecutorRegistry registry;
    private final TokenService tokenService;
    private final RedisManager redisManager;
    private final PresenceManager presenceManager;
    private final SessionRegistry sessionRegistry;

    @Autowired
    public ExecutorService(ExecutorDao executorDao, ExecutorRegistry registry,
                           TokenService tokenService, RedisManager redisManager,
                           PresenceManager presenceManager, SessionRegistry sessionRegistry) {
        this.executorDao = executorDao;
        this.registry = registry;
        this.tokenService = tokenService;
        this.redisManager = redisManager;
        this.presenceManager = presenceManager;
        this.sessionRegistry = sessionRegistry;
    }

    public ExecutorService(ExecutorDao executorDao, ExecutorRegistry registry,
                           TokenService tokenService) {
        this(executorDao, registry, tokenService, null, null, null);
    }

    @Transactional
    public IssuedExecutorVO create(long agentId, CreateExecutorRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.EXECUTOR_NAME_REQUIRED);
        }
        ExecutorDO e = new ExecutorDO();
        e.setTenantId(tenantId);
        e.setAgentId(agentId);
        e.setName(req.getName().trim());
        e.setStatus("OFFLINE");
        e.setClientKind(req.getClientKind());
        e.setCreatorId(userId);
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
        return vo;
    }

    public String getToken(long id, long tenantId) {
        ExecutorDO e = executorDao.findById(id);
        if (e == null || e.getTenantId() == null || e.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        String plaintext = tokenService.resolve(e.getTokenRef());
        if (plaintext == null) {
            throw new BizException(ErrorCode.EXECUTOR_TOKEN_NOT_RETRIEVABLE);
        }
        return plaintext;
    }

    public void recordLastConnectIp(long executorId, long tenantId, String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        executorDao.updateLastConnectIp(executorId, tenantId, ip, null);
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

    public List<ExecutorVO> listByAgent(long agentId, long tenantId) {
        List<ExecutorVO> result = new ArrayList<>();
        for (ExecutorDO e : executorDao.listByAgent(tenantId, agentId)) {
            result.add(toVO(e));
        }
        return result;
    }

    public List<ExecutorVO> listAll(long tenantId) {
        List<ExecutorVO> result = new ArrayList<>();
        for (ExecutorDO e : executorDao.listAll(tenantId)) {
            result.add(toVO(e));
        }
        return result;
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

    private ExecutorVO toVO(ExecutorDO e) {
        ExecutorVO vo = new ExecutorVO();
        vo.setId(e.getId());
        vo.setAgentId(e.getAgentId());
        vo.setAgentName(e.getAgentName());
        vo.setName(e.getName());
        vo.setStatus(registry.isOnline(e.getId()) ? "ONLINE" : "OFFLINE");
        vo.setClientKind(e.getClientKind());
        vo.setLastConnectIp(e.getLastConnectIp());
        vo.setLastHeartbeat(e.getLastHeartbeat());
        vo.setGmtCreate(e.getGmtCreate());
        return vo;
    }
}
