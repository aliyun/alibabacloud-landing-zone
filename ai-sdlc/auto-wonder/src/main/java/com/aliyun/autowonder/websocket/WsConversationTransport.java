package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.ConversationCapabilityService;
import com.aliyun.autowonder.conversation.ConversationCapabilitySnapshot;
import com.aliyun.autowonder.conversation.ConversationTransport;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import com.aliyun.autowonder.redis.RedisManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WsConversationTransport implements ConversationTransport {

    private static final ObjectMapper FRAME_MAPPER = new ObjectMapper();

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;
    private final ConversationCapabilityService capabilityService;
    private final AgentEnvironmentVariableResolver environmentVariableResolver;
    private final PresenceManager presenceManager;

    public WsConversationTransport(SessionRegistry sessionRegistry, RedisManager redisManager) {
        this(sessionRegistry, redisManager, null, null, null);
    }

    public WsConversationTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            ConversationCapabilityService capabilityService) {
        this(sessionRegistry, redisManager, capabilityService, null, null);
    }

    @Autowired
    public WsConversationTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            ConversationCapabilityService capabilityService,
            AgentEnvironmentVariableResolver environmentVariableResolver,
            PresenceManager presenceManager) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
        this.capabilityService = capabilityService;
        this.environmentVariableResolver = environmentVariableResolver;
        this.presenceManager = presenceManager;
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
        Map<String, String> environmentVariables = environmentVariableResolver == null
                ? Map.of()
                : environmentVariableResolver.resolve(conv.getTenantId(), capability.agentVersionId());
        requireEnvironmentVariableProtocol(conv.getExecutorId(), environmentVariables);
        frame.put("environmentVariables", environmentVariables);
        // The runtime expects plain JSON maps, not Fastjson references for shared empty maps.
        final String payload;
        try {
            payload = FRAME_MAPPER.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WebSocket conversation frame serialization failed", e);
        }
        deliverToExecutor(conv.getExecutorId(), payload);
    }

    private void requireEnvironmentVariableProtocol(long executorId,
            Map<String, String> environmentVariables) {
        if (environmentVariables.isEmpty()) {
            return;
        }
        if (presenceManager == null || !presenceManager.supportsProtocolFeature(
                executorId, WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1)) {
            throw new ExecutorProtocolCompatibilityException(
                    WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1);
        }
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

    @Override
    public void sendElicitationReply(AgentConversationDO conv, long turnId, String requestId,
            String action, String answerJson) {
        if (conv.getExecutorId() == null) {
            throw new IllegalArgumentException("conversation must have a bound executor");
        }
        JSONObject frame = buildElicitationReplyFrame(conv.getExecutorId(), conv.getId(), turnId,
                requestId, action, answerJson);
        deliverToExecutor(conv.getExecutorId(), frame.toJSONString());
    }

    @Override
    public void sendCommandsProbe(AgentConversationDO conv) {
        if (conv.getExecutorId() == null) {
            throw new IllegalArgumentException("conversation must have a bound executor");
        }
        deliverToExecutor(conv.getExecutorId(),
                buildCommandsProbeFrame(conv.getExecutorId(), conv.getId()).toJSONString());
    }

    /**
     * 与执行器 daemon/wsclient.ConversationElicitationReplyFrame 逐字段对齐的帧构造。
     * 抽成静态方法是为了让契约测试不必搭 WS 环境就能断言字段名。
     *
     * <p>{@code executorId} 不属于执行器读取的业务字段，但 Redis 广播兜底靠
     * {@link NodeMailboxListener} 按它找本机会话，缺失会导致跨节点投递被静默丢弃。
     *
     * <p>{@code answerJson} 为 null 或空白时不放 {@code content} 键 —— decline 与
     * cancel 本就没有答案，塞一个空对象会让执行器误以为用户答了空表单。
     */
    static JSONObject buildElicitationReplyFrame(long executorId, long conversationId, long turnId,
            String requestId, String action, String answerJson) {
        JSONObject frame = new JSONObject(true);
        frame.put("type", "CONVERSATION_ELICITATION_REPLY");
        frame.put("executorId", executorId);
        frame.put("conversationId", conversationId);
        frame.put("turnId", turnId);
        frame.put("requestId", requestId);
        frame.put("action", action);
        if (answerJson != null && !answerJson.isBlank()) {
            frame.put("content", JSON.parseObject(answerJson));
        }
        return frame;
    }

    /** 静态、可单测：探针帧只带路由所需的 executorId + conversationId（cwd 由执行器派生）。 */
    public static JSONObject buildCommandsProbeFrame(Long executorId, Long conversationId) {
        JSONObject frame = new JSONObject(true);
        frame.put("type", "CONVERSATION_COMMANDS_PROBE");
        frame.put("executorId", executorId);
        frame.put("conversationId", conversationId);
        return frame;
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
