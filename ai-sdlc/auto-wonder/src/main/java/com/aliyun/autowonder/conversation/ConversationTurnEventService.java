package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.websocket.ConversationRealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationTurnEventService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventService.class);
    /** 按轮次取事件的硬上限。一轮 token 级 chunk 可达数千行，不能裸查。 */
    static final int MAX_TURN_EVENTS = 5000;

    private final AgentConversationTurnEventDao eventDao;
    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private ConversationBrowserEventPublisher browserEventPublisher;
    private ConversationElicitationService conversationElicitationService;

    public ConversationTurnEventService(AgentConversationTurnEventDao eventDao,
            AgentConversationDao convDao, AgentConversationTurnDao turnDao) {
        this.eventDao = eventDao;
        this.convDao = convDao;
        this.turnDao = turnDao;
    }

    @Autowired(required = false)
    public void setBrowserEventPublisher(ConversationBrowserEventPublisher browserEventPublisher) {
        this.browserEventPublisher = browserEventPublisher;
    }

    @Autowired(required = false)
    public void setConversationElicitationService(
            ConversationElicitationService conversationElicitationService) {
        this.conversationElicitationService = conversationElicitationService;
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
            dispatchElicitationEvent(tenantId, conversationId, turnId, eventType, assembled);
            publishToBrowser(tenantId, conversationId, turnId, eventSeq, eventType, assembled);
        }
    }

    /**
     * 只拦 {@code acp_elicitation*} 前缀：卡片需要可变状态与幂等约束，必须落挂起表；
     * 而 plan 与 commands 是纯展示数据，事件表本身就是它们的持久化载体。
     *
     * <p>分派失败只记 warn —— 卡片落库出问题不该让整条事件流断掉，浏览器仍要收到这条事件。
     */
    private void dispatchElicitationEvent(long tenantId, long conversationId, long turnId,
            String eventType, String assembled) {
        if (conversationElicitationService == null || assembled == null || eventType == null
                || !eventType.startsWith(ConversationElicitationService.EVENT_TYPE_PREFIX)) {
            return;
        }
        try {
            conversationElicitationService.onEvent(tenantId, conversationId, turnId, eventType,
                    assembled);
        } catch (RuntimeException e) {
            log.warn("acp elicitation event handling failed conversationId={} turnId={} type={}: {}",
                    conversationId, turnId, eventType, e.getMessage());
        }
    }

    public List<AgentConversationTurnEventDO> listEventsAfter(long tenantId, long conversationId,
            long afterId, int limit) {
        return eventDao.listCompletedAfter(tenantId, conversationId, afterId, limit);
    }

    /**
     * 取某一轮次的全部事件，供历史轮次「查看执行详情」按需加载。
     *
     * <p>现有 {@link #listEventsAfter} 只支持 afterId 且被调用方限到 200 条，无法
     * 按轮次取全。这里的上限刻意设得高但有限：执行器逐条转发、一个 token 级
     * chunk 就是一行记录，一轮数百至数千行是常态。
     */
    public List<AgentConversationTurnEventDO> listEventsByTurn(long tenantId, long conversationId,
            long turnId) {
        return eventDao.listByTurn(tenantId, conversationId, turnId, MAX_TURN_EVENTS);
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
            com.aliyun.autowonder.json.JSONObject json = JSON.parseObject(payload);
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
        if (browserEventPublisher == null) {
            return;
        }
        browserEventPublisher.publish(tenantId, conversationId, turnId, eventSeq,
                eventType, payloadJson);
    }
}
