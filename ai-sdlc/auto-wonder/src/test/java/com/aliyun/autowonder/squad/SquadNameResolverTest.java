package com.aliyun.autowonder.squad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SquadNameResolverTest {

    private SquadDao squadDao;
    private SquadMemberDao squadMemberDao;
    private SquadNameResolver resolver;

    @BeforeEach
    void setUp() {
        squadDao = mock(SquadDao.class);
        squadMemberDao = mock(SquadMemberDao.class);
        resolver = new SquadNameResolver(squadDao, squadMemberDao);
    }

    @Test
    void returnsNoLabelsWithoutAgents() {
        assertTrue(resolver.resolve(7L, null, 900L).isEmpty());
        assertTrue(resolver.resolve(7L, List.of(), 900L).isEmpty());
        verifyNoInteractions(squadMemberDao, squadDao);
    }

    @Test
    void toleratesMissingMembershipAndSquadRows() {
        when(squadMemberDao.listByAgentIds(eq(7L), any())).thenReturn(null);
        assertTrue(resolver.resolve(7L, List.of(41L), 900L).isEmpty());

        when(squadMemberDao.listByAgentIds(eq(7L), any())).thenReturn(List.of());
        when(squadDao.listByIds(any())).thenReturn(null);
        assertTrue(resolver.resolve(7L, List.of(41L), null).isEmpty());
    }

    @Test
    void skipsMalformedLinksAndUnusableSquadRows() {
        SquadMemberDO nullAgent = member(null, 900L);
        SquadMemberDO nullSquad = member(41L, null);
        when(squadMemberDao.listByAgentIds(eq(7L), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(null, nullAgent, nullSquad)));
        when(squadDao.listByIds(any())).thenReturn(Arrays.asList(
                null,
                squad(null, "ignored"),
                squad(901L, null),
                squad(902L, "   ")));

        assertTrue(resolver.resolve(7L, List.of(41L), null).isEmpty());
    }

    @Test
    void prefersCurrentSquadAndFallsBackToSmallestSquadId() {
        when(squadMemberDao.listByAgentIds(eq(7L), any())).thenReturn(List.of(
                member(41L, 850L),
                member(41L, 900L),
                member(42L, 902L),
                member(43L, 903L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(
                squad(850L, "旧小队"), squad(900L, "当前小队"), squad(902L, "其他小队")));

        Map<Long, SquadNameResolver.SquadLabel> labels =
                resolver.resolve(7L, List.of(41L, 42L, 43L, 44L), 900L);

        assertEquals(new SquadNameResolver.SquadLabel("当前小队", true), labels.get(41L));
        assertEquals(new SquadNameResolver.SquadLabel("其他小队", false), labels.get(42L));
        assertEquals(new SquadNameResolver.SquadLabel(null, false), labels.get(43L));
        assertFalse(labels.containsKey(44L));
    }

    @Test
    void ignoresNullAgentIdsFromCallers() {
        when(squadMemberDao.listByAgentIds(eq(7L), any())).thenReturn(List.of(member(41L, 900L)));
        when(squadDao.listByIds(any())).thenReturn(List.of(squad(900L, "当前小队")));

        Map<Long, SquadNameResolver.SquadLabel> labels =
                resolver.resolve(7L, new ArrayList<>(Arrays.asList(41L, null)), 900L);

        assertEquals(1, labels.size());
        assertEquals("当前小队", labels.get(41L).squadName());
        assertTrue(labels.get(41L).currentSquad());
        assertNull(labels.get(null));
    }

    private SquadMemberDO member(Long agentId, Long squadId) {
        SquadMemberDO member = new SquadMemberDO();
        member.setTenantId(7L);
        member.setAgentId(agentId);
        member.setSquadId(squadId);
        return member;
    }

    private SquadDO squad(Long id, String name) {
        SquadDO squad = new SquadDO();
        squad.setId(id);
        squad.setTenantId(7L);
        squad.setName(name);
        return squad;
    }
}
