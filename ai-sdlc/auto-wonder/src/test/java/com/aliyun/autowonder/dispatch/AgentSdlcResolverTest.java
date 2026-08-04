package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSdlcResolverTest {

    @Test
    void resolvesOnlineVersionSdlc_andFirstStep() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);

        AgentDO a = new AgentDO();
        a.setId(10001L);
        a.setTenantId(10000L);
        a.setOnlineVersionId(10001L);
        when(agentDao.findById(10001L)).thenReturn(a);
        AgentVersionDO v = new AgentVersionDO();
        v.setId(10001L);
        v.setAgentId(10001L);
        v.setTenantId(10000L);
        v.setSdlcId(30002L);
        when(versionDao.findById(10001L)).thenReturn(v);
        SdlcStepDO s1 = step(1, 300021L);
        SdlcStepDO s2 = step(2, 300022L);
        when(stepDao.listBySdlc(30002L)).thenReturn(List.of(s2, s1));

        AgentSdlcResolver r = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertEquals(30002L, r.resolveSdlcId(10000L, 10001L));
        assertEquals(300021L, r.firstStep(10000L, 30002L).getId());
    }

    @Test
    void fallsBackToAnyVersionSdlc_whenNoOnlineVersion() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);

        AgentDO a = new AgentDO();
        a.setId(10002L);
        a.setTenantId(10000L);
        when(agentDao.findById(10002L)).thenReturn(a);
        AgentVersionDO v = new AgentVersionDO();
        v.setId(10002L);
        v.setAgentId(10002L);
        v.setTenantId(10000L);
        v.setSdlcId(30003L);
        when(versionDao.listByAgent(10002L)).thenReturn(List.of(v));

        AgentSdlcResolver r = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertEquals(30003L, r.resolveSdlcId(10000L, 10002L));
    }

    @Test
    void returnsNull_whenAgentMissingCrossTenantOrNoSdlc() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);

        when(agentDao.findById(1L)).thenReturn(null);
        AgentDO cross = new AgentDO();
        cross.setId(2L);
        cross.setTenantId(999L);
        when(agentDao.findById(2L)).thenReturn(cross);
        AgentDO noSdlc = new AgentDO();
        noSdlc.setId(3L);
        noSdlc.setTenantId(10000L);
        when(agentDao.findById(3L)).thenReturn(noSdlc);
        when(versionDao.listByAgent(3L)).thenReturn(List.of());

        AgentSdlcResolver r = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertNull(r.resolveSdlcId(10000L, 1L));
        assertNull(r.resolveSdlcId(10000L, 2L));
        assertNull(r.resolveSdlcId(10000L, 3L));
    }

    @Test
    void firstStep_returnsNull_whenNoStepsForTenant() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);
        when(stepDao.listBySdlc(40000L)).thenReturn(List.of(step(1, 1L, 999L)));

        AgentSdlcResolver r = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertNull(r.firstStep(10000L, 40000L));
    }

    @Test
    void resolveStep_acceptsUniquePartialNameButRejectsAmbiguousHint() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);
        SdlcStepDO analysis = step(1, 11L);
        analysis.setName("需求分析与评论");
        SdlcStepDO coding = step(2, 12L);
        coding.setName("编码实现");
        when(stepDao.listBySdlc(30002L)).thenReturn(List.of(analysis, coding));
        AgentSdlcResolver resolver = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertEquals(11L, resolver.resolveStep(10000L, 30002L, null, "需求分析").getId());

        SdlcStepDO reviewAnalysis = step(3, 13L);
        reviewAnalysis.setName("需求分析复核");
        when(stepDao.listBySdlc(30002L)).thenReturn(List.of(analysis, coding, reviewAnalysis));
        assertNull(resolver.resolveStep(10000L, 30002L, null, "需求分析"));
    }

    private static SdlcStepDO step(int order, long id) {
        return step(order, id, 10000L);
    }

    private static SdlcStepDO step(int order, long id, long tenantId) {
        SdlcStepDO s = new SdlcStepDO();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setStepOrder(order);
        return s;
    }
}
