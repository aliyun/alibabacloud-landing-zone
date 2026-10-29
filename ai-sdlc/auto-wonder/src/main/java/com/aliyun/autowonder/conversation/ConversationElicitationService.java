package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.json.Feature;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * ACP 问答卡片的生命周期：受理执行器上行事件、承接用户回答、轮次取消联动、
 * 30 分钟兜底过期。
 *
 * <p>回答、取消、过期三条路径会竞争同一次 PENDING → 终态的转移，全部经
 * {@link AgentConversationElicitationDao#settleIfPending} 收口，只有抢到转移
 * 的那个才产生后续副作用。系统过期只停止执行，不发送合成回答。
 *
 * <p>日志<b>严禁</b>打印 {@code answerJson} 与 {@code schemaJson}：两者承载
 * 用户填写的业务内容与 Agent 生成的问题正文，可能含敏感信息。
 */
@Service
public class ConversationElicitationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationElicitationService.class);

    /** 卡片类事件的类型前缀，与 ConversationTurnEventService 的分派条件对应。 */
    public static final String EVENT_TYPE_PREFIX = "acp_elicitation";
    private static final String EVENT_OPENED = "acp_elicitation";
    private static final String EVENT_RESOLVED = "acp_elicitation_resolved";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ANSWERED = "ANSWERED";
    private static final String STATUS_DECLINED = "DECLINED";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private static final String ACTION_ACCEPT = "accept";
    private static final String ACTION_DECLINE = "decline";
    private static final String ACTION_CANCEL = "cancel";

    private static final String DEFAULT_MODE = "form";
    /** 与 agent_conversation_elicitation.message 的 VARCHAR(1024) 对齐。 */
    private static final int MAX_MESSAGE_LENGTH = 1024;

    private static final String TURN_STATUS_PROCESSING = "PROCESSING";

    private final AgentConversationElicitationDao elicitationDao;
    private final AgentConversationDao convDao;
    private final ConversationTransport transport;
    private final ConversationRuntimePresence runtimePresence;
    private final AgentConversationTurnDao turnDao;
    /** 用 setter 注入：ConversationTurnEventService 也持有本服务，构造器注入会成环。 */
    private ConversationBrowserEventPublisher browserEventPublisher;
    private ConversationAnswerDeliveryService answerDeliveryService;
    private long expiryCursor;
    @Autowired(required = false)
    public void setAnswerDeliveryService(ConversationAnswerDeliveryService service) { this.answerDeliveryService=service; }

    public ConversationElicitationService(AgentConversationElicitationDao elicitationDao,
            AgentConversationDao convDao, ConversationTransport transport,
            ConversationRuntimePresence runtimePresence, AgentConversationTurnDao turnDao) {
        this.elicitationDao = elicitationDao;
        this.convDao = convDao;
        this.transport = transport;
        this.runtimePresence = runtimePresence;
        this.turnDao = turnDao;
    }

    @Autowired(required = false)
    public void setBrowserEventPublisher(
            ConversationBrowserEventPublisher browserEventPublisher) {
        this.browserEventPublisher = browserEventPublisher;
    }

    /**
     * 受理执行器上行的 {@code acp_elicitation*} 事件。
     *
     * <p>这里跑在事件热路径上：一条畸形事件不该中断整条事件流，因此所有解析
     * 与状态问题只记 warn 不抛。
     */
    @org.springframework.transaction.annotation.Transactional
    public String onEvent(long tenantId, long conversationId, long turnId, String eventType,
            String payload) {
        String requestId;
        JSONObject data;
        try {
            // 一次提问的多个题目整体存在 schema_json.requestedSchema.properties 这个 JSON
            // 对象里，表上没有 order/seq 列，题目顺序只存在于键序中。fastjson 默认用
            // HashMap 承载 JSONObject，落库前的 toJSONString 会把键序重排成哈希桶序，
            // 用户看到的题目就被打乱了，因此这里必须保序解析。
            JSONObject root = payload == null ? null
                    : JSON.parseObject(payload, Feature.OrderedField);
            data = root == null ? null : root.getJSONObject("data");
            requestId = data == null ? null : data.getString("requestId");
        } catch (RuntimeException e) {
            log.warn("acp elicitation event payload unparsable conversationId={} turnId={} type={}",
                    conversationId, turnId, eventType);
            return null;
        }
        if (requestId == null || requestId.isBlank()) {
            log.warn("acp elicitation event without requestId conversationId={} turnId={} type={}",
                    conversationId, turnId, eventType);
            return null;
        }
        if (EVENT_OPENED.equals(eventType)) {
            return openPending(tenantId, conversationId, turnId, requestId, data);
        } else if (EVENT_RESOLVED.equals(eventType)) {
            settleFromRuntime(tenantId, conversationId, requestId, data.getString("action"));
        } else {
            log.info("acp elicitation event type not handled conversationId={} type={}",
                    conversationId, eventType);
        }
        return null;
    }

    private String openPending(long tenantId, long conversationId, long turnId, String requestId,
            JSONObject data) {
        AgentConversationElicitationDO record = new AgentConversationElicitationDO();
        record.setTenantId(tenantId);
        record.setConversationId(conversationId);
        record.setTurnId(turnId);
        record.setRequestId(requestId);
        String mode = data.getString("mode");
        record.setMode(mode == null || mode.isBlank() ? DEFAULT_MODE : mode);
        record.setMessage(truncateMessage(data.getString("message")));
        JSONObject requestedSchema = data.getJSONObject("requestedSchema");
        record.setSchemaJson(requestedSchema == null ? null : requestedSchema.toJSONString());
        record.setStatus(STATUS_PENDING);
        {
            AgentConversationTurnDO turn = turnDao.findByConversationTurnForUpdate(tenantId, conversationId, turnId);
            if (AgentConversationService.questionExpiryStop(turn) != null) record.setStatus(STATUS_CANCELED);
            AgentConversationDO conv = convDao.findById(tenantId, conversationId);
            if (turn != null && turn.getDispatchAttempt() != null && turn.getDispatchAttempt() > 0
                    && conv != null && conv.getExecutorId() != null) {
                if (data.getIntValue("answerProtocolVersion") == 1) record.setAnswerProtocolVersion(1);
                record.setDispatchAttempt(turn.getDispatchAttempt());
                record.setExecutorId(conv.getExecutorId());
            }
        }
        // 幂等靠 uk_conv_request 的 INSERT IGNORE，不在热路径上多一次查询。
        int inserted = elicitationDao.insertIfAbsent(record);
        if (STATUS_CANCELED.equals(record.getStatus())) {
            boolean settled = inserted == 1 || elicitationDao.settleIfPending(tenantId, conversationId,
                    requestId, STATUS_CANCELED, null) == 1;
            if (settled) afterCommit(() -> notifySystemStopped(List.of(record)));
        }
        log.info("acp elicitation opened conversationId={} turnId={} requestId={} mode={}",
                conversationId, turnId, requestId, record.getMode());
        return record.getStatus();
    }

    private void settleFromRuntime(long tenantId, long conversationId, String requestId,
            String action) {
        String status = terminalStatusOf(action);
        if (status == null) {
            log.warn("acp elicitation resolved with unknown action conversationId={} requestId={} action={}",
                    conversationId, requestId, action);
            return;
        }
        // answerJson 传 null：答案已由 reply() 落库，resolved 只是执行器的确认回声。
        // 已终态的记录抢不到这次转移，因此不会覆盖掉库里的答案。
        int settled = elicitationDao.settleIfPending(tenantId, conversationId, requestId, status, null);
        log.info("acp elicitation resolved conversationId={} requestId={} status={} settled={}",
                conversationId, requestId, status, settled);
    }

    /**
     * 用户提交回答。校验通过后先抢状态转移，抢到才下发帧。
     *
     * @param action accept 或 decline；cancel 是服务端内部的取消联动动作，不接受外部提交
     */
    public void reply(long tenantId, long conversationId, String requestId, String action,
            String answerJson) {
        reply(tenantId, conversationId, requestId, action, answerJson, null);
    }

    public void reply(long tenantId, long conversationId, String requestId, String action,
            String answerJson, Long respondingUserId) {
        AgentConversationElicitationDO record =
                elicitationDao.findByRequestId(tenantId, conversationId, requestId);
        if (record == null) {
            // findByRequestId 按 conversationId 收口，跨会话猜 requestId 走到这里。
            throw BizException.keyed(ErrorCode.NOT_FOUND, "conversation.elicitation_not_found",
                    new Object[]{requestId}, "elicitation not found in this conversation: " + requestId);
        }
        AgentConversationTurnDO origin = turnDao.findByConversationTurn(tenantId, conversationId, record.getTurnId());
        if (origin != null && origin.getSubmittedUserId() != null
                && !origin.getSubmittedUserId().equals(respondingUserId)) {
            throw BizException.keyed(ErrorCode.NO_PERMISSION, "conversation.actor_reply_forbidden", null,
                    "only the original message sender may answer this question");
        }
        if (Integer.valueOf(1).equals(record.getAnswerProtocolVersion()) && answerDeliveryService != null) {
            String normalized = normalizeReply(action, answerJson);
            answerDeliveryService.submit(tenantId, conversationId, requestId, action, normalized);
            return;
        }
        if (!STATUS_PENDING.equals(record.getStatus())) {
            throw BizException.keyed(ErrorCode.CONFLICT, "conversation.elicitation_settled",
                    new Object[]{record.getStatus()}, "elicitation already settled: " + record.getStatus());
        }
        String normalizedAnswer = normalizeReply(action, answerJson);
        AgentConversationDO conv = requireOnlineConversation(tenantId, conversationId);

        String status = ACTION_ACCEPT.equals(action) ? STATUS_ANSWERED : STATUS_DECLINED;
        int settled = legacyAnswerTransaction(() -> {
            requireProcessingTurn(tenantId, conversationId, record.getTurnId());
            return elicitationDao.settleIfPending(tenantId, conversationId, requestId, status, normalizedAnswer);
        });
        if (settled != 1) {
            // 过期任务或取消联动刚好先抢到，答案已无处可去。
            throw BizException.keyed(ErrorCode.CONFLICT, "conversation.elicitation_concurrent",
                    new Object[]{requestId}, "elicitation was settled concurrently: " + requestId);
        }
        try {
            transport.sendElicitationReply(conv, record.getTurnId(), requestId, action,
                    normalizedAnswer);
        } catch (RuntimeException e) {
            // 卡片已是终态而答案没送出去，不补偿回 PENDING 用户就再也无法重答。
            // 只回滚仍停在自己刚写下那个终态的记录，避免覆盖并发赢家。
            int restored = legacyAnswerTransaction(() -> {
                var current = turnDao.findByConversationTurnForUpdate(tenantId, conversationId, record.getTurnId());
                // The admitted human answer is history if a later stop won. Never reopen its request.
                if (AgentConversationService.questionExpiryStop(current) != null) return 0;
                return elicitationDao.restorePendingIfStatus(tenantId, conversationId, requestId, status);
            });
            log.warn("acp elicitation reply delivery failed, restored={} conversationId={} "
                    + "requestId={}: {}", restored, conversationId, requestId, e.getMessage());
            throw e;
        }
        log.info("acp elicitation replied conversationId={} turnId={} requestId={} action={}",
                conversationId, record.getTurnId(), requestId, action);
    }

    private org.springframework.transaction.support.TransactionTemplate legacyAnswerAdmission;

    @Autowired
    void configureLegacyAnswerTransaction(org.springframework.transaction.PlatformTransactionManager manager) {
        legacyAnswerAdmission = new org.springframework.transaction.support.TransactionTemplate(manager);
        legacyAnswerAdmission.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private int legacyAnswerTransaction(java.util.function.Supplier<Integer> action) {
        // The caller's authorization read may have a transaction; do not carry answer row locks
        // into legacy transport. Only admission and conditional compensation happen here.
        return legacyAnswerAdmission == null ? action.get() : legacyAnswerAdmission.execute(status -> action.get());
    }

    /**
     * 轮次已不在 PROCESSING 时执行器侧的挂起 JSON-RPC 请求早已消失，答案会进
     * 黑洞。必须在落终态前拦住，否则卡片变成 ANSWERED 而 Agent 永远收不到。
     */
    private void requireProcessingTurn(long tenantId, long conversationId, long turnId) {
        AgentConversationTurnDO turn = turnDao.findByConversationTurnForUpdate(tenantId, conversationId,
                turnId);
        if (turn == null || !TURN_STATUS_PROCESSING.equals(turn.getStatus())
                || AgentConversationService.questionExpiryStop(turn) != null) {
            throw BizException.keyed(ErrorCode.CONFLICT, "conversation.elicitation_turn_inactive",
                    new Object[]{String.valueOf(turnId)}, "elicitation turn is no longer processing: " + turnId);
        }
    }

    /**
     * 校验并归一化回答载荷。decline 一律不带答案 —— 用户点的是「跳过」，
     * 界面上残留的表单内容不该被当成答案交给 Agent。
     */
    private String normalizeReply(String action, String answerJson) {
        if (ACTION_DECLINE.equals(action)) {
            return null;
        }
        if (!ACTION_ACCEPT.equals(action)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "conversation.elicitation_action_unsupported",
                    new Object[]{action}, "unsupported elicitation action: " + action);
        }
        if (answerJson == null || answerJson.isBlank()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "conversation.elicitation_answer_required", null, "elicitation answer is required");
        }
        // content 必须是 JSON 对象：帧构造要把它放进 content 键，数组、裸标量、
        // 字面量 null 都会在投递时炸掉，而那时卡片已经落成 ANSWERED 了。
        Object parsed;
        try {
            parsed = JSON.parse(answerJson);
        } catch (RuntimeException e) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "conversation.elicitation_answer_object", null, "elicitation answer must be a JSON object");
        }
        if (!(parsed instanceof JSONObject)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "conversation.elicitation_answer_object", null, "elicitation answer must be a JSON object");
        }
        return answerJson;
    }

    private AgentConversationDO requireOnlineConversation(long tenantId, long conversationId) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null || conv.getExecutorId() == null
                || !runtimePresence.isExecutorOnline(conv.getExecutorId())) {
            // 先判在线再落终态：否则卡片变成 ANSWERED 而执行器永远收不到答案，
            // 用户既看不到进展也无法重答。
            throw new IllegalStateException("RUNTIME_OFFLINE");
        }
        return conv;
    }

    /**
     * 轮次取消前把该轮所有挂起卡片置 CANCELED，**只写库、不发副作用**。
     *
     * <p>拆成两步是因为调用方都在事务里：状态转移必须与轮次状态原子提交、可随
     * 事务回滚，而下发给执行器的 cancel 帧不可回滚 —— 若回滚了卡片却已把帧发
     * 出去，卡片回到 PENDING 而执行器侧 requestId 已取消，用户之后的回答会被
     * 服务端接受却永远投不进去。
     *
     * <p>返回真正抢到状态转移的记录，调用方须在**提交之后**用它调
     * {@link #notifyCanceled(List)}。
     */
    public List<AgentConversationElicitationDO> settlePendingForTurn(long tenantId,
            long conversationId, long turnId) {
        turnDao.findByConversationTurnForUpdate(tenantId, conversationId, turnId);
        elicitationDao.closeAnswerDeliveriesForTurn(tenantId, conversationId, turnId);
        List<AgentConversationElicitationDO> settled = new ArrayList<>();
        for (AgentConversationElicitationDO record : elicitationDao.listPendingByTurn(tenantId, turnId)) {
            if (elicitationDao.settleIfPending(record.getTenantId(), record.getConversationId(),
                    record.getRequestId(), STATUS_CANCELED, null) == 1) {
                settled.add(record);
            }
            // 抢不到说明用户刚好同时作答，答案已下发，不能再发一个矛盾的动作。
        }
        return settled;
    }

    /** 发出 {@link #settlePendingForTurn} 攒下的副作用。必须在事务提交之后调用。 */
    public void notifyCanceled(List<AgentConversationElicitationDO> settled) {
        for (AgentConversationElicitationDO record : settled) {
            notifyRuntimeBestEffort(record, ACTION_CANCEL, STATUS_CANCELED);
            notifyBrowserBestEffort(record, ACTION_CANCEL, STATUS_CANCELED);
        }
    }

    /** System expiry stops the execution; it never fabricates an ACP answer. */
    public synchronized void expirePending(Date cutoff, int limit) {
        int boundedLimit = Math.max(1, limit);
        var page = elicitationDao.listPendingOlderThan(cutoff, expiryCursor, boundedLimit);
        for (var record : page) {
            // Advance even for permanently unprovable identities or transient per-row failures.
            expiryCursor = record.getId();
            try {
                if (answerDeliveryService != null) answerDeliveryService.expireQuestion(record, cutoff);
            } catch (RuntimeException e) {
                log.warn("question expiry deferred conversationId={} turnId={}", record.getConversationId(), record.getTurnId(), e);
            }
        }
        if (page.size() < boundedLimit) expiryCursor = 0;
    }

    /** Caller holds conversation coordination, then the current turn row lock. */
    List<AgentConversationElicitationDO> expireForAttempt(AgentConversationElicitationDO candidate,
            Date cutoff, AgentConversationTurnDO turn, AgentConversationDO conv) {
        var row = elicitationDao.findByRequestIdForUpdate(candidate.getTenantId(),
                candidate.getConversationId(), candidate.getRequestId());
        if (row == null || !STATUS_PENDING.equals(row.getStatus()) || row.getGmtCreate() == null
                || !row.getGmtCreate().before(cutoff)
                || !java.util.Objects.equals(row.getTurnId(), turn.getId())
                || !java.util.Objects.equals(row.getDispatchAttempt(), candidate.getDispatchAttempt())
                || !java.util.Objects.equals(row.getExecutorId(), candidate.getExecutorId())) return List.of();
        if (row.getDispatchAttempt() == null && row.getExecutorId() == null) {
            // Old legacy rows have no identity. Missing timestamps prove nothing.
            // TIMESTAMP precision is seconds, so equal seconds remain a valid first dispatch.
            if (turn.getLastDispatchAt() == null
                    || turn.getLastDispatchAt().getTime() / 1000 > row.getGmtCreate().getTime() / 1000) return List.of();
        } else if (!java.util.Objects.equals(row.getDispatchAttempt(), turn.getDispatchAttempt())
                || !java.util.Objects.equals(row.getExecutorId(), conv.getExecutorId())) return List.of();
        if (elicitationDao.settleIfPending(row.getTenantId(), row.getConversationId(),
                row.getRequestId(), STATUS_EXPIRED, row.getAnswerJson()) != 1) return List.of();
        row.setStatus(STATUS_EXPIRED);
        List<AgentConversationElicitationDO> settled = new ArrayList<>();
        settled.add(row);
        // Preserve real answers and truthful uncertainty. This path sends no question replies.
        settled.addAll(settlePendingForTurn(row.getTenantId(), row.getConversationId(), row.getTurnId()));
        return settled;
    }

    private void afterCommit(Runnable effect) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { effect.run(); }
                    });
        } else effect.run();
    }

    void notifySystemStopped(List<AgentConversationElicitationDO> settled) {
        for (var row : settled) {
            notifyBrowserBestEffort(row, ACTION_CANCEL,
                    STATUS_EXPIRED.equals(row.getStatus()) ? STATUS_EXPIRED : STATUS_CANCELED, true);
        }
    }

    /**
     * 卡片进终态时向浏览器推一条 {@code acp_elicitation_resolved}。
     *
     * <p>不推的话执行器离线或超时后前端卡片永远停在未解决态：用户既看不到卡片
     * 已失效，也会继续对着一个没人接收答案的表单填内容。
     *
     * <p>与执行器帧一样是 best-effort：状态已落库，推送失败不该让剩下的卡片
     * 和后续的取消 / 过期流程停摆。
     */
    private void notifyBrowserBestEffort(AgentConversationElicitationDO record, String action,
            String status) {
        notifyBrowserBestEffort(record, action, status, false);
    }

    private void notifyBrowserBestEffort(AgentConversationElicitationDO record, String action,
            String status, boolean committed) {
        if (browserEventPublisher == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("requestId", record.getRequestId());
        data.put("action", action);
        data.put("status", status);
        JSONObject payload = new JSONObject();
        payload.put("type", EVENT_RESOLVED);
        payload.put("data", data);
        try {
            if (committed) {
                browserEventPublisher.publishCommittedServerEvent(record.getTenantId(),
                        record.getConversationId(), record.getTurnId(), EVENT_RESOLVED, payload.toJSONString());
            } else {
                browserEventPublisher.publishServerEvent(record.getTenantId(),
                        record.getConversationId(), record.getTurnId(), EVENT_RESOLVED, payload.toJSONString());
            }
        } catch (RuntimeException e) {
            log.warn("acp elicitation {} browser push failed conversationId={} requestId={}: {}",
                    status, record.getConversationId(), record.getRequestId(), e.getMessage());
        }
    }

    /**
     * 批量路径的投递是 best-effort：状态已落终态，一张卡片投递失败不该让
     * 剩下的卡片和后续的取消 / 过期流程停摆。执行器侧另有 30 分钟超时兜底。
     */
    private void notifyRuntimeBestEffort(AgentConversationElicitationDO record, String action,
            String status) {
        AgentConversationDO conv = convDao.findById(record.getTenantId(),
                record.getConversationId());
        if (conv == null || conv.getExecutorId() == null) {
            log.info("acp elicitation {} not delivered: conversation or executor gone "
                            + "conversationId={} requestId={}",
                    status, record.getConversationId(), record.getRequestId());
            return;
        }
        try {
            transport.sendElicitationReply(conv, record.getTurnId(), record.getRequestId(),
                    action, null);
            log.info("acp elicitation {} delivered conversationId={} turnId={} requestId={}",
                    status, record.getConversationId(), record.getTurnId(), record.getRequestId());
        } catch (RuntimeException e) {
            log.warn("acp elicitation {} delivery failed conversationId={} requestId={}: {}",
                    status, record.getConversationId(), record.getRequestId(), e.getMessage());
        }
    }

    /** 供会话详情回显未解决卡片，浏览器刷新后据此恢复卡片。 */
    public List<AgentConversationElicitationDO> listPending(long tenantId, long conversationId) {
        return elicitationDao.listPendingByConversation(tenantId, conversationId);
    }

    public List<AgentConversationElicitationDO> listQuestionHistory(long tenantId, long conversationId) {
        return elicitationDao.listQuestionHistory(tenantId, conversationId);
    }

    public List<AgentConversationElicitationDO> listAnswerDeliveries(long tenantId, long conversationId) {
        return elicitationDao.listAnswerDeliveries(tenantId, conversationId);
    }

    private String terminalStatusOf(String action) {
        if (ACTION_ACCEPT.equals(action)) {
            return STATUS_ANSWERED;
        }
        if (ACTION_DECLINE.equals(action)) {
            return STATUS_DECLINED;
        }
        if (ACTION_CANCEL.equals(action)) {
            return STATUS_CANCELED;
        }
        return null;
    }

    private String truncateMessage(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
