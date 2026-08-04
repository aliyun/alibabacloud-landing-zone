package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class MemoryDistributionServiceTest {
    private AgentDao agentDao;
    private SquadMemberDao squadMemberDao;
    private AgentService agentService;
    private MemoryDistributionService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        squadMemberDao = mock(SquadMemberDao.class);
        agentService = mock(AgentService.class);
        service = new MemoryDistributionService(agentDao, squadMemberDao, agentService);
    }

    @Test
    void agentScopeTargetsOnlyOwner() {
        AgentDO owner = onlineAgent(41L, 10L);
        when(agentDao.findById(41L)).thenReturn(owner);

        service.distribute(memory(7L, 10L, "AGENT", 41L), 99L);

        verify(agentService).attachReviewedMemory(41L, 7L, "AGENT", 10L, 99L);
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void squadScopeTargetsCurrentOnlineMembersOnly() {
        SquadMemberDO first = member(10L, 9L, 41L);
        SquadMemberDO second = member(10L, 9L, 42L);
        when(squadMemberDao.listBySquad(9L)).thenReturn(List.of(first, second));
        when(agentDao.listByIds(10L, List.of(41L, 42L)))
                .thenReturn(List.of(onlineAgent(41L, 10L), draftAgent(42L, 10L)));

        service.distribute(memory(7L, 10L, "SQUAD", 9L), 99L);

        verify(agentService).attachReviewedMemory(41L, 7L, "SQUAD", 10L, 99L);
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void orgScopeTargetsTenantOnlineAgents() {
        when(agentDao.listByTenant(10L)).thenReturn(List.of(
                onlineAgent(41L, 10L), onlineAgent(42L, 10L), draftAgent(43L, 10L)));

        service.distribute(memory(7L, 10L, "ORG", null), 99L);

        verify(agentService).attachReviewedMemory(41L, 7L, "ORG", 10L, 99L);
        verify(agentService).attachReviewedMemory(42L, 7L, "ORG", 10L, 99L);
        verifyNoMoreInteractions(agentService);
    }

    private static MemoryDO memory(long id, long tenantId, String scope, Long ownerRef) {
        MemoryDO memory = new MemoryDO();
        memory.setId(id);
        memory.setTenantId(tenantId);
        memory.setScope(scope);
        memory.setOwnerRef(ownerRef);
        memory.setStatus("ADOPTED");
        return memory;
    }

    private static AgentDO onlineAgent(long id, long tenantId) {
        AgentDO agent = draftAgent(id, tenantId);
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(id + 1000);
        return agent;
    }

    private static AgentDO draftAgent(long id, long tenantId) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(tenantId);
        agent.setStatus("DRAFT");
        return agent;
    }

    private static SquadMemberDO member(long tenantId, long squadId, long agentId) {
        SquadMemberDO member = new SquadMemberDO();
        member.setTenantId(tenantId);
        member.setSquadId(squadId);
        member.setAgentId(agentId);
        return member;
    }
}
