package com.aliyun.autowonder.conversation;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.websocket.ConversationRealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationTurnEventService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventService.class);
    // 服务端直推事件使用基于时间的序号，避免与 Runtime 每轮次从 1 递增的 eventSeq 冲突。
    private static final java.util.concurrent.atomic.AtomicLong SERVER_PUSHED_EVENT_SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    private final AgentConversationTurnEventDao eventDao;
    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private ConversationRealtimePublisher conversationRealtimePublisher;

    public ConversationTurnEventService(AgentConversationTurnEventDao eventDao,
            AgentConversationDao convDao, AgentConversationTurnDao turnDao) {
        this.eventDao = eventDao;
        this.convDao = convDao;
        this.turnDao = turnDao;
    }

    @Autowired(required = false)
    public void setConversationRealtimePublisher(ConversationRealtimePublisher conversationRealtimePublisher) {
        this.conversationRealtimePublisher = conversationRealtimePublisher;
    }

    public void persistEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null || conv.getExecutorId() == null
                || conv.getExecutorId() != executorId) {
            log.warn("conversation event rejected: executor mismatch tenantId={} conversationId={} "
                    + "executorId={} expectedExecutorId={}",
                    tenantId, conversationId, executorId,
                    conv != null ? conv.getExecutorId() : null);
            return;
        }
        AgentConversationTurnDO turn = turnDao.findProcessingInbound(tenantId, conversationId);
        if (turn == null || turn.getId() != turnId) {
            log.warn("conversation event rejected: no active turn tenantId={} conversationId={} turnId={}",
                    tenantId, conversationId, turnId);
            return;
        }

        AgentConversationTurnEventDO event = new AgentConversationTurnEventDO();
        event.setTenantId(tenantId);
        event.setConversationId(conversationId);
        event.setTurnId(turnId);
        event.setDispatchAttempt(dispatchAttempt);
        event.setEventSeq(eventSeq);
        event.setChunkIndex(chunkIndex);
        event.setChunkCount(chunkCount);
        event.setEventType(eventType);
        event.setPayloadFragment(payloadFragment);
        eventDao.insertChunkIfAbsent(event);

        if (chunkCount <= 1 || allChunksPresent(tenantId, turnId, dispatchAttempt, eventSeq, chunkCount)) {
            String assembled = chunkCount <= 1 ? payloadFragment
                    : assemblePayload(eventDao.listLogicalEventChunks(tenantId, turnId, dispatchAttempt, eventSeq));
            if ("status".equals(eventType) && assembled != null) {
                updateCliSessionRefIfPresent(tenantId, conversationId, assembled);
            }
            publishToBrowser(tenantId, conversationId, turnId, eventSeq, eventType, assembled);
        }
    }

    public List<AgentConversationTurnEventDO> listEventsAfter(long tenantId, long conversationId,
            long afterId, int limit) {
        return eventDao.listCompletedAfter(tenantId, conversationId, afterId, limit);
    }

    /**
     * 服务端直接向浏览器推送轮次状态事件（不落库），用于取消等需要前端
     * 立即感知终结态的场景。
     */
    public void publishStatusEvent(long tenantId, long conversationId, long turnId, String status) {
        String payload = "{\"type\":\"status\",\"status\":\"" + status + "\"}";
        publishToBrowser(tenantId, conversationId, turnId,
                SERVER_PUSHED_EVENT_SEQ.incrementAndGet(), "status", payload);
    }

    private boolean allChunksPresent(long tenantId, long turnId, int dispatchAttempt,
            long eventSeq, int expectedCount) {
        List<AgentConversationTurnEventDO> chunks = eventDao.listLogicalEventChunks(
                tenantId, turnId, dispatchAttempt, eventSeq);
        if (chunks.size() != expectedCount) {
            return false;
        }
        String assembled = assemblePayload(chunks);
        try {
            JSON.parse(assembled);
            return true;
        } catch (Exception e) {
            log.warn("conversation event chunk reassembly produced invalid JSON turnId={} eventSeq={}",
                    turnId, eventSeq);
            return false;
        }
    }

    private String assemblePayload(List<AgentConversationTurnEventDO> chunks) {
        StringBuilder sb = new StringBuilder();
        for (AgentConversationTurnEventDO chunk : chunks) {
            sb.append(chunk.getPayloadFragment());
        }
        return sb.toString();
    }

    private void updateCliSessionRefIfPresent(long tenantId, long conversationId, String payload) {
        try {
            com.alibaba.fastjson.JSONObject json = JSON.parseObject(payload);
            String sessionId = json.getString("sessionId");
            if (sessionId != null && !sessionId.isBlank()) {
                convDao.updateCliSessionRef(tenantId, conversationId, sessionId);
            }
        } catch (Exception e) {
            // non-fatal: session ref update is best-effort
        }
    }

    private void publishToBrowser(long tenantId, long conversationId, long turnId,
            long eventSeq, String eventType, String payloadJson) {
        if (conversationRealtimePublisher == null) {
            return;
        }
        try {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("conversationId", conversationId);
            event.put("turnId", turnId);
            event.put("eventSeq", eventSeq);
            event.put("eventType", eventType);
            if (payloadJson != null) {
                event.put("payload", JSON.parse(payloadJson));
            }
            conversationRealtimePublisher.publish(
                    "conversation:" + conversationId, "CONVERSATION_TURN_EVENT", event);
        } catch (Exception e) {
            log.warn("conversation event browser publish failed conversationId={} turnId={} eventSeq={}: {}",
                    conversationId, turnId, eventSeq, e.getMessage());
        }
    }
}
