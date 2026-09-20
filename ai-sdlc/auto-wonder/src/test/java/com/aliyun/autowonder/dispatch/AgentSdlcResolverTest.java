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

    /** 验收6：存在生效版本但其已解除 SDLC 引用(sdlcId 为空)时，返回 null，且不回退历史版本隐式恢复引用。 */
    @Test
    void onlineVersionReleasedSdlc_returnsNull_withoutHistoricalFallback() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);

        AgentDO a = new AgentDO();
        a.setId(10003L);
        a.setTenantId(10000L);
        a.setOnlineVersionId(10003L);
        when(agentDao.findById(10003L)).thenReturn(a);
        AgentVersionDO online = new AgentVersionDO();
        online.setId(10003L);
        online.setAgentId(10003L);
        online.setTenantId(10000L);
        online.setSdlcId(null);
        when(versionDao.findById(10003L)).thenReturn(online);

        AgentSdlcResolver r = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertNull(r.resolveSdlcId(10000L, 10003L));
        verify(versionDao, never()).listByAgent(anyLong());
    }

    /** 生效指针不可用（记录缺失/跨租户/指向他人数字人版本）时返回 null，同样不回退历史版本。 */
    @Test
    void onlineVersionPointerUnusable_returnsNull_withoutHistoricalFallback() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SdlcStepDao stepDao = mock(SdlcStepDao.class);

        AgentDO missing = new AgentDO();
        missing.setId(10004L);
        missing.setTenantId(10000L);
        missing.setOnlineVersionId(999L);
        when(agentDao.findById(10004L)).thenReturn(missing);
        when(versionDao.findById(999L)).thenReturn(null);

        AgentDO crossOnline = new AgentDO();
        crossOnline.setId(10005L);
        crossOnline.setTenantId(10000L);
        crossOnline.setOnlineVersionId(10005L);
        when(agentDao.findById(10005L)).thenReturn(crossOnline);
        AgentVersionDO crossVersion = new AgentVersionDO();
        crossVersion.setId(10005L);
        crossVersion.setAgentId(10005L);
        crossVersion.setTenantId(999L);
        crossVersion.setSdlcId(30009L);
        when(versionDao.findById(10005L)).thenReturn(crossVersion);

        // 生效指针指向他人数字人的版本：租户匹配(通过第 42 行)但 agentId 不匹配(触发第 43 行 false 分支)。
        // sdlcId 非空，确保返回 null 来自 agentId 守卫而非取到空值。
        AgentDO foreignPointer = new AgentDO();
        foreignPointer.setId(10006L);
        foreignPointer.setTenantId(10000L);
        foreignPointer.setOnlineVersionId(10099L);
        when(agentDao.findById(10006L)).thenReturn(foreignPointer);
        AgentVersionDO foreignVersion = new AgentVersionDO();
        foreignVersion.setId(10099L);
        foreignVersion.setAgentId(777L);
        foreignVersion.setTenantId(10000L);
        foreignVersion.setSdlcId(30010L);
        when(versionDao.findById(10099L)).thenReturn(foreignVersion);

        AgentSdlcResolver r = new AgentSdlcResolver(agentDao, versionDao, stepDao);

        assertNull(r.resolveSdlcId(10000L, 10004L));
        assertNull(r.resolveSdlcId(10000L, 10005L));
        assertNull(r.resolveSdlcId(10000L, 10006L));
        verify(versionDao, never()).listByAgent(anyLong());
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
