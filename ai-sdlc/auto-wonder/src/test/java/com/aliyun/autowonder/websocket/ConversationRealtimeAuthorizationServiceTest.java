package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationRealtimeAuthorizationServiceTest {

    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private ConversationRealtimeAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationRealtimeAuthorizationService(convDao);
    }

    @Test
    void authorizesValidConversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(10L);
        conv.setTenantId(100L);
        when(convDao.findById(100L, 10L)).thenReturn(conv);

        assertTrue(service.authorize(100L, 42L, "conversation:10"));
    }

    @Test
    void deniesWhenConversationNotFound() {
        when(convDao.findById(100L, 10L)).thenReturn(null);

        assertFalse(service.authorize(100L, 42L, "conversation:10"));
    }

    @Test
    void deniesNullChannel() {
        assertFalse(service.authorize(100L, 42L, null));
    }

    @Test
    void deniesNonConversationChannel() {
        assertFalse(service.authorize(100L, 42L, "dispatch:10"));
    }

    @Test
    void deniesMalformedChannel() {
        assertFalse(service.authorize(100L, 42L, "conversation:abc"));
    }

    @Test
    void parseConversationIdReturnsId() {
        assertEquals(10L, service.parseConversationId("conversation:10"));
    }

    @Test
    void parseConversationIdReturnsNullForInvalid() {
        assertNull(service.parseConversationId("dispatch:10"));
        assertNull(service.parseConversationId(null));
        assertNull(service.parseConversationId("conversation:abc"));
    }
}
