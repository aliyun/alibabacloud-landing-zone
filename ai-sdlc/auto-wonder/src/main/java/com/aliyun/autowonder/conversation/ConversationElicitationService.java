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
 * 的那个才下发 REPLY 帧 —— 否则执行器会收到重复或矛盾的答案。
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
    public void onEvent(long tenantId, long conversationId, long turnId, String eventType,
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
            return;
        }
        if (requestId == null || requestId.isBlank()) {
            log.warn("acp elicitation event without requestId conversationId={} turnId={} type={}",
                    conversationId, turnId, eventType);
            return;
        }
        if (EVENT_OPENED.equals(eventType)) {
            openPending(tenantId, conversationId, turnId, requestId, data);
        } else if (EVENT_RESOLVED.equals(eventType)) {
            settleFromRuntime(tenantId, conversationId, requestId, data.getString("action"));
        } else {
            log.info("acp elicitation event type not handled conversationId={} type={}",
                    conversationId, eventType);
        }
    }

    private void openPending(long tenantId, long conversationId, long turnId, String requestId,
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
        // 幂等靠 uk_conv_request 的 INSERT IGNORE，不在热路径上多一次查询。
        elicitationDao.insertIfAbsent(record);
        log.info("acp elicitation opened conversationId={} turnId={} requestId={} mode={}",
                conversationId, turnId, requestId, record.getMode());
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
        AgentConversationElicitationDO record =
                elicitationDao.findByRequestId(tenantId, conversationId, requestId);
        if (record == null) {
            // findByRequestId 按 conversationId 收口，跨会话猜 requestId 走到这里。
            throw new BizException(ErrorCode.NOT_FOUND,
                    "elicitation not found in this conversation: " + requestId);
        }
        if (!STATUS_PENDING.equals(record.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "elicitation already settled: " + record.getStatus());
        }
        String normalizedAnswer = normalizeReply(action, answerJson);
        requireProcessingTurn(tenantId, conversationId, record.getTurnId());
        AgentConversationDO conv = requireOnlineConversation(tenantId, conversationId);

        String status = ACTION_ACCEPT.equals(action) ? STATUS_ANSWERED : STATUS_DECLINED;
        int settled = elicitationDao.settleIfPending(tenantId, conversationId, requestId, status,
                normalizedAnswer);
        if (settled != 1) {
            // 过期任务或取消联动刚好先抢到，答案已无处可去。
            throw new BizException(ErrorCode.CONFLICT,
                    "elicitation was settled concurrently: " + requestId);
        }
        try {
            transport.sendElicitationReply(conv, record.getTurnId(), requestId, action,
                    normalizedAnswer);
        } catch (RuntimeException e) {
            // 卡片已是终态而答案没送出去，不补偿回 PENDING 用户就再也无法重答。
            // 只回滚仍停在自己刚写下那个终态的记录，避免覆盖并发赢家。
            int restored = elicitationDao.restorePendingIfStatus(tenantId, conversationId,
                    requestId, status);
            log.warn("acp elicitation reply delivery failed, restored={} conversationId={} "
                    + "requestId={}: {}", restored, conversationId, requestId, e.getMessage());
            throw e;
        }
        log.info("acp elicitation replied conversationId={} turnId={} requestId={} action={}",
                conversationId, record.getTurnId(), requestId, action);
    }

    /**
     * 轮次已不在 PROCESSING 时执行器侧的挂起 JSON-RPC 请求早已消失，答案会进
     * 黑洞。必须在落终态前拦住，否则卡片变成 ANSWERED 而 Agent 永远收不到。
     */
    private void requireProcessingTurn(long tenantId, long conversationId, long turnId) {
        AgentConversationTurnDO turn = turnDao.findByConversationTurn(tenantId, conversationId,
                turnId);
        if (turn == null || !TURN_STATUS_PROCESSING.equals(turn.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "elicitation turn is no longer processing: " + turnId);
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
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "unsupported elicitation action: " + action);
        }
        if (answerJson == null || answerJson.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "elicitation answer is required");
        }
        // content 必须是 JSON 对象：帧构造要把它放进 content 键，数组、裸标量、
        // 字面量 null 都会在投递时炸掉，而那时卡片已经落成 ANSWERED 了。
        Object parsed;
        try {
            parsed = JSON.parse(answerJson);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "elicitation answer must be a JSON object");
        }
        if (!(parsed instanceof JSONObject)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "elicitation answer must be a JSON object");
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

    /**
     * 兜底过期：置 EXPIRED 并向执行器下发 decline，否则永不回答的会话会一直
     * 占着执行器槽位与 Qoder 进程。
     */
    public void expirePending(Date cutoff, int limit) {
        settleBatch(elicitationDao.listPendingOlderThan(cutoff, limit), STATUS_EXPIRED,
                ACTION_DECLINE);
    }

    private void settleBatch(List<AgentConversationElicitationDO> records, String status,
            String action) {
        if (records.isEmpty()) {
            return;
        }
        for (AgentConversationElicitationDO record : records) {
            int settled = elicitationDao.settleIfPending(record.getTenantId(),
                    record.getConversationId(), record.getRequestId(), status, null);
            if (settled != 1) {
                // 用户刚好同时作答，答案已下发，这里不能再发一次矛盾的动作。
                continue;
            }
            notifyRuntimeBestEffort(record, action, status);
            notifyBrowserBestEffort(record, action, status);
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
        if (browserEventPublisher == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("requestId", record.getRequestId());
        data.put("action", action);
        JSONObject payload = new JSONObject();
        payload.put("type", EVENT_RESOLVED);
        payload.put("data", data);
        try {
            browserEventPublisher.publishServerEvent(record.getTenantId(),
                    record.getConversationId(), record.getTurnId(), EVENT_RESOLVED,
                    payload.toJSONString());
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
