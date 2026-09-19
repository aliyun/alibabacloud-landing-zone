package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.integration.dingtalk.DingTalkSourceContext;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class AgentConversationService {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationService.class);

    private static final String API_MODE_SUFFIX =
            "\n\n重要:你在API模式下运行,不能使用AskUserQuestion等交互工具。直接用文字提问和回复。";
    private static final int CONVERSATION_LOCK_TIMEOUT_SECONDS = 10;
    private static final long STALE_TURN_REDELIVERY_AFTER_MILLIS = 5 * 60 * 1000L;
    private static final int STALE_TURN_RECOVERY_BATCH_SIZE = 100;
    private static final int MAX_DISPATCH_ATTEMPTS = 3;
    private static final int MAX_TURN_ERROR_LENGTH = 1024;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String DIRECTION_IN = "IN";
    private static final String PROTOCOL_FEATURE_TURN_CANCEL = "CONVERSATION_TURN_CANCEL";
    private static final String PROTOCOL_FEATURE_ACP_INTERACTION = "CONVERSATION_ACP_INTERACTION_V1";
    private static final long DEFAULT_CANCEL_ACK_TIMEOUT_SECONDS = 30;
    private static final String CANCELED_FALLBACK_CONTENT = "响应已终止";

    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private final ConversationTransport transport;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final ConversationChannelSinkRegistry sinkRegistry;
    private final ConversationRuntimePresence runtimePresence;
    private final ConversationExecutorRouter executorRouter;
    private TransactionTemplate failureTransactionTemplate;
    private ConversationBrowserEventPublisher browserEventPublisher;
    private ConversationElicitationService conversationElicitationService;
    private final Map<Long, ScheduledFuture<?>> pendingCancels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cancelTimeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "conversation-cancel-timeout");
                thread.setDaemon(true);
                return thread;
            });
    private long cancelAckTimeoutSeconds = DEFAULT_CANCEL_ACK_TIMEOUT_SECONDS;

    @Autowired
    public AgentConversationService(AgentConversationDao convDao, AgentConversationTurnDao turnDao,
            ConversationTransport transport, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ConversationChannelSinkRegistry sinkRegistry,
            ConversationRuntimePresence runtimePresence,
            ConversationExecutorRouter executorRouter) {
        this.convDao = convDao;
        this.turnDao = turnDao;
        this.transport = transport;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.sinkRegistry = sinkRegistry;
        this.runtimePresence = runtimePresence;
        this.executorRouter = executorRouter;
    }

    public AgentConversationService(AgentConversationDao convDao, AgentConversationTurnDao turnDao,
            ConversationTransport transport, AgentDao agentDao,
            AgentVersionDao agentVersionDao, ConversationChannelSinkRegistry sinkRegistry,
            ConversationExecutorRouter executorRouter) {
        this(convDao, turnDao, transport, agentDao, agentVersionDao,
                sinkRegistry, null, executorRouter);
    }

    @Autowired
    void configureFailureTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTransactionTemplate = template;
    }

    @Autowired(required = false)
    void setBrowserEventPublisher(ConversationBrowserEventPublisher browserEventPublisher) {
        this.browserEventPublisher = browserEventPublisher;
    }

    @Autowired(required = false)
    void setConversationElicitationService(
            ConversationElicitationService conversationElicitationService) {
        this.conversationElicitationService = conversationElicitationService;
    }

    void setCancelAckTimeoutSeconds(long cancelAckTimeoutSeconds) {
        this.cancelAckTimeoutSeconds = cancelAckTimeoutSeconds;
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
        ExecutorSelection selection = selectExecutor(tenantId, agentId, null);
        Long executorId = selection.executorId();
        if (executorId == null) {
            throw new IllegalStateException("no online executor for agent " + agentId);
        }
        AgentIdentitySnapshot identity = resolveIdentity(selection.agentVersionId(), channel,
                executorId);
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
        return new PendingDispatch(conv, turn.getId(), content, sourceContext, identity.systemPrompt(),
                currentRequestId(), 1);
    }

    private PendingDispatch insertAndPrepareDispatch(Long tenantId, Long agentId, String content,
            String externalMsgId, String sourceContext, AgentConversationDO conv) {
        ExecutorSelection selection = selectAndBindExecutor(tenantId, conv);
        AgentConversationTurnDO turn = insertInboundTurn(tenantId, conv.getId(), content,
                externalMsgId, sourceContext, STATUS_PROCESSING);
        if (!recordProcessingDispatchAttempt(tenantId, conv.getId(), turn.getId())) {
            return null;
        }
        AgentIdentitySnapshot identity = refreshConversationIdentity(tenantId, conv,
                selection.agentVersionId());
        return new PendingDispatch(conv, turn.getId(), content, sourceContext, identity.systemPrompt(),
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
            out.setContent(STATUS_CANCELED.equalsIgnoreCase(status)
                    ? canceledReplyContent(replyMarkdown)
                    : outboundReplyContent(replyMarkdown, error));
            out.setRequestId(currentRequestId());
            out.setStatus(status);
            out.setError(error);
            turnDao.insert(out);
            convDao.updateStatusAndLastTurn(tenantId, conversationId, "ACTIVE", new Date());
            if (STATUS_CANCELED.equalsIgnoreCase(status)) {
                cancelPendingCancelTimeout(turnId);
                publishCanceledStatusEvent(tenantId, conversationId, turnId);
            } else {
                publishAckTerminalStatusEvent(tenantId, conversationId, turnId, status);
            }
            PendingChannelReply channelReply = null;
            if ("SUCCESS".equalsIgnoreCase(status) && replyMarkdown != null) {
                String sourceExternalMsgId = inboundTurn == null ? null : inboundTurn.getExternalMsgId();
                channelReply = new PendingChannelReply(conv, replyMarkdown, sourceExternalMsgId,
                        currentRequestId());
            }
            return new PostCommitEffects(channelReply, nextQueuedDispatch(tenantId, conv));
        });
    }

    private String outboundReplyContent(String replyMarkdown, String error) {
        if (replyMarkdown != null && !replyMarkdown.isBlank()) {
            return replyMarkdown;
        }
        // 空内容 OUT turn 在澄清界面渲染为空泡，会造成问答断裂；落库可见兜底文案。
        if (error != null && !error.isBlank()) {
            return "回复失败：" + error;
        }
        return "（数字人未返回内容）";
    }

    private String canceledReplyContent(String replyMarkdown) {
        if (replyMarkdown != null && !replyMarkdown.isBlank()) {
            return replyMarkdown;
        }
        return CANCELED_FALLBACK_CONTENT;
    }

    private void publishCanceledStatusEvent(Long tenantId, Long conversationId, Long turnId) {
        if (browserEventPublisher == null) {
            return;
        }
        try {
            browserEventPublisher.publishStatusEvent(tenantId, conversationId, turnId, "canceled");
        } catch (RuntimeException e) {
            log.warn("conversation canceled status event publish failed conversationId={} turnId={}",
                    conversationId, turnId, e);
        }
    }

    /** ACK 正常终结（非取消）时直推终态事件；否则轮次结束后浏览器端没有任何
     *  触发重新拉取会话的事件，澄清界面会一直停留在"回复中"状态。
     *
     *  <p>走 browserEventPublisher 而不是 ConversationTurnEventService：后者上的
     *  同名方法只是转发到这里，为打断 Spring 循环依赖已随组件抽出而删除，绕回去
     *  会把环装回来。 */
    private void publishAckTerminalStatusEvent(Long tenantId, Long conversationId, Long turnId,
            String status) {
        if (browserEventPublisher == null) {
            return;
        }
        String terminalStatus;
        if ("SUCCESS".equalsIgnoreCase(status)) {
            terminalStatus = "completed";
        } else if ("FAILED".equalsIgnoreCase(status)) {
            terminalStatus = "failed";
        } else {
            return;
        }
        try {
            browserEventPublisher.publishStatusEvent(tenantId, conversationId, turnId,
                    terminalStatus);
        } catch (RuntimeException e) {
            log.warn("conversation ack terminal status event publish failed conversationId={} turnId={}",
                    conversationId, turnId, e);
        }
    }

    @Transactional
    public void requestTurnCancel(Long tenantId, Long conversationId, Long turnId) {
        if (tenantId == null || conversationId == null || turnId == null) {
            throw new IllegalArgumentException("tenantId, conversationId and turnId are required");
        }
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null) {
            throw new IllegalArgumentException("conversation not found");
        }
        AgentConversationTurnDO turn = inboundTurn(tenantId, conversationId, turnId);
        if (turn == null || !DIRECTION_IN.equals(turn.getDirection())) {
            throw new BizException(ErrorCode.NOT_FOUND, "conversation turn not found: " + turnId);
        }
        if (!STATUS_PROCESSING.equals(turn.getStatus()) && !STATUS_QUEUED.equals(turn.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "conversation turn is not cancelable: " + turn.getStatus());
        }
        if (STATUS_QUEUED.equals(turn.getStatus())) {
            // 排队中的轮次尚未下发到 Runtime，直接在服务端终结。
            withConversationLockEffects(conversationLockName(tenantId, conversationId),
                    () -> finalizeTurnCancelLocked(tenantId, conv, turnId, null));
            publishCanceledStatusEvent(tenantId, conversationId, turnId);
            return;
        }
        if (conv.getExecutorId() == null || runtimePresence == null
                || !runtimePresence.isExecutorOnline(conv.getExecutorId())
                || !runtimePresence.supportsProtocolFeature(conv.getExecutorId(),
                        PROTOCOL_FEATURE_TURN_CANCEL)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "runtime does not support conversation turn cancel");
        }
        cancelPendingElicitations(tenantId, conversationId, turnId);
        transport.sendCancel(conv, turnId);
        scheduleCancelAckTimeout(conv, turnId);
    }

    /**
     * 取消 / 重投前先把该轮所有挂起卡片 cancel 掉 —— ACP 要求挂起请求必须有终态，
     * 否则执行器侧的 JSON-RPC 请求会一直悬着，用户也会对着一个投不进去的表单填。
     *
     * <p>状态转移留在调用方事务内：它可回滚，且必须与轮次状态原子生效。只有不可
     * 回滚的部分（执行器 cancel 帧、浏览器推送）推迟到提交之后 —— 若帧先出门而
     * 事务回滚，卡片回到 PENDING 但执行器侧 requestId 已取消，用户之后的回答会
     * 被服务端接受却永远投不进去。
     *
     * <p>联动失败只记 warn：用户点了停止就必须停下来，不能被卡片状态拖住。
     */
    private void cancelPendingElicitations(Long tenantId, Long conversationId, Long turnId) {
        if (conversationElicitationService == null) {
            return;
        }
        List<AgentConversationElicitationDO> settled;
        try {
            settled = conversationElicitationService.settlePendingForTurn(tenantId, conversationId,
                    turnId);
        } catch (RuntimeException e) {
            log.warn("conversation pending elicitation cancel failed conversationId={} turnId={}: {}",
                    conversationId, turnId, e.getMessage());
            return;
        }
        if (settled.isEmpty()) {
            return;
        }
        runAfterCommit(() -> conversationElicitationService.notifyCanceled(settled));
    }

    /**
     * 事务提交后执行 {@code action}；没有事务时立即执行。
     *
     * <p>只能放不可回滚的副作用：此处的执行上下文里数据库写入会被静默丢弃
     * （MyBatis 的 SqlSession 已在 beforeCompletion 关闭）。
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void scheduleCancelAckTimeout(AgentConversationDO conv, Long turnId) {
        ScheduledFuture<?> future = cancelTimeoutScheduler.schedule(
                () -> handleCancelAckTimeout(conv, turnId),
                cancelAckTimeoutSeconds, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = pendingCancels.put(turnId, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelPendingCancelTimeout(Long turnId) {
        ScheduledFuture<?> future = pendingCancels.remove(turnId);
        if (future != null) {
            future.cancel(false);
        }
    }

    // 包级可见，便于单测直接触发超时路径。
    void handleCancelAckTimeout(AgentConversationDO conv, Long turnId) {
        ScheduledFuture<?> current = pendingCancels.remove(turnId);
        if (current == null) {
            // Runtime 已应答取消（ack 路径会先移除定时器），无需兜底。
            return;
        }
        log.warn("conversation cancel ack timeout conversationId={} turnId={}",
                conv.getId(), turnId);
        try {
            Long tenantId = conv.getTenantId();
            Long conversationId = conv.getId();
            if (failureTransactionTemplate != null) {
                failureTransactionTemplate.executeWithoutResult(status ->
                        withConversationLockEffects(conversationLockName(tenantId, conversationId),
                                () -> finalizeTurnCancelLocked(tenantId, conv, turnId, null)));
            } else {
                withConversationLockEffects(conversationLockName(tenantId, conversationId),
                        () -> finalizeTurnCancelLocked(tenantId, conv, turnId, null));
            }
        } catch (RuntimeException e) {
            log.error("conversation cancel ack timeout finalization failed conversationId={} turnId={}",
                    conv.getId(), turnId, e);
            return;
        }
        publishCanceledStatusEvent(conv.getTenantId(), conv.getId(), turnId);
    }

    private PostCommitEffects finalizeTurnCancelLocked(Long tenantId, AgentConversationDO conv,
            Long turnId, String partialContent) {
        AgentConversationTurnDO turn = inboundTurn(tenantId, conv.getId(), turnId);
        if (turn == null || !DIRECTION_IN.equals(turn.getDirection())) {
            return null;
        }
        int finalized;
        if (STATUS_PROCESSING.equals(turn.getStatus())) {
            finalized = turnDao.updateInboundStatusIfProcessing(tenantId, conv.getId(), turnId,
                    STATUS_CANCELED, null);
        } else if (STATUS_QUEUED.equals(turn.getStatus())) {
            finalized = turnDao.updateStatusIfCurrent(tenantId, turnId, STATUS_QUEUED,
                    STATUS_CANCELED, null);
        } else {
            return null;
        }
        if (finalized != 1) {
            return null;
        }
        AgentConversationTurnDO out = new AgentConversationTurnDO();
        out.setTenantId(tenantId);
        out.setConversationId(conv.getId());
        out.setDirection("OUT");
        out.setContent(canceledReplyContent(partialContent));
        out.setRequestId(currentRequestId());
        out.setStatus(STATUS_CANCELED);
        turnDao.insert(out);
        convDao.updateStatusAndLastTurn(tenantId, conv.getId(), "ACTIVE", new Date());
        return new PostCommitEffects(null, nextQueuedDispatch(tenantId, conv));
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
                // 执行器已重启 / 掉线，它侧的挂起 requestId 不存在了：卡片必须立刻
                // 失效，否则最长 30 分钟内用户的回答会落库成功却进黑洞（spec §4.6）。
                String error = "conversation runtime did not acknowledge after "
                        + current.getDispatchAttempt() + " delivery attempts";
                int finalized = turnDao.updateInboundStatusIfProcessing(stale.getTenantId(),
                        stale.getConversationId(), stale.getId(), "FAILED", error);
                if (finalized != 1) {
                    return null;
                }
                cancelPendingElicitations(stale.getTenantId(), stale.getConversationId(),
                        stale.getId());
                log.warn("conversation stale turn exhausted delivery attempts conversationId={} "
                                + "turnId={} attempts={}", stale.getConversationId(), stale.getId(),
                        current.getDispatchAttempt());
                return new PostCommitEffects(null,
                        nextQueuedDispatch(stale.getTenantId(), conv));
            }
            ExecutorSelection selection;
            try {
                selection = trySelectExecutor(stale.getTenantId(), conv.getAgentId(),
                        conv.getExecutorId());
            } catch (ExecutorProtocolCompatibilityException compatibilityFailure) {
                deferSelectionCompatibilityFailure(stale.getTenantId(), conv, current,
                        STATUS_PROCESSING, compatibilityFailure);
                return null;
            }
            if (selection == null) {
                return null;
            }
            if (!claimStaleDispatchAttempt(stale.getTenantId(),
                    stale.getConversationId(), stale.getId(), cutoff)) {
                return null;
            }
            bindSelectedExecutor(stale.getTenantId(), conv, selection);
            // 重投等于换了一个执行器进程，它侧的挂起 requestId 已经没有了。
            cancelPendingElicitations(stale.getTenantId(), stale.getConversationId(),
                    stale.getId());
            log.info("conversation stale turn redeliver conversationId={} turnId={} executorId={}",
                    stale.getConversationId(), stale.getId(), conv.getExecutorId());
            AgentIdentitySnapshot identity = refreshConversationIdentity(stale.getTenantId(), conv,
                    selection.agentVersionId());
            AgentConversationTurnDO reloaded = turnDao.findByConversationTurn(
                    stale.getTenantId(), stale.getConversationId(), current.getId());
            int attempt = reloaded != null && reloaded.getDispatchAttempt() != null
                    ? reloaded.getDispatchAttempt() : 1;
            return new PostCommitEffects(null, new PendingDispatch(conv, current.getId(),
                    current.getContent(), current.getSourceContext(), identity.systemPrompt(),
                    current.getRequestId(), attempt));
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
        ExecutorSelection selection;
        try {
            selection = trySelectExecutor(tenantId, conv.getAgentId(), conv.getExecutorId());
        } catch (ExecutorProtocolCompatibilityException compatibilityFailure) {
            deferSelectionCompatibilityFailure(tenantId, conv, next, STATUS_QUEUED,
                    compatibilityFailure);
            return null;
        }
        if (selection == null) {
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
        bindSelectedExecutor(tenantId, conv, selection);
        AgentIdentitySnapshot identity = refreshConversationIdentity(tenantId, conv,
                selection.agentVersionId());
        return new PendingDispatch(conv, next.getId(), next.getContent(), next.getSourceContext(),
                identity.systemPrompt(), next.getRequestId(), 1);
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
                transport.send(dispatch.conv(), dispatch.turnId(), contentForRuntime(dispatch),
                        dispatch.systemPrompt(), dispatch.dispatchAttempt()));
    }

    private String contentForRuntime(PendingDispatch dispatch) {
        if (dispatch == null || dispatch.conv() == null) {
            return null;
        }
        if (!"DINGTALK".equalsIgnoreCase(dispatch.conv().getChannel())) {
            return dispatch.content();
        }
        String prefix = dingtalkSenderContextPrompt(dispatch.sourceContext());
        if (prefix == null || prefix.isBlank()) {
            return dispatch.content();
        }
        return prefix + "\nUser message:\n" + (dispatch.content() == null ? "" : dispatch.content());
    }

    private String dingtalkSenderContextPrompt(String sourceContext) {
        DingTalkSourceContext context;
        try {
            context = DingTalkSourceContext.parse(sourceContext);
        } catch (Exception e) {
            log.warn("ignore invalid DingTalk sourceContext for conversation prompt: {}", e.getMessage());
            return "";
        }
        if (context == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("DingTalk message context:\n");
        appendIf(sb, "- Sender nickname", context.getSenderNick());
        appendIf(sb, "- Sender staffId", context.getSenderStaffId());
        appendIf(sb, "- Sender dingtalk senderId", context.getSenderId());
        appendIf(sb, "- Conversation title", context.getConversationTitle());
        appendIf(sb, "- Conversation type", context.getConversationType());
        if (sb.toString().equals("DingTalk message context:\n")) {
            return "";
        }
        sb.append("\nInstruction: The sender above is the human who sent the current DingTalk message. ")
                .append("If the user asks \"who am I\" or refers to \"me\", answer using this DingTalk sender context. ")
                .append("Do not confuse this sender with any AutoWonder MCP token or tool identity.\n");
        return sb.toString();
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
            AgentConversationDO conv, Long agentVersionId) {
        AgentIdentitySnapshot identity = resolveIdentity(agentVersionId, conv.getChannel(),
                conv.getExecutorId());
        if (!identity.agentVersionId().equals(conv.getAgentVersionId())) {
            if (convDao.updateAgentVersion(tenantId, conv.getId(), identity.agentVersionId()) != 1) {
                throw new IllegalStateException("conversation agent version refresh failed: "
                        + conv.getId());
            }
            conv.setAgentVersionId(identity.agentVersionId());
        }
        return identity;
    }

    private ExecutorSelection selectExecutor(Long tenantId, Long agentId,
            Long preferredExecutorId) {
        ExecutorSelection selection = trySelectExecutor(tenantId, agentId, preferredExecutorId);
        if (selection == null) {
            throw new IllegalStateException("no online executor for agent " + agentId);
        }
        return selection;
    }

    private ExecutorSelection trySelectExecutor(Long tenantId, Long agentId,
            Long preferredExecutorId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getOnlineVersionId() == null) {
            throw new IllegalStateException("agent has no online version: " + agentId);
        }
        Long executorId = executorRouter.select(tenantId, agentId, agent.getOnlineVersionId(),
                preferredExecutorId);
        return executorId == null ? null
                : new ExecutorSelection(executorId, agent.getOnlineVersionId());
    }

    private ExecutorSelection selectAndBindExecutor(Long tenantId, AgentConversationDO conv) {
        ExecutorSelection selection = selectExecutor(tenantId, conv.getAgentId(),
                conv.getExecutorId());
        bindSelectedExecutor(tenantId, conv, selection);
        return selection;
    }

    private void bindSelectedExecutor(Long tenantId, AgentConversationDO conv,
            ExecutorSelection selection) {
        Long executorId = selection.executorId();
        if (!executorId.equals(conv.getExecutorId())) {
            conv.setExecutorId(executorId);
            convDao.updateExecutor(tenantId, conv.getId(), executorId);
        }
    }

    private void deferSelectionCompatibilityFailure(Long tenantId, AgentConversationDO conv,
            AgentConversationTurnDO turn, String expectedStatus,
            ExecutorProtocolCompatibilityException failure) {
        String error = "conversation executor compatibility failed: " + failure.getMessage();
        runAfterCommit(() -> {
            Runnable finalizeTarget = () -> {
                if (STATUS_QUEUED.equals(expectedStatus)) {
                    turnDao.updateStatusIfCurrent(tenantId, turn.getId(), STATUS_QUEUED,
                            "FAILED", error);
                } else {
                    turnDao.updateInboundStatusIfProcessing(tenantId, conv.getId(), turn.getId(),
                            "FAILED", error);
                }
            };
            if (failureTransactionTemplate != null) {
                failureTransactionTemplate.executeWithoutResult(status -> finalizeTarget.run());
            } else {
                finalizeTarget.run();
            }
        });
    }

    private AgentIdentitySnapshot resolveIdentity(Long agentVersionId, String channel,
            Long executorId) {
        AgentVersionDO v = agentVersionDao.findById(agentVersionId);
        if (v == null) {
            throw new IllegalStateException("online agent version is missing: "
                    + agentVersionId);
        }
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "角色", v.getRoleName());
        appendIf(sb, "角色代号", v.getRoleCode());
        appendIf(sb, "业务背景", v.getBusinessBackground());
        appendIf(sb, "职责", v.getResponsibilities());
        appendIf(sb, "身份", v.getIdentityJson());
        if (sb.length() == 0) {
            throw new IllegalStateException("online agent version has no identity prompt: "
                    + agentVersionId);
        }
        // Agent 具备结构化提问能力时不再禁止交互工具：它的提问会经 elicitation/create
        // 变成前端卡片，用户可以真的回答。老版本执行器没有这条通路，禁令必须保留，
        // 否则 Agent 的提问会掉进黑洞 —— 用户看不到问题，Agent 也等不到回答。
        ConversationMode mode = ConversationMode.of(channel);
        if (mode.oneWay() || !supportsAcpInteraction(executorId)) {
            sb.append(API_MODE_SUFFIX);
        }
        sb.append(mode.promptSuffix());
        return new AgentIdentitySnapshot(agentVersionId, sb.toString());
    }

    private boolean supportsAcpInteraction(Long executorId) {
        return executorId != null && runtimePresence != null
                && runtimePresence.supportsProtocolFeature(executorId,
                        PROTOCOL_FEATURE_ACP_INTERACTION);
    }

    private void appendIf(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private record PendingDispatch(AgentConversationDO conv, Long turnId, String content,
            String sourceContext, String systemPrompt, String requestId, Integer dispatchAttempt) {
    }

    private record AgentIdentitySnapshot(Long agentVersionId, String systemPrompt) {
    }

    private record ExecutorSelection(Long executorId, Long agentVersionId) {
    }

    private record PostCommitEffects(PendingChannelReply channelReply, PendingDispatch dispatch) {
    }

    private record PendingChannelReply(AgentConversationDO conv, String replyMarkdown,
            String sourceExternalMsgId, String requestId) {
    }

    private record RuntimeActivity(boolean reported, Set<Long> activeTurnIds) {
    }
}
