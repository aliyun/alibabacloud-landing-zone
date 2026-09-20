package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.aliyun.autowonder.agent.PlatformIntelligenceService.Availability.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformIntelligenceServiceTest {
    AgentDao agents = mock(AgentDao.class);
    AgentVersionDao versions = mock(AgentVersionDao.class);
    ExecutorSelector executors = mock(ExecutorSelector.class);
    AgentConversationService conversations = mock(AgentConversationService.class);
    AgentConversationDao conversationDao = mock(AgentConversationDao.class);
    AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    PlatformIntelligenceService service = new PlatformIntelligenceService(
            agents, versions, executors, conversations, conversationDao, turns);
    UUID requestId = UUID.randomUUID();
    AgentDO agent;
    AgentVersionDO version;

    @BeforeEach
    void configured() {
        agent = new AgentDO();
        agent.setId(3L);
        agent.setTenantId(1L);
        agent.setKind("PLATFORM");
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(5L);
        version = new AgentVersionDO();
        version.setId(5L);
        version.setAgentId(3L);
        version.setTenantId(1L);
        version.setRoleName("Chief of Staff");
        when(agents.findPlatformAgent(1L)).thenReturn(agent);
        when(versions.findById(5L)).thenReturn(version);
    }

    @Test
    void distinguishesConfigurationAndRuntimeAvailability() {
        assertEquals(RUNTIME_UNAVAILABLE, service.getStatus(1L).status());
        when(executors.hasAvailableExecutor(3L)).thenReturn(true);
        assertTrue(service.getStatus(1L).isAvailable());
        agent.setStatus("OFFLINE");
        assertEquals(AGENT_OFFLINE, service.getStatus(1L).status());
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(null);
        assertEquals(VERSION_UNAVAILABLE, service.getStatus(1L).status());
        when(agents.findPlatformAgent(1L)).thenReturn(null);
        assertEquals(NOT_CONFIGURED, service.getStatus(1L).status());
    }

    @Test
    void rejectsWrongWorkspaceOrVersionIdentity() {
        agent.setTenantId(2L);
        assertEquals(NOT_CONFIGURED, service.getStatus(1L).status());
        agent.setTenantId(1L);
        version.setTenantId(2L);
        assertEquals(VERSION_UNAVAILABLE, service.getStatus(1L).status());
        verifyNoInteractions(executors);
    }

    @Test
    void offlineInvocationDoesNotCreateConversation() {
        var error = assertThrows(PlatformIntelligenceService.UnavailableException.class,
                () -> service.invoke(1L, requestId, "Summarize"));
        assertEquals(RUNTIME_UNAVAILABLE, error.getCapabilityStatus().status());
        verifyNoInteractions(conversations);
    }

    @Test
    void submitsAnIsolatedInternalConversationAndReturnsHandle() {
        when(executors.hasAvailableExecutor(3L)).thenReturn(true);
        doAnswer(inv -> { persist("PROCESSING", null); return null; }).when(conversations)
                .submitTurn(1L, 3L, "PLATFORM_INTERNAL", requestId.toString(), "Summarize", externalId());
        var result = service.invoke(1L, requestId, "Summarize");
        assertEquals(requestId, result.requestId());
        assertEquals(77L, result.conversationId());
        assertFalse(result.isTerminal());
        verify(conversations).submitTurn(1L, 3L, "PLATFORM_INTERNAL", requestId.toString(),
                "Summarize", externalId());
    }

    @Test
    void retriesReadPersistedResultEvenWhenRuntimeIsOffline() {
        persist("SUCCESS", "Summary");
        var result = service.invoke(1L, requestId, "Summarize");
        assertTrue(result.isTerminal());
        assertEquals("Summary", result.content());
        verifyNoInteractions(conversations, agents, executors);
        assertThrows(IllegalArgumentException.class, () -> service.invoke(1L, requestId, "Different"));
    }

    @Test
    void readsFailureAndCancellationWithoutRuntime() {
        for (String status : List.of("FAILED", "CANCELED")) {
            persist(status, "Partial output");
            var result = service.getResult(1L, requestId);
            assertTrue(result.isTerminal());
            assertEquals(status, result.status());
            assertEquals("reason", result.error());
        }
        verifyNoInteractions(executors);
    }

    @Test
    void resultIsWorkspaceAndChannelScoped() {
        persist("SUCCESS", "Summary");
        assertNull(service.getResult(2L, requestId));
        AgentConversationDO other = new AgentConversationDO();
        other.setChannel("DINGTALK");
        when(conversationDao.findById(1L, 77L)).thenReturn(other);
        assertThrows(IllegalArgumentException.class, () -> service.getResult(1L, requestId));
    }

    @Test
    void validatesInputBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> service.getStatus(null));
        assertThrows(IllegalArgumentException.class, () -> service.invoke(1L, null, "hello"));
        assertThrows(IllegalArgumentException.class, () -> service.invoke(1L, requestId, "  "));
        verifyNoInteractions(conversations);
    }

    private String externalId() { return "platform-internal:" + requestId; }

    private void persist(String status, String reply) {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(77L);
        conversation.setChannel("PLATFORM_INTERNAL");
        conversation.setChannelConversationId(requestId.toString());
        when(conversationDao.findById(1L, 77L)).thenReturn(conversation);
        AgentConversationTurnDO inbound = new AgentConversationTurnDO();
        inbound.setId(88L);
        inbound.setConversationId(77L);
        inbound.setDirection("IN");
        inbound.setContent("Summarize");
        inbound.setStatus(status);
        when(turns.findByExternalMsgId(1L, externalId())).thenReturn(inbound);
        AgentConversationTurnDO outbound = new AgentConversationTurnDO();
        outbound.setId(89L);
        outbound.setDirection("OUT");
        outbound.setStatus(status);
        outbound.setContent(reply);
        outbound.setError("reason");
        when(turns.listTurnsByConversation(1L, 77L)).thenReturn(
                reply == null ? List.of(inbound) : List.of(inbound, outbound));
    }
}
