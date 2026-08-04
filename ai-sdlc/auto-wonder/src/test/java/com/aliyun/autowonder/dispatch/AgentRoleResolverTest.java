package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRoleResolverTest {

    private AgentDao agentDao;
    private AgentRoleResolver resolver;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        resolver = new AgentRoleResolver(agentDao);
    }

    @Test
    void returnsNullWhenNoMatch() {
        when(agentDao.findOnlineByRoleCode(1L, "BE")).thenReturn(List.of());
        assertNull(resolver.resolveOnlineAgentId(1L, "BE"));
    }

    @Test
    void returnsNullWhenRoleCodeBlank() {
        assertNull(resolver.resolveOnlineAgentId(1L, "  "));
        verifyNoInteractions(agentDao);
    }

    @Test
    void picksFirstWhenMultiple() {
        AgentDO a1 = new AgentDO(); a1.setId(11L);
        AgentDO a2 = new AgentDO(); a2.setId(22L);
        when(agentDao.findOnlineByRoleCode(1L, "BE")).thenReturn(List.of(a1, a2));
        assertEquals(11L, resolver.resolveOnlineAgentId(1L, "BE"));
    }
}
