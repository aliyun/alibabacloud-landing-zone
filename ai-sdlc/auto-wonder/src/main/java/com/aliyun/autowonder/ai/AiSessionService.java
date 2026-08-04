package com.aliyun.autowonder.ai;

import com.aliyun.autowonder.ai.adapter.SceneAdapter;
import com.aliyun.autowonder.ai.adapter.SceneRegistry;
import com.aliyun.autowonder.ai.dto.*;
import com.aliyun.autowonder.aiusage.AiUsageService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class AiSessionService {

    private static final Logger log = LoggerFactory.getLogger(AiSessionService.class);

    private final AiSessionDao sessionDao;
    private final AiMessageDao messageDao;
    private final SceneRegistry sceneRegistry;
    private final RedisManager redisManager;
    private final AiUsageService aiUsageService;
    private final RepoDao repoDao;

    public AiSessionService(AiSessionDao sessionDao, AiMessageDao messageDao,
            SceneRegistry sceneRegistry, RedisManager redisManager,
            AiUsageService aiUsageService, RepoDao repoDao) {
        this.sessionDao = sessionDao;
        this.messageDao = messageDao;
        this.sceneRegistry = sceneRegistry;
        this.redisManager = redisManager;
        this.aiUsageService = aiUsageService;
        this.repoDao = repoDao;
    }

    @Transactional
    public Long create(CreateSessionRequest req, long tenantId, long userId) {
        SceneAdapter adapter = sceneRegistry.get(req.getScene());
        if (adapter == null) {
            throw new BizException(ErrorCode.AI_SCENE_NOT_SUPPORTED);
        }
        aiUsageService.checkQuota(tenantId);
        markRepoScanStartedIfNeeded(req, tenantId, userId);

        AiSessionDO session = new AiSessionDO();
        session.setTenantId(tenantId);
        session.setScene(req.getScene());
        session.setBizRefType(req.getBizRefType());
        session.setBizRefId(req.getBizRefId());
        session.setStatus(AiConstants.Status.QUEUED);
        session.setCreatorId(userId);
        sessionDao.insert(session);
        long sessionId = session.getId();

        if (shouldPersistInitialUserMessage(req)) {
            AiMessageDO msg = new AiMessageDO();
            msg.setTenantId(tenantId);
            msg.setSessionId(sessionId);
            msg.setSeq(1);
            msg.setRole(AiConstants.Role.USER);
            msg.setContent(req.getInput());
            messageDao.insert(msg);
        }

        String scene = req.getScene();
        String bizRefType = req.getBizRefType();
        Long bizRefId = req.getBizRefId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisManager.lpush("ai:queue:global", String.valueOf(sessionId));
                log.info("AI session queued sessionId={} scene={} bizRefType={} bizRefId={} tenantId={} userId={}",
                        sessionId, scene, bizRefType, bizRefId, tenantId, userId);
            }
        });
        return sessionId;
    }

    private boolean shouldPersistInitialUserMessage(CreateSessionRequest req) {
        return !AiConstants.Scene.REPO_SCAN.equals(req.getScene())
                && req.getInput() != null
                && !req.getInput().isBlank();
    }

    private void markRepoScanStartedIfNeeded(CreateSessionRequest req, long tenantId, long userId) {
        if (!AiConstants.Scene.REPO_SCAN.equals(req.getScene())
                || !"REPO".equals(req.getBizRefType())
                || req.getBizRefId() == null) {
            return;
        }
        RepoDO repo = repoDao.findById(req.getBizRefId());
        if (repo == null || repo.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        repoDao.updateScanStatus(repo.getId(), tenantId, "SCANNING",
                repo.getVersion(), userId);
    }

    public AiSessionVO get(long sessionId, long tenantId) {
        AiSessionDO session = sessionDao.findById(sessionId);
        if (session == null || session.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        AiSessionVO vo = new AiSessionVO();
        vo.setId(session.getId());
        vo.setScene(session.getScene());
        vo.setBizRefType(session.getBizRefType());
        vo.setBizRefId(session.getBizRefId());
        vo.setStatus(session.getStatus());
        vo.setResultJson(session.getResultJson());
        vo.setError(session.getError());
        vo.setGmtCreate(session.getGmtCreate());
        vo.setMessages(messageDao.listBySession(sessionId));
        return vo;
    }

    @Transactional
    public void appendMessage(long sessionId, AppendMessageRequest req, long tenantId) {
        AiSessionDO session = sessionDao.findById(sessionId);
        if (session == null || session.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        if (!AiConstants.Status.WAIT_USER.equals(session.getStatus())) {
            throw new BizException(ErrorCode.AI_SESSION_NOT_WAIT_USER);
        }

        int nextSeq = messageDao.maxSeq(sessionId) + 1;
        AiMessageDO msg = new AiMessageDO();
        msg.setTenantId(tenantId);
        msg.setSessionId(sessionId);
        msg.setSeq(nextSeq);
        msg.setRole(AiConstants.Role.USER);
        msg.setContent(req.getContent());
        messageDao.insert(msg);

        sessionDao.updateStatus(session.getId(), tenantId,
                AiConstants.Status.WAIT_USER, AiConstants.Status.QUEUED, session.getVersion());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisManager.lpush("ai:queue:global", String.valueOf(sessionId));
                log.info("ai session appendMessage sessionId={} re-queued", sessionId);
            }
        });
    }

    @Transactional
    public void confirm(long sessionId, ConfirmResultRequest req, long tenantId) {
        AiSessionDO session = sessionDao.findById(sessionId);
        if (session == null || session.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        if (!AiConstants.Status.WAIT_USER.equals(session.getStatus())) {
            throw new BizException(ErrorCode.AI_SESSION_NOT_WAIT_USER);
        }

        SceneAdapter adapter = sceneRegistry.get(session.getScene());
        if (adapter == null) {
            throw new BizException(ErrorCode.AI_SCENE_NOT_SUPPORTED);
        }

        String validationError = adapter.validateResult(req.getResultJson());
        if (validationError != null) {
            throw new BizException(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, validationError);
        }

        adapter.persistConfirmedResult(session, req.getResultJson());
        sessionDao.updateStatus(session.getId(), tenantId,
                AiConstants.Status.WAIT_USER, AiConstants.Status.COMPLETED, session.getVersion());
        log.info("ai session confirmed sessionId={}", sessionId);
    }

    public void cancel(long sessionId, long tenantId) {
        AiSessionDO session = sessionDao.findById(sessionId);
        if (session == null || session.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        String currentStatus = session.getStatus();
        if (AiConstants.Status.COMPLETED.equals(currentStatus)
                || AiConstants.Status.CANCELED.equals(currentStatus)
                || AiConstants.Status.FAILED.equals(currentStatus)
                || AiConstants.Status.RUNNING.equals(currentStatus)) {
            return; // idempotent for terminal states; no-op for RUNNING (let it finish or be compensated)
        }
        sessionDao.updateStatus(session.getId(), tenantId,
                currentStatus, AiConstants.Status.CANCELED, session.getVersion());
        log.info("ai session canceled sessionId={}", sessionId);
    }
}
