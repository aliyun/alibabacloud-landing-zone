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

    @org.springframework.transaction.annotation.Transactional
    public void persistReliableEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        // Serialize critical side effects against cancellation/re-dispatch of this turn.
        AgentConversationTurnDO turn = turnDao.findByConversationTurnForUpdate(tenantId, conversationId, turnId);
        if (turn == null || !"PROCESSING".equals(turn.getStatus())
                || dispatchAttempt <= 0 || !Integer.valueOf(dispatchAttempt).equals(turn.getDispatchAttempt())) return;
        persistEvent(tenantId, executorId, conversationId, turnId, dispatchAttempt, eventSeq,
                chunkIndex, chunkCount, eventType, payloadFragment, true);
    }

    public void persistEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment) {
        persistEvent(tenantId, executorId, conversationId, turnId, dispatchAttempt, eventSeq,
                chunkIndex, chunkCount, eventType, payloadFragment, false);
    }

    private void persistEvent(long tenantId, long executorId, long conversationId,
            long turnId, int dispatchAttempt, long eventSeq, int chunkIndex, int chunkCount,
            String eventType, String payloadFragment, boolean reliable) {
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

        // Old runtimes omit attempt (0); keep their existing behavior during rolling upgrades.
        if (dispatchAttempt > 0 && turn.getDispatchAttempt() != null
                && turn.getDispatchAttempt() > 0 && dispatchAttempt != turn.getDispatchAttempt()) {
            log.warn("conversation event rejected: stale attempt conversationId={} turnId={} attempt={}",
                    conversationId, turnId, dispatchAttempt);
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
            String questionStatus;
            if (reliable && conversationElicitationService != null && assembled != null
                    && eventType != null && eventType.startsWith(ConversationElicitationService.EVENT_TYPE_PREFIX)) {
                // Database failures must roll back and suppress the receiver ACK, allowing retry.
                questionStatus = conversationElicitationService.onEvent(tenantId, conversationId, turnId, eventType, assembled);
            } else {
                questionStatus = dispatchElicitationEvent(tenantId, conversationId, turnId, eventType, assembled);
            }
            // A late question is retained as settled; do not reopen it after the resolved push.
            if ("acp_elicitation".equals(eventType) && "CANCELED".equals(questionStatus)) return;
            String publicPayload=PlatformConversationChannel.CHANNEL.equals(conv.getChannel())
                    ? PlatformEventProjection.payload(eventType,assembled):assembled;
            String publicType=PlatformConversationChannel.CHANNEL.equals(conv.getChannel())
                    && ("thinking".equals(eventType)||"thinking_delta".equals(eventType))?"status":eventType;
            publishToBrowser(tenantId, conversationId, turnId, dispatchAttempt, eventSeq, publicType, publicPayload);
        }
    }

    /**
     * 只拦 {@code acp_elicitation*} 前缀：卡片需要可变状态与幂等约束，必须落挂起表；
     * 而 plan 与 commands 是纯展示数据，事件表本身就是它们的持久化载体。
     *
     * <p>分派失败只记 warn —— 卡片落库出问题不该让整条事件流断掉，浏览器仍要收到这条事件。
     */
    private String dispatchElicitationEvent(long tenantId, long conversationId, long turnId,
            String eventType, String assembled) {
        if (conversationElicitationService == null || assembled == null || eventType == null
                || !eventType.startsWith(ConversationElicitationService.EVENT_TYPE_PREFIX)) {
            return null;
        }
        try {
            return conversationElicitationService.onEvent(tenantId, conversationId, turnId, eventType,
                    assembled);
        } catch (RuntimeException e) {
            log.warn("acp elicitation event handling failed conversationId={} turnId={} type={}: {}",
                    conversationId, turnId, eventType, e.getMessage());
        }
        return null;
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

    public record EventPage(List<AgentConversationTurnEventDO> rows, long nextAfterId, boolean hasMore) {}

    public EventPage listEventsByTurnPage(long tenantId, long conversationId, long turnId,
            long afterId, int requestedLimit) {
        int limit = Math.max(1, Math.min(500, requestedLimit));
        long cursor = Math.max(0, afterId);
        List<AgentConversationTurnEventDO> rows = eventDao.listByTurnAfter(
                tenantId, conversationId, turnId, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<AgentConversationTurnEventDO> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new EventPage(page, page.isEmpty() ? cursor : page.get(page.size() - 1).getId(), more);
    }

    public EventPage listPlatformEventsByTurnPage(long tenantId, long conversationId, long turnId,
            long afterId, int limit) {
        EventPage raw = listEventsByTurnPage(tenantId, conversationId, turnId, afterId, limit);
        java.util.Map<String, List<AgentConversationTurnEventDO>> groups = new java.util.LinkedHashMap<>();
        for (var row : raw.rows()) groups.computeIfAbsent(row.getDispatchAttempt() + ":" + row.getEventSeq(),
                ignored -> new java.util.ArrayList<>()).add(row);
        java.util.List<AgentConversationTurnEventDO> complete = new java.util.ArrayList<>();
        for (var chunks : groups.values()) {
            var head = chunks.get(0);
            complete.addAll(chunks.size() < head.getChunkCount()
                    ? eventDao.listLogicalEventChunks(tenantId, turnId, head.getDispatchAttempt(), head.getEventSeq())
                    : chunks);
        }
        // Cursor follows raw rows, not projected ids: a fragmented event may straddle pages.
        return new EventPage(PlatformEventProjection.events(complete), raw.nextAfterId(), raw.hasMore());
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
            int dispatchAttempt, long eventSeq, String eventType, String payloadJson) {
        if (browserEventPublisher == null) {
            return;
        }
        browserEventPublisher.publish(tenantId, conversationId, turnId, dispatchAttempt, eventSeq,
                eventType, payloadJson);
    }
}
