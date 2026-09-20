package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.ConversationCapabilityService;
import com.aliyun.autowonder.conversation.ConversationCapabilitySnapshot;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WsConversationTransportTest {

    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final RedisManager redisManager = mock(RedisManager.class);
    private final ConversationCapabilityService capabilityService = mock(ConversationCapabilityService.class);
    private final WsConversationTransport transport =
            new WsConversationTransport(sessionRegistry, redisManager, capabilityService);

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void publishesToRedisWhenNoLocalSession() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);
        when(capabilityService.prepare(any(), eq(11L))).thenReturn(snapshot());

        transport.send(conv, 11L, "hello", "SYS", null);

        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("CONVERSATION_TURN"));
    }

    @Test
    void includesRequestIdInConversationTurnFrame() {
        AutoWonderContext.get().setRequestId("rid-frame");
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);
        when(capabilityService.prepare(any(), eq(11L))).thenReturn(snapshot());

        transport.send(conv, 11L, "hello", "SYS", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        assertTrue(payload.getValue().contains("\"requestId\":\"rid-frame\""));
    }

    @Test
    void includesCliSessionRefForResumeTurns() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        conv.setCliSessionRef("sess-123");
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);
        when(capabilityService.prepare(any(), eq(11L))).thenReturn(snapshot());

        transport.send(conv, 11L, "hello again", null, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        assertTrue(payload.getValue().contains("\"cliSessionRef\":\"sess-123\""));
        assertTrue(payload.getValue().contains("\"systemPrompt\":\"\""));
    }

    @Test
    void includesCurrentAgentCapabilitySnapshotOnEveryTurn() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setTenantId(1L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        // The capability snapshot is the effective per-turn version and may supersede this value.
        conv.setAgentVersionId(49L);
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);
        when(capabilityService.prepare(conv, 11L)).thenReturn(snapshot());

        transport.send(conv, 11L, "hello", "SYS", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        String frame = payload.getValue();
        assertTrue(frame.contains("\"agentVersionId\":50"));
        assertTrue(frame.contains("\"capabilityDownloadUrl\":\"https://oss/cap.zip\""));
        assertTrue(frame.contains("\"capabilitySha256\":\"abc123\""));
        assertTrue(frame.contains("\"capabilityHash\":\"abc123\""));
        assertTrue(frame.contains("\"mcpToken\":\"awconversation_token\""));
        assertTrue(frame.contains("\"mcpSecrets\":{\"kc:v1:test\":\"secret\"}"));
    }

    @Test
    void sendCancelPublishesConversationTurnCancelFrame() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);

        transport.sendCancel(conv, 11L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        String frame = payload.getValue();
        assertTrue(frame.contains("\"type\":\"CONVERSATION_TURN_CANCEL\""));
        assertTrue(frame.contains("\"conversationId\":5"));
        assertTrue(frame.contains("\"turnId\":11"));
        verifyNoInteractions(capabilityService);
    }

    @Test
    void resolvesLatestCompleteEnvironmentSnapshotForEveryTurn() {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        WsConversationTransport envTransport = new WsConversationTransport(
                sessionRegistry, redisManager, capabilityService, resolver, presence);
        AgentConversationDO conv = conversation();
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);
        when(capabilityService.prepare(eq(conv), anyLong())).thenReturn(snapshot());
        when(resolver.resolve(1L, 50L))
                .thenReturn(Map.of("TOKEN", "first"), Map.of("TOKEN", "updated"));
        when(presence.supportsProtocolFeature(
                9L, WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1)).thenReturn(true);

        envTransport.send(conv, 11L, "one", "SYS", 1);
        envTransport.send(conv, 12L, "two", "SYS", 1);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager, times(2)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL),
                payload.capture());
        assertEquals("first", com.alibaba.fastjson.JSON.parseObject(payload.getAllValues().get(0))
                .getJSONObject("environmentVariables").getString("TOKEN"));
        assertEquals("updated", com.alibaba.fastjson.JSON.parseObject(payload.getAllValues().get(1))
                .getJSONObject("environmentVariables").getString("TOKEN"));
        verify(resolver, times(2)).resolve(1L, 50L);
    }

    @Test
    void rejectsNonEmptyConversationSnapshotOnUnsupportedExecutor() {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        WsConversationTransport envTransport = new WsConversationTransport(
                sessionRegistry, redisManager, capabilityService, resolver, presence);
        AgentConversationDO conv = conversation();
        when(capabilityService.prepare(conv, 11L)).thenReturn(snapshot());
        when(resolver.resolve(1L, 50L)).thenReturn(Map.of("TOKEN", "secret-value"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> envTransport.send(conv, 11L, "one", "SYS", 1));

        assertTrue(error.getMessage().contains(WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1));
        assertFalse(error.getMessage().contains("secret-value"));
        verify(redisManager, never()).publish(anyString(), anyString());
    }

    @Test
    void sendsExplicitEmptyConversationSnapshotToLegacyExecutor() {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        WsConversationTransport envTransport = new WsConversationTransport(
                sessionRegistry, redisManager, capabilityService, resolver, presence);
        AgentConversationDO conv = conversation();
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);
        when(capabilityService.prepare(conv, 11L)).thenReturn(snapshot());
        when(resolver.resolve(1L, 50L)).thenReturn(Map.of());

        envTransport.send(conv, 11L, "one", "SYS", 1);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        assertTrue(payload.getValue().contains("\"environmentVariables\":{}"));
        verify(presence, never()).supportsProtocolFeature(
                9L, WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1);
    }

    private AgentConversationDO conversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setTenantId(1L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        conv.setAgentVersionId(50L);
        return conv;
    }

    private ConversationCapabilitySnapshot snapshot() {
        return new ConversationCapabilitySnapshot(50L, "https://oss/cap.zip", "abc123", "abc123",
                "awconversation_token", java.util.Map.of("kc:v1:test", "secret"));
    }
}
