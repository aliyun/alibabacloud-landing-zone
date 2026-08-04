package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvolutionModeResolverLiteServiceTest {

    private AgentDao agentDao;
    private AgentVersionDao versionDao;
    private EvolutionModeResolverLiteService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        service = new EvolutionModeResolverLiteService(agentDao, versionDao);
    }

    @Test
    void defaultsToAssistedWhenAgentHasNoMode() {
        AgentDO agent = new AgentDO();
        agent.setId(30L);
        agent.setTenantId(10L);
        agent.setOnlineVersionId(88L);
        when(agentDao.findById(30L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO();
        version.setId(88L);
        version.setTenantId(10L);
        when(versionDao.findById(88L)).thenReturn(version);

        assertEquals(EvolutionMode.ASSISTED, service.resolve(10L, 30L));
    }

    @Test
    void readsModeFromOnlineVersionIdentityJson() {
        AgentDO agent = new AgentDO();
        agent.setId(30L);
        agent.setTenantId(10L);
        agent.setOnlineVersionId(88L);
        when(agentDao.findById(30L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO();
        version.setId(88L);
        version.setTenantId(10L);
        version.setIdentityJson("{\"evolutionMode\":\"MANUAL\"}");
        when(versionDao.findById(88L)).thenReturn(version);

        assertEquals(EvolutionMode.MANUAL, service.resolve(10L, 30L));
    }

    @Test
    void invalidStoredModeFallsBackToAssisted() {
        AgentDO agent = new AgentDO();
        agent.setId(30L);
        agent.setTenantId(10L);
        agent.setEditingVersionId(77L);
        when(agentDao.findById(30L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO();
        version.setId(77L);
        version.setTenantId(10L);
        version.setIdentityJson("{\"evolutionMode\":\"FULL_AUTO_RELEASE\"}");
        when(versionDao.findById(77L)).thenReturn(version);

        assertEquals(EvolutionMode.ASSISTED, service.resolve(10L, 30L));
    }
}
