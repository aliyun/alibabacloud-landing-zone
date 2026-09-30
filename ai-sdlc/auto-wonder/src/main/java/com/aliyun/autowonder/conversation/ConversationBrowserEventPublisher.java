package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.Feature;
import com.aliyun.autowonder.websocket.ConversationRealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话事件到浏览器的推送出口。
 *
 * <p>单独成一个组件而不是留在 {@link ConversationTurnEventService} 里，是为了
 * 打断一个真实的 Spring 循环依赖：事件服务需要卡片服务来分派 acp_elicitation，
 * 而卡片服务只是想在卡片进终态时推一条事件给浏览器。让两者都依赖这个更小的
 * 出口，依赖方向就是单向的。
 */
@Component
public class ConversationBrowserEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(ConversationBrowserEventPublisher.class);

    /**
     * 服务端直推事件的序号基于时间，避免与 Runtime 每轮从 1 递增的 eventSeq
     * 撞号 —— 前端按 (turnId, eventSeq) 去重，撞号会让事件被当成重复丢弃。
     */
    private static final AtomicLong SERVER_PUSHED_EVENT_SEQ =
            new AtomicLong(System.currentTimeMillis());

    private ConversationRealtimePublisher conversationRealtimePublisher;

    @Autowired(required = false)
    public void setConversationRealtimePublisher(
            ConversationRealtimePublisher conversationRealtimePublisher) {
        this.conversationRealtimePublisher = conversationRealtimePublisher;
    }

    /** 推送一条已落库事件（沿用 Runtime 上报的 eventSeq）。 */
    public void publish(long tenantId, long conversationId, long turnId, long eventSeq,
            String eventType, String payloadJson) {
        if (conversationRealtimePublisher == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("conversationId", conversationId);
            event.put("turnId", turnId);
            event.put("eventSeq", eventSeq);
            event.put("eventType", eventType);
            if (payloadJson != null) {
                // payload 在这里被解析成对象，随后由 ConversationRealtimePublisher 再序列化
                // 一次才推到浏览器。卡片题目的顺序只存在于 requestedSchema.properties 的
                // 键序中，而 fastjson 默认用 HashMap 承载 JSONObject，这一来一回会把键序
                // 重排成哈希桶序，用户看到的题目顺序就被打乱了，因此必须保序解析。
                event.put("payload", JSON.parse(payloadJson, Feature.OrderedField));
            }
            conversationRealtimePublisher.publish(
                    "conversation:" + conversationId, "CONVERSATION_TURN_EVENT", event);
        } catch (Exception e) {
            log.warn("conversation event browser publish failed conversationId={} turnId={} eventSeq={}: {}",
                    conversationId, turnId, eventSeq, e.getMessage());
        }
    }

    /**
     * 推送一条轮次状态事件（取消等需要前端立即感知终结态的场景）。
     *
     * <p>信封在这里统一构造，避免每个调用方各自手拼这段 JSON。
     */
    public void publishStatusEvent(long tenantId, long conversationId, long turnId, String status) {
        publishServerEvent(tenantId, conversationId, turnId, "status",
                "{\"type\":\"status\",\"status\":\"" + status + "\"}");
    }

    /** 推送一条服务端直生成、不落库的事件（取消、卡片终态等）。 */
    public void publishServerEvent(long tenantId, long conversationId, long turnId,
            String eventType, String payloadJson) {
        publish(tenantId, conversationId, turnId,
                SERVER_PUSHED_EVENT_SEQ.incrementAndGet(), eventType, payloadJson);
    }
}
