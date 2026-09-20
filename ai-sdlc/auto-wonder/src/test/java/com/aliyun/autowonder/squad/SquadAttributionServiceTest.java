package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SquadAttributionServiceTest {

    private static final Long TENANT = 100L;

    SquadMemberDao squadMemberDao;
    SquadDao squadDao;
    AgentVersionDao agentVersionDao;
    SquadAttributionService service;

    @BeforeEach
    void setUp() {
        squadMemberDao = mock(SquadMemberDao.class);
        squadDao = mock(SquadDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        service = new SquadAttributionService(squadMemberDao, squadDao, agentVersionDao);
    }

    private SquadMemberDO member(long agentId, long squadId) {
        SquadMemberDO m = new SquadMemberDO();
        m.setTenantId(TENANT);
        m.setAgentId(agentId);
        m.setSquadId(squadId);
        return m;
    }

    private SquadDO squad(long id, String name) {
        SquadDO s = new SquadDO();
        s.setId(id);
        s.setName(name);
        return s;
    }

    private AgentVersionDO onlineVersion(long agentId, long sdlcId) {
        AgentVersionDO v = new AgentVersionDO();
        v.setAgentId(agentId);
        v.setSdlcId(sdlcId);
        return v;
    }

    private AgentVO agent(long id) {
        AgentVO vo = new AgentVO();
        vo.setId(id);
        return vo;
    }

    private SdlcVO sdlc(long id) {
        SdlcVO vo = new SdlcVO();
        vo.setId(id);
        return vo;
    }

    private ExecutorVO executor(long id, Long agentId) {
        ExecutorVO vo = new ExecutorVO();
        vo.setId(id);
        vo.setAgentId(agentId);
        return vo;
    }

    @Test
    void fillAgentSquads_lists_every_squad_with_aligned_names() {
        AgentVO first = agent(1L);
        AgentVO second = agent(2L);
        when(squadMemberDao.listByAgentIds(eq(TENANT), eq(Set.of(1L, 2L))))
                .thenReturn(List.of(member(1L, 7L), member(1L, 8L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(7L, "前端小队"), squad(8L, "测试小队")));

        service.fillAgentSquads(TENANT, new ArrayList<>(List.of(first, second)));

        assertEquals(List.of(7L, 8L), first.getSquadIds());
        assertEquals(List.of("前端小队", "测试小队"), first.getSquadNames());
        assertEquals(List.of(), second.getSquadIds());
        assertEquals(List.of(), second.getSquadNames());
    }

    @Test
    void fillAgentSquads_dedupes_repeated_membership_rows() {
        AgentVO vo = agent(1L);
        when(squadMemberDao.listByAgentIds(eq(TENANT), any()))
                .thenReturn(List.of(member(1L, 7L), member(1L, 7L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(7L, "前端小队")));

        service.fillAgentSquads(TENANT, new ArrayList<>(List.of(vo)));

        assertEquals(List.of(7L), vo.getSquadIds());
        assertEquals(List.of("前端小队"), vo.getSquadNames());
    }

    @Test
    void fillAgentSquads_drops_a_squad_whose_name_lookup_missed() {
        AgentVO vo = agent(1L);
        when(squadMemberDao.listByAgentIds(eq(TENANT), any()))
                .thenReturn(List.of(member(1L, 7L), member(1L, 9L)));
        // Squad 9 was deleted after the membership row was written, so listByIds no longer returns it.
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(7L, "前端小队")));

        service.fillAgentSquads(TENANT, new ArrayList<>(List.of(vo)));

        assertEquals(List.of(7L), vo.getSquadIds());
        assertEquals(List.of("前端小队"), vo.getSquadNames());
    }

    @Test
    void fillAgentSquads_batches_the_membership_lookup_once_per_page() {
        when(squadMemberDao.listByAgentIds(eq(TENANT), any()))
                .thenReturn(List.of(member(1L, 7L), member(2L, 7L), member(3L, 7L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(7L, "前端小队")));

        service.fillAgentSquads(TENANT, new ArrayList<>(List.of(agent(1L), agent(2L), agent(3L))));

        verify(squadMemberDao, times(1)).listByAgentIds(eq(TENANT), any());
        verify(squadDao, times(1)).listByIds(any());
    }

    @Test
    void fillAgentSquads_without_a_workspace_touches_no_dao() {
        AgentVO vo = agent(1L);

        service.fillAgentSquads(null, new ArrayList<>(List.of(vo)));

        assertEquals(List.of(), vo.getSquadIds());
        assertEquals(List.of(), vo.getSquadNames());
        verifyNoInteractions(squadMemberDao, squadDao, agentVersionDao);
    }

    @Test
    void fillAgentSquads_on_an_empty_page_touches_no_dao() {
        service.fillAgentSquads(TENANT, new ArrayList<>());
        service.fillAgentSquads(TENANT, null);

        verifyNoInteractions(squadMemberDao, squadDao, agentVersionDao);
    }

    @Test
    void fillSdlcSquads_merges_the_squads_of_every_agent_binding_the_flow() {
        SdlcVO shared = sdlc(2L);
        SdlcVO unbound = sdlc(3L);
        when(agentVersionDao.listOnlineBySdlcIds(eq(TENANT), eq(Set.of(2L, 3L))))
                .thenReturn(List.of(onlineVersion(1L, 2L), onlineVersion(2L, 2L)));
        when(squadMemberDao.listByAgentIds(eq(TENANT), eq(Set.of(1L, 2L))))
                .thenReturn(List.of(member(1L, 7L), member(2L, 8L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(7L, "前端小队"), squad(8L, "测试小队")));

        service.fillSdlcSquads(TENANT, new ArrayList<>(List.of(shared, unbound)));

        assertEquals(List.of(7L, 8L), shared.getSquadIds());
        assertEquals(List.of("前端小队", "测试小队"), shared.getSquadNames());
        assertEquals(List.of(), unbound.getSquadIds());
    }

    @Test
    void fillSdlcSquads_skips_the_membership_lookup_when_no_online_version_binds_the_flow() {
        SdlcVO vo = sdlc(2L);
        when(agentVersionDao.listOnlineBySdlcIds(eq(TENANT), any())).thenReturn(List.of());

        service.fillSdlcSquads(TENANT, new ArrayList<>(List.of(vo)));

        assertEquals(List.of(), vo.getSquadIds());
        assertEquals(List.of(), vo.getSquadNames());
        verify(squadMemberDao, never()).listByAgentIds(any(), any());
    }

    @Test
    void fillSdlcSquads_ignores_a_version_that_lost_its_agent() {
        SdlcVO vo = sdlc(2L);
        AgentVersionDO orphan = new AgentVersionDO();
        orphan.setSdlcId(2L);
        when(agentVersionDao.listOnlineBySdlcIds(eq(TENANT), any())).thenReturn(List.of(orphan));

        service.fillSdlcSquads(TENANT, new ArrayList<>(List.of(vo)));

        assertEquals(List.of(), vo.getSquadIds());
        verify(squadMemberDao, never()).listByAgentIds(any(), any());
    }

    @Test
    void fillSdlcSquads_on_an_empty_list_touches_no_dao() {
        service.fillSdlcSquads(TENANT, new ArrayList<>());
        service.fillSdlcSquads(TENANT, null);

        verifyNoInteractions(squadMemberDao, squadDao, agentVersionDao);
    }

    @Test
    void fillSdlcSquads_without_a_workspace_touches_no_dao() {
        SdlcVO vo = sdlc(2L);

        service.fillSdlcSquads(null, new ArrayList<>(List.of(vo)));

        assertEquals(List.of(), vo.getSquadIds());
        assertEquals(List.of(), vo.getSquadNames());
        verifyNoInteractions(squadMemberDao, squadDao, agentVersionDao);
    }

    @Test
    void fillSdlcSquads_without_any_resolvable_id_touches_no_dao() {
        // A row that lost its id cannot key the lookup, so no query is worth issuing.
        service.fillSdlcSquads(TENANT, new ArrayList<>(List.of(new SdlcVO())));

        verifyNoInteractions(squadMemberDao, squadDao, agentVersionDao);
    }

    @Test
    void fillExecutorSquads_follows_the_owning_agent() {
        ExecutorVO owned = executor(9L, 1L);
        ExecutorVO orphan = executor(10L, null);
        when(squadMemberDao.listByAgentIds(eq(TENANT), eq(Set.of(1L))))
                .thenReturn(List.of(member(1L, 7L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(7L, "前端小队")));

        service.fillExecutorSquads(TENANT, new ArrayList<>(List.of(owned, orphan)));

        assertEquals(List.of(7L), owned.getSquadIds());
        assertEquals(List.of("前端小队"), owned.getSquadNames());
        assertEquals(List.of(), orphan.getSquadIds());
        assertEquals(List.of(), orphan.getSquadNames());
    }

    @Test
    void fillExecutorSquads_on_an_empty_list_touches_no_dao() {
        service.fillExecutorSquads(TENANT, List.of());

        verifyNoInteractions(squadMemberDao, squadDao, agentVersionDao);
    }

    @Test
    void attribution_never_emits_a_null_squad_reference() {
        AgentVO vo = agent(1L);
        SquadMemberDO partial = new SquadMemberDO();
        partial.setTenantId(TENANT);
        partial.setAgentId(1L);
        when(squadMemberDao.listByAgentIds(eq(TENANT), any())).thenReturn(List.of(partial));

        service.fillAgentSquads(TENANT, new ArrayList<>(List.of(vo)));

        assertTrue(vo.getSquadIds().isEmpty());
        assertTrue(vo.getSquadNames().isEmpty());
        verify(squadDao, never()).listByIds(any());
    }
}
