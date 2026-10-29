package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.ConversationCapabilityService;
import com.aliyun.autowonder.conversation.ConversationCapabilitySnapshot;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import com.aliyun.autowonder.redis.RedisManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WsConversationTransportTest {

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void officialIdentityReplacesLegacyPromptWithoutLosingChannelPolicy(boolean acp) throws Exception {
        var presence = mock(PresenceManager.class);
        when(presence.supportsProtocolFeature(9L, "CONVERSATION_ACP_INTERACTION_V1")).thenReturn(acp);
        var sender = new WsConversationTransport(sessionRegistry, redisManager, capabilityService, null, presence);
        var conv = new AgentConversationDO();
        conv.setId(5L);
        conv.setExecutorId(9L);
        conv.setAgentId(3L);
        conv.setChannel("PLATFORM_ASSISTANT");
        when(capabilityService.prepare(conv, 11L)).thenReturn(new ConversationCapabilitySnapshot(
                50L, "https://bundle", "sha", "hash", "token", Map.of(), "OFFICIAL_IDENTITY"));
        sender.send(conv, 11L, "hello", "OLD_RESPONSIBILITIES OLD_IDENTITY_JSON", 1);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        var frame = new ObjectMapper().readTree(payload.getValue());
        String prompt = frame.path("systemPrompt").asText();
        assertTrue(prompt.startsWith("OFFICIAL_IDENTITY"));
        assertFalse(prompt.contains("OLD_"));
        assertTrue(prompt.contains("autowonder.propose_platform_actions"));
        assertEquals(!acp, prompt.contains("不能使用AskUserQuestion"));
        assertEquals(50L, frame.path("agentVersionId").asLong());
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void officialScopedChiefFramePreservesTrustedActorContentAndSession(boolean acp) throws Exception {
        var presence = mock(PresenceManager.class);
        when(presence.supportsProtocolFeature(9L, "CONVERSATION_ACP_INTERACTION_V1")).thenReturn(acp);
        var sender = new WsConversationTransport(sessionRegistry, redisManager, capabilityService, null, presence);
        var conv = new AgentConversationDO();
        conv.setId(5L); conv.setExecutorId(9L); conv.setAgentId(3L);
        conv.setChannel("PLATFORM_ASSISTANT"); conv.setCliSessionRef("existing-session");
        String trustedContent = "Authenticated AutoWonder context: userId=7 workspaceId=1 "
                + "conversationId=5 turnId=11 dispatchAttempt=2\nUser message:\nhello";
        when(capabilityService.prepare(conv, 11L)).thenReturn(new ConversationCapabilitySnapshot(
                50L, "https://bundle", "sha", "official-hash", "current-actor-token", Map.of(),
                1, "OFFICIAL_IDENTITY"));

        sender.send(conv, 11L, trustedContent, "OLD_IDENTITY", 2);

        var payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        var frame = new ObjectMapper().readTree(payload.getValue());
        assertEquals(trustedContent, frame.path("content").asText());
        assertEquals(1, frame.path("conversationActorProtocolVersion").asInt());
        assertEquals("current-actor-token", frame.path("mcpToken").asText());
        assertEquals("existing-session", frame.path("cliSessionRef").asText());
        assertEquals("official-hash", frame.path("capabilityHash").asText());
        assertEquals(2, frame.path("dispatchAttempt").asInt());
        String prompt = frame.path("systemPrompt").asText();
        assertTrue(prompt.startsWith("OFFICIAL_IDENTITY"));
        assertFalse(prompt.contains("OLD_IDENTITY"));
        assertTrue(prompt.contains("autowonder.propose_platform_actions"));
        assertEquals(!acp, prompt.contains("不能使用AskUserQuestion"));
    }

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
        assertFalse(frame.contains("conversationActorProtocolVersion"));
        assertTrue(frame.contains("\"mcpSecrets\":{\"kc:v1:test\":\"secret\"}"));
    }

    @Test
    void scopedActorSnapshotSignalsFailClosedRuntimeProtocol() {
        AgentConversationDO conv = new AgentConversationDO(); conv.setId(5L); conv.setExecutorId(9L); conv.setAgentId(3L);
        when(capabilityService.prepare(conv,11L)).thenReturn(new ConversationCapabilitySnapshot(50L,"url","sha","hash","token",Map.of(),1));
        transport.send(conv,11L,"hello",null,null);
        ArgumentCaptor<String> payload=ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL),payload.capture());
        assertTrue(payload.getValue().contains("\"conversationActorProtocolVersion\":1"));
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
        assertEquals("first", com.aliyun.autowonder.json.JSON.parseObject(payload.getAllValues().get(0))
                .getJSONObject("environmentVariables").getString("TOKEN"));
        assertEquals("updated", com.aliyun.autowonder.json.JSON.parseObject(payload.getAllValues().get(1))
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

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void sendsLiteralEnvironmentAndSecretObjectsWithoutJsonReferences(
            boolean emptySecrets, boolean emptyEnvironment) throws Exception {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        WsConversationTransport envTransport = new WsConversationTransport(
                sessionRegistry, redisManager, capabilityService, resolver, presence);
        AgentConversationDO conv = conversation();
        Map<String, String> secrets = emptySecrets ? Map.of() : Map.of("test-ref", "synthetic-secret");
        Map<String, String> environment = emptyEnvironment ? Map.of() : Map.of("TEST_VALUE", "中文\n\"quoted\"");
        when(capabilityService.prepare(eq(conv), anyLong())).thenReturn(
                new ConversationCapabilitySnapshot(50L, "https://oss/cap.zip", "abc123", "abc123",
                        "synthetic-token", secrets));
        when(resolver.resolve(1L, 50L)).thenReturn(environment);
        when(presence.supportsProtocolFeature(
                9L, WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1)).thenReturn(true);

        envTransport.send(conv, 11L, "first", "SYS", 1);
        conv.setCliSessionRef("resumed-session");
        envTransport.send(conv, 12L, "follow-up", "SYS", 2);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager, times(2)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        // Read plain wire JSON: Fastjson parsing would resolve $ref and hide the defect.
        ObjectMapper reader = new ObjectMapper();
        for (String wire : payload.getAllValues()) {
            JsonNode frame = reader.readTree(wire);
            assertEquals(reader.valueToTree(secrets), frame.get("mcpSecrets"));
            assertEquals(reader.valueToTree(environment), frame.get("environmentVariables"));
            assertFalse(wire.contains("\"$ref\""));
            assertEquals("CONVERSATION_TURN", frame.get("type").asText());
            assertEquals(9L, frame.get("executorId").asLong());
        }
        JsonNode resumed = reader.readTree(payload.getAllValues().get(1));
        assertEquals("resumed-session", resumed.get("cliSessionRef").asText());
        assertEquals(12L, resumed.get("turnId").asLong());
        assertEquals(2, resumed.get("dispatchAttempt").asInt());
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
