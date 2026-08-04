package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class AgentConversationService {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationService.class);

    private static final String API_MODE_SUFFIX =
            "\n\n重要:你在API模式下运行,不能使用AskUserQuestion等交互工具。直接用文字提问和回复。";
    private static final String CLARIFICATION_MODE_SUFFIX =
            "\n\n当前是工单需求澄清会话。遵循身份配置完成澄清；仅在用户明确确认最终方案后上传产物。"
                    + "用户要求重写时，先清理本次澄清上传的旧产物，再上传新版。";
    private static final int CONVERSATION_LOCK_TIMEOUT_SECONDS = 10;
    private static final long STALE_TURN_REDELIVERY_AFTER_MILLIS = 5 * 60 * 1000L;
    private static final int STALE_TURN_RECOVERY_BATCH_SIZE = 100;
    private static final int MAX_DISPATCH_ATTEMPTS = 3;
    private static final int MAX_TURN_ERROR_LENGTH = 1024;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String DIRECTION_IN = "IN";

    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private final ConversationTransport transport;
    private final ExecutorSelector executorSelector;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final ConversationChannelSinkRegistry sinkRegistry;
    private final ConversationRuntimePresence runtimePresence;
    private TransactionTemplate failureTransactionTemplate;

    @Autowired
    public AgentConversationService(AgentConversationDao convDao, AgentConversationTurnDao turnDao,
            ConversationTransport transport, ExecutorSelector executorSelector, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ConversationChannelSinkRegistry sinkRegistry,
            ConversationRuntimePresence runtimePresence) {
        this.convDao = convDao;
        this.turnDao = turnDao;
        this.transport = transport;
        this.executorSelector = executorSelector;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.sinkRegistry = sinkRegistry;
        this.runtimePresence = runtimePresence;
    }

    public AgentConversationService(AgentConversationDao convDao, AgentConversationTurnDao turnDao,
            ConversationTransport transport, ExecutorSelector executorSelector, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ConversationChannelSinkRegistry sinkRegistry) {
        this(convDao, turnDao, transport, executorSelector, agentDao, agentVersionDao,
                sinkRegistry, null);
    }

    @Autowired
    void configureFailureTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTransactionTemplate = template;
    }

    @Transactional
    public void submitTurn(Long tenantId, Long agentId, String channel, String channelConversationId,
            String content, String externalMsgId) {
        submitTurn(tenantId, agentId, channel, channelConversationId, content, externalMsgId, null);
    }

    @Transactional
    public void submitTurn(Long tenantId, Long agentId, String channel, String channelConversationId,
            String content, String externalMsgId, String sourceContext) {
        PendingDispatch dispatch = withConversationLock(
                logicalConversationLockName(tenantId, channel, channelConversationId, agentId),
                () -> submitTurnLocked(tenantId, agentId, channel, channelConversationId, content,
                        externalMsgId, sourceContext));
        sendAfterCommitOrNow(dispatch);
    }

    private PendingDispatch submitTurnLocked(Long tenantId, Long agentId, String channel,
            String channelConversationId, String content, String externalMsgId,
            String sourceContext) {
        if (externalMsgId != null && turnDao.findByExternalMsgId(tenantId, externalMsgId) != null) {
            return null;
        }
        AgentConversationDO conv = convDao.findByKey(tenantId, channel, channelConversationId, agentId);
        if (conv != null) {
            AgentConversationDO existingConv = conv;
            return withConversationLock(conversationLockName(tenantId, existingConv.getId()), () -> {
                if (turnDao.findProcessingInbound(tenantId, existingConv.getId()) != null) {
                    insertInboundTurn(tenantId, existingConv.getId(), content, externalMsgId,
                            sourceContext, STATUS_QUEUED);
                    return null;
                }
                return insertAndPrepareDispatch(tenantId, agentId, content, externalMsgId,
                        sourceContext, existingConv);
            });
        }
        return createConversationAndFirstDispatch(tenantId, agentId, channel, channelConversationId,
                content, externalMsgId, sourceContext);
    }

    private PendingDispatch createConversationAndFirstDispatch(Long tenantId, Long agentId,
            String channel, String channelConversationId, String content, String externalMsgId,
            String sourceContext) {
        Long executorId = executorSelector.select(agentId, null);
        if (executorId == null) {
            throw new IllegalStateException("no online executor for agent " + agentId);
        }
        AgentIdentitySnapshot identity = resolveIdentity(agentId, channel);
        AgentConversationDO conv = new AgentConversationDO();
        conv.setTenantId(tenantId);
        conv.setAgentId(agentId);
        conv.setAgentVersionId(identity.agentVersionId());
        conv.setChannel(channel);
        conv.setChannelConversationId(channelConversationId);
        conv.setExecutorId(executorId);
        conv.setStatus("ACTIVE");
        conv.setLastTurnAt(new Date());
        convDao.insert(conv);
        AgentConversationTurnDO turn = insertInboundTurn(tenantId, conv.getId(), content,
                externalMsgId, sourceContext, STATUS_PROCESSING);
        if (!recordProcessingDispatchAttempt(tenantId, conv.getId(), turn.getId())) {
            return null;
        }
        return new PendingDispatch(conv, turn.getId(), content, identity.systemPrompt(),
                currentRequestId(), 1);
    }

    private PendingDispatch insertAndPrepareDispatch(Long tenantId, Long agentId, String content,
            String externalMsgId, String sourceContext, AgentConversationDO conv) {
        Long preferredExecutorId = conv != null ? conv.getExecutorId() : null;
        Long executorId = executorSelector.select(agentId, preferredExecutorId);
        if (executorId == null) {
            throw new IllegalStateException("no online executor for agent " + agentId);
        }
        if (!executorId.equals(conv.getExecutorId())) {
            conv.setExecutorId(executorId);
            convDao.updateExecutor(tenantId, conv.getId(), executorId);
        }
        AgentConversationTurnDO turn = insertInboundTurn(tenantId, conv.getId(), content,
                externalMsgId, sourceContext, STATUS_PROCESSING);
        if (!recordProcessingDispatchAttempt(tenantId, conv.getId(), turn.getId())) {
            return null;
        }
        AgentIdentitySnapshot identity = refreshConversationIdentity(tenantId, conv);
        return new PendingDispatch(conv, turn.getId(), content, identity.systemPrompt(),
                currentRequestId(), 1);
    }

    private AgentConversationTurnDO insertInboundTurn(Long tenantId, Long conversationId,
            String content, String externalMsgId, String sourceContext, String status) {
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setTenantId(tenantId);
        turn.setConversationId(conversationId);
        turn.setDirection("IN");
        turn.setContent(content);
        turn.setExternalMsgId(externalMsgId);
        turn.setRequestId(currentRequestId());
        turn.setSourceContext(sourceContext);
        turn.setStatus(status);
        turnDao.insert(turn);
        return turn;
    }

    public boolean hasExternalMessage(Long tenantId, String externalMsgId) {
        return externalMsgId != null && turnDao.findByExternalMsgId(tenantId, externalMsgId) != null;
    }

    @Transactional
    public void recoverStaleTurnsForExecutor(Long tenantId, Long executorId) {
        recoverStaleTurnsForExecutor(tenantId, executorId, Collections.emptySet(), false,
                staleCutoff(), STALE_TURN_RECOVERY_BATCH_SIZE);
    }

    @Transactional
    public void recoverStaleTurnsForExecutor(Long tenantId, Long executorId,
            Collection<Long> activeConversationTurnIds) {
        recoverStaleTurnsForExecutor(tenantId, executorId, activeConversationTurnIds, true,
                staleCutoff(), STALE_TURN_RECOVERY_BATCH_SIZE);
    }

    @Transactional
    public void recoverInactiveTurnsForReplacedExecutor(Long tenantId, Long executorId,
            Collection<Long> activeConversationTurnIds) {
        Date cutoff = new Date();
        recoverStaleTurnsForExecutor(tenantId, executorId, activeConversationTurnIds, true,
                cutoff, STALE_TURN_RECOVERY_BATCH_SIZE);
    }

    @Transactional
    public void recoverStaleTurnsForExecutor(Long tenantId, Long executorId, Date cutoff, int limit) {
        recoverStaleTurnsForExecutor(tenantId, executorId, Collections.emptySet(), false,
                cutoff, limit);
    }

    void recoverStaleTurnsForExecutor(Long tenantId, Long executorId,
            Collection<Long> activeConversationTurnIds, boolean activityReported,
            Date cutoff, int limit) {
        if (tenantId == null || executorId == null) {
            return;
        }
        recoverStaleTurns(turnDao.listStaleProcessingInboundByExecutor(tenantId, executorId,
                cutoff, normalizeLimit(limit)), activityReported,
                normalizeActiveConversationTurnIds(activeConversationTurnIds), cutoff);
    }

    @Transactional
    public void recoverStaleTurns() {
        Date cutoff = staleCutoff();
        recoverStaleTurns(turnDao.listStaleProcessingInbound(cutoff,
                STALE_TURN_RECOVERY_BATCH_SIZE), false, Collections.emptySet(), cutoff);
    }

    @Transactional
    public void acknowledgeTurn(Long tenantId, Long executorId, Long conversationId, Long turnId,
            String status, String error, String replyMarkdown, String cliSessionId) {
        AgentConversationTurnDO inboundTurn = inboundTurn(tenantId, conversationId, turnId);
        String requestId = inboundTurn == null ? null : inboundTurn.getRequestId();
        String previousMdcRequestId = MDC.get("requestId");
        String previousContextRequestId = AutoWonderContext.get().getRequestId();
        if (requestId != null && !requestId.isEmpty()) {
            MDC.put("requestId", requestId);
            AutoWonderContext.get().setRequestId(requestId);
        }
        try {
            acknowledgeTurnWithCurrentRequestId(tenantId, executorId, conversationId, turnId,
                    status, error, replyMarkdown, cliSessionId, inboundTurn);
        } finally {
            restoreRequestId(previousMdcRequestId, previousContextRequestId);
        }
    }

    private void acknowledgeTurnWithCurrentRequestId(Long tenantId, Long executorId,
            Long conversationId, Long turnId, String status, String error, String replyMarkdown,
            String cliSessionId, AgentConversationTurnDO inboundTurn) {
        log.info("inbound CONVERSATION_TURN_ACK conversationId={} status={} executorId={}",
                conversationId, status, executorId);
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null) {
            return;
        }
        // 仅允许会话粘性 executor 应答,拒绝错路由的 executor 向群里投递任意内容。
        if (executorId != null && conv.getExecutorId() != null
                && !executorId.equals(conv.getExecutorId())) {
            log.warn("conversation ack rejected: executor {} != owner {} conversationId={}",
                    executorId, conv.getExecutorId(), conversationId);
            return;
        }
        if (!isActiveInboundTurn(inboundTurn, conversationId)) {
            log.warn("conversation ack ignored: invalid inbound turn conversationId={} turnId={}",
                    conversationId, turnId);
            return;
        }
        withConversationLockEffects(conversationLockName(tenantId, conversationId), () -> {
            if (turnId == null) {
                return null;
            }
            int finalized = turnDao.updateInboundStatusIfProcessing(tenantId, conversationId,
                    turnId, status, error);
            if (finalized != 1) {
                log.warn("conversation ack ignored: turn not active IN PROCESSING conversationId={} turnId={}",
                        conversationId, turnId);
                return null;
            }
            if (cliSessionId != null && !cliSessionId.isEmpty()) {
                convDao.updateCliSessionRef(tenantId, conversationId, cliSessionId);
                conv.setCliSessionRef(cliSessionId);
            }
            AgentConversationTurnDO out = new AgentConversationTurnDO();
            out.setTenantId(tenantId);
            out.setConversationId(conversationId);
            out.setDirection("OUT");
            out.setContent(replyMarkdown);
            out.setRequestId(currentRequestId());
            out.setStatus(status);
            out.setError(error);
            turnDao.insert(out);
            convDao.updateStatusAndLastTurn(tenantId, conversationId, "ACTIVE", new Date());
            PendingChannelReply channelReply = null;
            if ("SUCCESS".equalsIgnoreCase(status) && replyMarkdown != null) {
                String sourceExternalMsgId = inboundTurn == null ? null : inboundTurn.getExternalMsgId();
                channelReply = new PendingChannelReply(conv, replyMarkdown, sourceExternalMsgId,
                        currentRequestId());
            }
            return new PostCommitEffects(channelReply, nextQueuedDispatch(tenantId, conv));
        });
    }

    private boolean isActiveInboundTurn(AgentConversationTurnDO inboundTurn, Long conversationId) {
        return inboundTurn != null
                && conversationId != null
                && conversationId.equals(inboundTurn.getConversationId())
                && DIRECTION_IN.equals(inboundTurn.getDirection())
                && STATUS_PROCESSING.equals(inboundTurn.getStatus());
    }

    private void recoverStaleTurns(List<AgentConversationTurnDO> staleTurns,
            boolean activityReported, Set<Long> activeConversationTurnIds, Date cutoff) {
        if (staleTurns == null || staleTurns.isEmpty()) {
            return;
        }
        for (AgentConversationTurnDO stale : staleTurns) {
            if (stale == null || stale.getTenantId() == null
                    || stale.getConversationId() == null || stale.getId() == null) {
                continue;
            }
            try {
                recoverStaleTurn(stale, activityReported, activeConversationTurnIds, cutoff);
            } catch (RuntimeException e) {
                log.warn("conversation stale turn recovery failed conversationId={} turnId={}",
                        stale.getConversationId(), stale.getId(), e);
            }
        }
    }

    private void recoverStaleTurn(AgentConversationTurnDO stale, boolean activityReported,
            Set<Long> activeConversationTurnIds, Date cutoff) {
        withConversationLockEffects(conversationLockName(stale.getTenantId(),
                stale.getConversationId()), () -> {
            AgentConversationDO conv = convDao.findById(stale.getTenantId(), stale.getConversationId());
            if (conv == null || conv.getExecutorId() == null) {
                return null;
            }
            RuntimeActivity runtimeActivity = runtimeActivity(conv.getExecutorId(), activityReported,
                    activeConversationTurnIds);
            if (!runtimeActivity.reported()) {
                log.info("conversation stale turn recovery skipped: runtime activity unknown "
                                + "conversationId={} turnId={} executorId={}",
                        stale.getConversationId(), stale.getId(), conv.getExecutorId());
                return null;
            }
            if (runtimeActivity.activeTurnIds().contains(stale.getId())) {
                log.info("conversation stale turn recovery skipped: runtime still active "
                                + "conversationId={} turnId={} executorId={}",
                        stale.getConversationId(), stale.getId(), conv.getExecutorId());
                return null;
            }
            AgentConversationTurnDO current = turnDao.findByConversationTurn(stale.getTenantId(),
                    stale.getConversationId(), stale.getId());
            if (!isActiveInboundTurn(current, stale.getConversationId())) {
                return null;
            }
            if (current.getDispatchAttempt() != null
                    && current.getDispatchAttempt() >= MAX_DISPATCH_ATTEMPTS) {
                String error = "conversation runtime did not acknowledge after "
                        + current.getDispatchAttempt() + " delivery attempts";
                int finalized = turnDao.updateInboundStatusIfProcessing(stale.getTenantId(),
                        stale.getConversationId(), stale.getId(), "FAILED", error);
                if (finalized != 1) {
                    return null;
                }
                log.warn("conversation stale turn exhausted delivery attempts conversationId={} "
                                + "turnId={} attempts={}", stale.getConversationId(), stale.getId(),
                        current.getDispatchAttempt());
                return new PostCommitEffects(null,
                        nextQueuedDispatch(stale.getTenantId(), conv));
            }
            if (!claimStaleDispatchAttempt(stale.getTenantId(),
                    stale.getConversationId(), stale.getId(), cutoff)) {
                return null;
            }
            log.info("conversation stale turn redeliver conversationId={} turnId={} executorId={}",
                    stale.getConversationId(), stale.getId(), conv.getExecutorId());
            AgentIdentitySnapshot identity = refreshConversationIdentity(stale.getTenantId(), conv);
            AgentConversationTurnDO reloaded = turnDao.findByConversationTurn(
                    stale.getTenantId(), stale.getConversationId(), current.getId());
            int attempt = reloaded != null && reloaded.getDispatchAttempt() != null
                    ? reloaded.getDispatchAttempt() : 1;
            return new PostCommitEffects(null, new PendingDispatch(conv, current.getId(),
                    current.getContent(), identity.systemPrompt(), current.getRequestId(), attempt));
        });
    }

    private PendingDispatch nextQueuedDispatch(Long tenantId, AgentConversationDO conv) {
        if (turnDao.findProcessingInbound(tenantId, conv.getId()) != null) {
            return null;
        }
        AgentConversationTurnDO next = turnDao.findNextQueuedInbound(tenantId, conv.getId());
        if (next == null) {
            return null;
        }
        int rows = turnDao.updateStatusIfCurrent(tenantId, next.getId(), STATUS_QUEUED,
                STATUS_PROCESSING, null);
        if (rows != 1) {
            return null;
        }
        if (!recordProcessingDispatchAttempt(tenantId, conv.getId(), next.getId())) {
            return null;
        }
        AgentIdentitySnapshot identity = refreshConversationIdentity(tenantId, conv);
        return new PendingDispatch(conv, next.getId(), next.getContent(), identity.systemPrompt(),
                next.getRequestId(), 1);
    }

    private AgentConversationTurnDO inboundTurn(Long tenantId, Long conversationId, Long turnId) {
        if (tenantId == null || conversationId == null || turnId == null) {
            return null;
        }
        return turnDao.findByConversationTurn(tenantId, conversationId, turnId);
    }

    private String currentRequestId() {
        String requestId = AutoWonderContext.get().getRequestId();
        if (requestId == null || requestId.isEmpty()) {
            requestId = MDC.get("requestId");
        }
        return requestId;
    }

    private PendingDispatch withConversationLock(String lockName, Supplier<PendingDispatch> work) {
        withConversationLockEffects(lockName, () -> new PostCommitEffects(null, work.get()));
        return null;
    }

    private void withConversationLockEffects(String lockName, Supplier<PostCommitEffects> work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "actual transaction required for conversation lock");
        }
        Integer acquired = turnDao.acquireConversationLock(lockName, CONVERSATION_LOCK_TIMEOUT_SECONDS);
        if (!Integer.valueOf(1).equals(acquired)) {
            throw new IllegalStateException("conversation lock busy: " + lockName);
        }
        AtomicReference<PostCommitEffects> effectsRef = new AtomicReference<>();
        AtomicBoolean released = new AtomicBoolean(false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseConversationLockOnce(lockName, released);
                sendPostCommitEffects(effectsRef.get());
            }

            @Override
            public void afterCompletion(int status) {
                releaseConversationLockOnce(lockName, released);
            }
        });
        effectsRef.set(work.get());
    }

    private String conversationLockName(Long tenantId, Long conversationId) {
        return "agent-conversation:" + tenantId + ":" + conversationId;
    }

    private String logicalConversationLockName(Long tenantId, String channel,
            String channelConversationId, Long agentId) {
        String source = tenantId + "\u001f" + channel + "\u001f"
                + channelConversationId + "\u001f" + agentId;
        return "agent-conv-key:" + sha256Base64Url(source);
    }

    private String sha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void sendAfterCommitOrNow(PendingDispatch dispatch) {
        if (dispatch == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendPendingDispatchOrFail(dispatch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendPendingDispatchOrFail(dispatch);
            }
        });
    }

    private void sendPendingDispatchOrFail(PendingDispatch dispatch) {
        try {
            sendPendingDispatch(dispatch);
        } catch (RuntimeException e) {
            markDispatchFailed(dispatch, e);
        }
    }

    private void sendPendingDispatch(PendingDispatch dispatch) {
        log.info("conversation turn dispatch conversationId={} turnId={} executorId={} attempt={}",
                dispatch.conv().getId(), dispatch.turnId(), dispatch.conv().getExecutorId(),
                dispatch.dispatchAttempt());
        withRequestId(dispatch.requestId(), () ->
                transport.send(dispatch.conv(), dispatch.turnId(), dispatch.content(),
                        dispatch.systemPrompt(), dispatch.dispatchAttempt()));
    }

    private boolean recordProcessingDispatchAttempt(Long tenantId, Long conversationId, Long turnId) {
        int rows = turnDao.recordDispatchAttemptIfProcessing(tenantId, conversationId, turnId);
        if (rows != 1) {
            log.warn("conversation turn dispatch attempt skipped conversationId={} turnId={} rows={}",
                    conversationId, turnId, rows);
            return false;
        }
        return true;
    }

    private boolean claimStaleDispatchAttempt(Long tenantId, Long conversationId, Long turnId,
            Date cutoff) {
        int rows = turnDao.claimStaleDispatchAttemptIfProcessing(tenantId, conversationId,
                turnId, cutoff);
        if (rows != 1) {
            log.info("conversation stale turn claim skipped conversationId={} turnId={} rows={}",
                    conversationId, turnId, rows);
            return false;
        }
        return true;
    }

    private Date staleCutoff() {
        return new Date(System.currentTimeMillis() - STALE_TURN_REDELIVERY_AFTER_MILLIS);
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return STALE_TURN_RECOVERY_BATCH_SIZE;
        }
        return Math.min(limit, STALE_TURN_RECOVERY_BATCH_SIZE);
    }

    private Set<Long> normalizeActiveConversationTurnIds(Collection<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (Long id : raw) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    private RuntimeActivity runtimeActivity(Long executorId, boolean activityReported,
            Set<Long> activeConversationTurnIds) {
        if (activityReported) {
            return new RuntimeActivity(true, activeConversationTurnIds);
        }
        if (runtimePresence == null || executorId == null
                || !runtimePresence.isExecutorOnline(executorId)) {
            return new RuntimeActivity(false, Collections.emptySet());
        }
        if (!runtimePresence.hasConversationTurnActivityReport(executorId)) {
            return new RuntimeActivity(false, Collections.emptySet());
        }
        return new RuntimeActivity(true, runtimePresence.activeConversationTurnIds(executorId));
    }

    private void sendPostCommitEffects(PostCommitEffects effects) {
        if (effects == null) {
            return;
        }
        PendingChannelReply reply = effects.channelReply();
        if (reply != null) {
            try {
                sendPendingChannelReply(reply);
            } catch (RuntimeException e) {
                log.warn("conversation channel reply delivery failed conversationId={}",
                        reply.conv().getId(), e);
            }
        }
        PendingDispatch dispatch = effects.dispatch();
        if (dispatch != null) {
            sendPendingDispatchOrFail(dispatch);
        }
    }

    private void markDispatchFailed(PendingDispatch dispatch, RuntimeException failure) {
        String error = dispatchFailureSummary(failure);
        int finalized;
        if (failureTransactionTemplate == null) {
            finalized = turnDao.updateInboundStatusIfProcessing(dispatch.conv().getTenantId(),
                    dispatch.conv().getId(), dispatch.turnId(), "FAILED", error);
        } else {
            try {
                PostCommitEffects effects = failureTransactionTemplate.execute(status ->
                        finalizeDispatchFailure(dispatch, error));
                finalized = effects == null ? 0 : 1;
                sendPostCommitEffects(effects);
            } catch (RuntimeException finalizationFailure) {
                log.error("conversation dispatch failure finalization failed conversationId={} turnId={}",
                        dispatch.conv().getId(), dispatch.turnId(), finalizationFailure);
                finalized = turnDao.updateInboundStatusIfProcessing(dispatch.conv().getTenantId(),
                        dispatch.conv().getId(), dispatch.turnId(), "FAILED", error);
            }
        }
        log.error("conversation runtime dispatch failed conversationId={} turnId={} finalized={} error={}",
                dispatch.conv().getId(), dispatch.turnId(), finalized, error, failure);
    }

    private PostCommitEffects finalizeDispatchFailure(PendingDispatch dispatch, String error) {
        AgentConversationTurnDO inbound = inboundTurn(dispatch.conv().getTenantId(),
                dispatch.conv().getId(), dispatch.turnId());
        int finalized = turnDao.updateInboundStatusIfProcessing(dispatch.conv().getTenantId(),
                dispatch.conv().getId(), dispatch.turnId(), "FAILED", error);
        if (finalized != 1) {
            return null;
        }
        String reply = "回复失败：" + error;
        AgentConversationTurnDO out = new AgentConversationTurnDO();
        out.setTenantId(dispatch.conv().getTenantId());
        out.setConversationId(dispatch.conv().getId());
        out.setDirection("OUT");
        out.setContent(reply);
        out.setRequestId(dispatch.requestId());
        out.setStatus("FAILED");
        out.setError(error);
        turnDao.insert(out);
        convDao.updateStatusAndLastTurn(dispatch.conv().getTenantId(),
                dispatch.conv().getId(), "ACTIVE", new Date());
        PendingChannelReply channelReply = new PendingChannelReply(dispatch.conv(), reply,
                inbound == null ? null : inbound.getExternalMsgId(), dispatch.requestId());
        return new PostCommitEffects(channelReply,
                nextQueuedDispatch(dispatch.conv().getTenantId(), dispatch.conv()));
    }

    private String dispatchFailureSummary(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        String summary = "conversation dispatch failed: "
                + message.replace('\n', ' ').replace('\r', ' ').trim();
        return summary.length() <= MAX_TURN_ERROR_LENGTH
                ? summary : summary.substring(0, MAX_TURN_ERROR_LENGTH);
    }

    private void sendPendingChannelReply(PendingChannelReply reply) {
        withRequestId(reply.requestId(), () ->
                sinkRegistry.resolve(reply.conv().getChannel()).deliverReply(reply.conv(),
                        reply.replyMarkdown(), reply.sourceExternalMsgId()));
    }

    private void releaseConversationLockOnce(String lockName, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            releaseConversationLock(lockName);
        }
    }

    private void releaseConversationLock(String lockName) {
        try {
            Integer released = turnDao.releaseConversationLock(lockName);
            if (!Integer.valueOf(1).equals(released)) {
                log.warn("conversation lock release returned {} lockName={}", released, lockName);
            }
        } catch (RuntimeException e) {
            log.warn("conversation lock release failed lockName={}", lockName, e);
        }
    }

    private void withRequestId(String requestId, Runnable action) {
        String previousMdcRequestId = MDC.get("requestId");
        String previousContextRequestId = AutoWonderContext.get().getRequestId();
        if (requestId == null || requestId.isEmpty()) {
            MDC.remove("requestId");
            AutoWonderContext.get().setRequestId(null);
        } else {
            MDC.put("requestId", requestId);
            AutoWonderContext.get().setRequestId(requestId);
        }
        try {
            action.run();
        } finally {
            restoreRequestId(previousMdcRequestId, previousContextRequestId);
        }
    }

    private void restoreRequestId(String previousMdcRequestId, String previousContextRequestId) {
        if (previousMdcRequestId == null || previousMdcRequestId.isEmpty()) {
            MDC.remove("requestId");
        } else {
            MDC.put("requestId", previousMdcRequestId);
        }
        AutoWonderContext.get().setRequestId(previousContextRequestId);
    }

    private AgentIdentitySnapshot refreshConversationIdentity(Long tenantId,
            AgentConversationDO conv) {
        AgentIdentitySnapshot identity = resolveIdentity(conv.getAgentId(), conv.getChannel());
        if (!identity.agentVersionId().equals(conv.getAgentVersionId())) {
            if (convDao.updateAgentVersion(tenantId, conv.getId(), identity.agentVersionId()) != 1) {
                throw new IllegalStateException("conversation agent version refresh failed: "
                        + conv.getId());
            }
            conv.setAgentVersionId(identity.agentVersionId());
        }
        return identity;
    }

    private AgentIdentitySnapshot resolveIdentity(Long agentId, String channel) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getOnlineVersionId() == null) {
            throw new IllegalStateException("agent has no online version: " + agentId);
        }
        AgentVersionDO v = agentVersionDao.findById(agent.getOnlineVersionId());
        if (v == null) {
            throw new IllegalStateException("online agent version is missing: "
                    + agent.getOnlineVersionId());
        }
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "角色", v.getRoleName());
        appendIf(sb, "角色代号", v.getRoleCode());
        appendIf(sb, "业务背景", v.getBusinessBackground());
        appendIf(sb, "职责", v.getResponsibilities());
        appendIf(sb, "身份", v.getIdentityJson());
        if (sb.length() == 0) {
            throw new IllegalStateException("online agent version has no identity prompt: "
                    + agent.getOnlineVersionId());
        }
        sb.append(API_MODE_SUFFIX);
        if ("WORKITEM_CLARIFICATION".equals(channel)) {
            sb.append(CLARIFICATION_MODE_SUFFIX);
        }
        return new AgentIdentitySnapshot(agent.getOnlineVersionId(), sb.toString());
    }

    private void appendIf(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private record PendingDispatch(AgentConversationDO conv, Long turnId, String content,
            String systemPrompt, String requestId, Integer dispatchAttempt) {
    }

    private record AgentIdentitySnapshot(Long agentVersionId, String systemPrompt) {
    }

    private record PostCommitEffects(PendingChannelReply channelReply, PendingDispatch dispatch) {
    }

    private record PendingChannelReply(AgentConversationDO conv, String replyMarkdown,
            String sourceExternalMsgId, String requestId) {
    }

    private record RuntimeActivity(boolean reported, Set<Long> activeTurnIds) {
    }
}
