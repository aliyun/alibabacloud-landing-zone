package com.aliyun.autowonder.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAgentSeederTest {

    AgentDao agentDao;
    AgentVersionDao versionDao;
    PlatformAgentSeeder seeder;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        seeder = new PlatformAgentSeeder(agentDao, versionDao);
    }

    @Test
    void seed_creates_online_platform_agent_with_approved_v1() {
        doAnswer(inv -> { ((AgentDO) inv.getArgument(0)).setId(11L); return null; })
                .when(agentDao).insert(any());
        doAnswer(inv -> { ((AgentVersionDO) inv.getArgument(0)).setId(12L); return null; })
                .when(versionDao).insert(any());

        boolean created = seeder.seed(100L, 7L);

        assertTrue(created);
        ArgumentCaptor<AgentDO> agentCap = ArgumentCaptor.forClass(AgentDO.class);
        verify(agentDao).insert(agentCap.capture());
        AgentDO agent = agentCap.getValue();
        assertEquals(100L, agent.getTenantId());
        assertEquals("Chief of Staff", agent.getName());
        assertEquals("PLATFORM", agent.getKind());
        assertEquals("ONLINE", agent.getStatus());
        assertEquals(Integer.valueOf(1), agent.getLatestVersionNo());
        assertEquals(7L, agent.getCreatorId());

        ArgumentCaptor<AgentVersionDO> versionCap = ArgumentCaptor.forClass(AgentVersionDO.class);
        verify(versionDao).insert(versionCap.capture());
        AgentVersionDO version = versionCap.getValue();
        assertEquals(11L, version.getAgentId());
        assertEquals(Integer.valueOf(1), version.getVersionNo());
        assertEquals("APPROVED", version.getStatus());
        assertEquals("chief_of_staff", version.getRoleCode());
        assertNull(version.getSdlcId());
        assertTrue(version.getResponsibilities().contains("职责说明"));
        assertTrue(version.getBusinessBackground().contains("灵魂"));
        assertTrue(version.getIdentityJson().contains("\"evolutionMode\":\"ASSISTED\""));

        verify(agentDao).updateStatus(11L, 100L, "ONLINE", 12L, null, 1, 0, 7L);
    }

    @Test
    void seed_skips_when_platform_agent_already_exists() {
        AgentDO existing = new AgentDO();
        existing.setId(11L);
        when(agentDao.findPlatformAgent(100L)).thenReturn(existing);

        assertFalse(seeder.seed(100L, 7L));
        verify(agentDao, never()).insert(any());
        verify(versionDao, never()).insert(any());
    }

    @Test
    void seed_fails_fast_when_template_missing() {
        PlatformAgentSeeder broken = new PlatformAgentSeeder(agentDao, versionDao) {
            @Override
            protected String loadTemplate(String path) {
                throw new IllegalStateException("platform agent template missing: " + path);
            }
        };
        assertThrows(IllegalStateException.class, () -> broken.seed(100L, 7L));
        verify(agentDao, never()).insert(any());
    }

    @Test
    void bundled_templates_are_present_and_non_blank() {
        assertTrue(seeder.loadTemplate(PlatformAgentSeeder.AGENT_MD_PATH).contains("Chief of Staff"));
        assertTrue(seeder.loadTemplate(PlatformAgentSeeder.SOUL_MD_PATH).contains("Chief of Staff"));
    }
}
