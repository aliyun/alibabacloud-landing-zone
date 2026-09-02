package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.ConversationCapabilityService;
import com.aliyun.autowonder.conversation.ConversationCapabilitySnapshot;
import com.aliyun.autowonder.conversation.ConversationTransport;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WsConversationTransport implements ConversationTransport {

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;
    private final ConversationCapabilityService capabilityService;

    public WsConversationTransport(SessionRegistry sessionRegistry, RedisManager redisManager) {
        this(sessionRegistry, redisManager, null);
    }

    @Autowired
    public WsConversationTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            ConversationCapabilityService capabilityService) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
        this.capabilityService = capabilityService;
    }

    @Override
    public void send(AgentConversationDO conv, Long turnId, String content, String systemPrompt,
            Integer dispatchAttempt) {
        if (conv.getExecutorId() == null) {
            throw new IllegalArgumentException("conversation must have a bound executor");
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "CONVERSATION_TURN");
        frame.put("executorId", conv.getExecutorId());
        frame.put("conversationId", conv.getId());
        frame.put("turnId", turnId);
        frame.put("agentId", conv.getAgentId());
        if (dispatchAttempt != null) {
            frame.put("dispatchAttempt", dispatchAttempt);
        }
        frame.put("content", content);
        String requestId = AutoWonderContext.get().getRequestId();
        if (requestId == null || requestId.isEmpty()) {
            requestId = MDC.get("requestId");
        }
        frame.put("requestId", requestId == null ? "" : requestId);
        frame.put("cliSessionRef", conv.getCliSessionRef() == null ? "" : conv.getCliSessionRef());
        frame.put("systemPrompt", systemPrompt == null ? "" : systemPrompt);
        if (capabilityService == null) {
            throw new IllegalStateException("conversation capability service is unavailable");
        }
        ConversationCapabilitySnapshot capability = capabilityService.prepare(conv, turnId);
        frame.put("agentVersionId", capability.agentVersionId());
        frame.put("capabilityDownloadUrl", capability.downloadUrl());
        frame.put("capabilitySha256", capability.sha256());
        frame.put("capabilityHash", capability.capabilityHash());
        frame.put("mcpToken", capability.mcpToken());
        frame.put("mcpSecrets", capability.mcpSecrets());
        deliverToExecutor(conv.getExecutorId(), frame.toJSONString());
    }

    @Override
    public void sendCancel(AgentConversationDO conv, Long turnId) {
        if (conv.getExecutorId() == null) {
            throw new IllegalArgumentException("conversation must have a bound executor");
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "CONVERSATION_TURN_CANCEL");
        frame.put("executorId", conv.getExecutorId());
        frame.put("conversationId", conv.getId());
        frame.put("turnId", turnId);
        deliverToExecutor(conv.getExecutorId(), frame.toJSONString());
    }

    private void deliverToExecutor(Long executorId, String payload) {
        ExecutorSession session = sessionRegistry.findByExecutorId(executorId);
        try {
            if (session != null && session.getSession().isOpen()) {
                session.sendText(payload);
            } else {
                redisManager.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
            }
        } catch (Exception e) {
            throw new IllegalStateException("WebSocket conversation send failed", e);
        }
    }
}
