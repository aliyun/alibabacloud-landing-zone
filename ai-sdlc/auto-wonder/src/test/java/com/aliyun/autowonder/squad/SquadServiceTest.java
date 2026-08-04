package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.dto.CreateSquadRequest;
import com.aliyun.autowonder.squad.dto.SquadMemberVO;
import com.aliyun.autowonder.squad.dto.SquadVO;
import com.aliyun.autowonder.squad.dto.UpdateSquadRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SquadServiceTest {

    SquadDao squadDao;
    SquadMemberDao memberDao;
    AgentDao agentDao;
    AgentVersionDao agentVersionDao;
    SdlcDao sdlcDao;
    SdlcStepDao sdlcStepDao;
    ExecutorDao executorDao;
    ExecutorRegistry executorRegistry;
    SquadService service;

    @BeforeEach
    void setUp() {
        squadDao = mock(SquadDao.class);
        memberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        sdlcDao = mock(SdlcDao.class);
        sdlcStepDao = mock(SdlcStepDao.class);
        executorDao = mock(ExecutorDao.class);
        executorRegistry = mock(ExecutorRegistry.class);
        service = new SquadService(squadDao, memberDao, agentDao, agentVersionDao, sdlcDao, sdlcStepDao,
                executorDao, executorRegistry);
    }

    @Test
    void create_inserts_squad() {
        CreateSquadRequest req = new CreateSquadRequest();
        req.setName("开发小队");
        req.setDescription("负责核心功能");

        SquadVO vo = service.create(req, 100L, 7L);
        assertEquals("开发小队", vo.getName());
        verify(squadDao).insert(argThat((SquadDO s) ->
                s.getTenantId() == 100L && "开发小队".equals(s.getName())));
    }

    @Test
    void create_blank_name_throws() {
        CreateSquadRequest req = new CreateSquadRequest();
        req.setName("");
        BizException ex = assertThrows(BizException.class, () -> service.create(req, 100L, 7L));
        assertEquals("15002", ex.getCode());
    }

    @Test
    void get_not_found_throws() {
        when(squadDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(9L));
        assertEquals("15001", ex.getCode());
    }

    @Test
    void get_returns_with_members() {
        SquadDO sq = new SquadDO();
        sq.setId(10L);
        sq.setName("test");
        sq.setVersion(0);
        when(squadDao.findById(10L)).thenReturn(sq);
        SquadMemberDO m1 = new SquadMemberDO();
        m1.setAgentId(1L);
        SquadMemberDO m2 = new SquadMemberDO();
        m2.setAgentId(2L);
        when(memberDao.listBySquad(10L)).thenReturn(List.of(m1, m2));

        SquadVO vo = service.get(10L);
        assertEquals(2, vo.getMemberAgentIds().size());
        assertEquals(2, vo.getMemberCount());
    }

    @Test
    void list_returns_member_counts() {
        SquadDO sq = new SquadDO();
        sq.setId(10L);
        sq.setName("test");
        sq.setVersion(0);
        when(squadDao.list(0, 20)).thenReturn(List.of(sq));
        SquadMemberDO m1 = new SquadMemberDO();
        m1.setAgentId(1L);
        SquadMemberDO m2 = new SquadMemberDO();
        m2.setAgentId(2L);
        when(memberDao.listBySquad(10L)).thenReturn(List.of(m1, m2));

        List<SquadVO> result = service.list(1, 20);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getMemberCount());
        assertNull(result.get(0).getMemberAgentIds());
    }

    @Test
    void list_returns_card_summary_counts() {
        SquadDO squad = new SquadDO();
        squad.setId(10L);
        squad.setTenantId(100L);
        squad.setName("交付小队");
        squad.setVersion(0);
        when(squadDao.list(0, 20)).thenReturn(List.of(squad));

        SquadMemberDO member1 = new SquadMemberDO();
        member1.setAgentId(1L);
        SquadMemberDO member2 = new SquadMemberDO();
        member2.setAgentId(2L);
        when(memberDao.listBySquad(10L)).thenReturn(List.of(member1, member2));
        when(memberDao.countBySquad(10L)).thenReturn(2);

        AgentDO agent1 = new AgentDO();
        agent1.setId(1L);
        agent1.setTenantId(100L);
        agent1.setOnlineVersionId(101L);
        AgentDO agent2 = new AgentDO();
        agent2.setId(2L);
        agent2.setTenantId(100L);
        agent2.setOnlineVersionId(102L);
        when(agentDao.listByIds(100L, List.of(1L, 2L))).thenReturn(List.of(agent1, agent2));

        AgentVersionDO version1 = new AgentVersionDO();
        version1.setId(101L);
        version1.setRoleCode("DEV");
        version1.setSdlcId(201L);
        AgentVersionDO version2 = new AgentVersionDO();
        version2.setId(102L);
        version2.setRoleCode("QA");
        version2.setSdlcId(201L);
        when(agentVersionDao.listByIds(eq(100L), anySet())).thenReturn(List.of(version1, version2));

        ExecutorDO executor1 = new ExecutorDO();
        executor1.setId(301L);
        executor1.setAgentId(1L);
        ExecutorDO executor2 = new ExecutorDO();
        executor2.setId(302L);
        executor2.setAgentId(2L);
        when(executorDao.listByAgent(100L, 1L)).thenReturn(List.of(executor1));
        when(executorDao.listByAgent(100L, 2L)).thenReturn(List.of(executor2));
        when(executorRegistry.isOnline(301L)).thenReturn(true);
        when(executorRegistry.isOnline(302L)).thenReturn(false);

        List<SquadVO> result = service.list(1, 20);

        assertEquals(1, result.size());
        SquadVO vo = result.get(0);
        assertEquals(2, vo.getMemberCount());
        assertEquals(2, vo.getRoleCount());
        assertEquals(1, vo.getSdlcCount());
        assertEquals(2, vo.getExecutorTotalCount());
        assertEquals(1, vo.getExecutorOnlineCount());
        assertNull(vo.getMemberAgentIds());
    }

    @Test
    void addMembers_skips_duplicates() {
        SquadDO sq = new SquadDO();
        sq.setId(10L);
        sq.setVersion(0);
        when(squadDao.findById(10L)).thenReturn(sq);
        SquadMemberDO existing = new SquadMemberDO();
        existing.setAgentId(1L);
        when(memberDao.findBySquadAndAgent(10L, 1L)).thenReturn(existing);
        when(memberDao.findBySquadAndAgent(10L, 2L)).thenReturn(null);

        service.addMembers(10L, List.of(1L, 2L), 100L);
        verify(memberDao, times(1)).insert(argThat((SquadMemberDO m) -> m.getAgentId() == 2L));
        verify(memberDao, never()).insert(argThat((SquadMemberDO m) -> m.getAgentId() == 1L));
    }

    @Test
    void listMembers_returns_enriched_members() {
        SquadDO sq = new SquadDO();
        sq.setId(10L);
        when(squadDao.findById(10L)).thenReturn(sq);
        SquadMemberDO m1 = new SquadMemberDO();
        m1.setAgentId(1L);
        SquadMemberDO m2 = new SquadMemberDO();
        m2.setAgentId(2L);
        when(memberDao.listBySquad(10L)).thenReturn(List.of(m1, m2));
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setName("测试Agent");
        agent.setOnlineVersionId(100L);
        AgentDO agent2 = new AgentDO();
        agent2.setId(2L);
        agent2.setName("测试Agent2");
        agent2.setOnlineVersionId(101L);
        when(agentDao.listByIds(100L, List.of(1L, 2L))).thenReturn(List.of(agent, agent2));
        AgentVersionDO ver = new AgentVersionDO();
        ver.setId(100L);
        ver.setRoleName("前端开发工程师");
        ver.setRoleCode("DEVELOPER");
        ver.setResponsibilities("负责前端页面实现和接口联调");
        ver.setSdlcId(200L);
        AgentVersionDO ver2 = new AgentVersionDO();
        ver2.setId(101L);
        ver2.setRoleName("测试工程师");
        ver2.setRoleCode("QA");
        ver2.setResponsibilities("负责测试验证");
        ver2.setSdlcId(200L);
        when(agentVersionDao.listByIds(eq(100L), anySet())).thenReturn(List.of(ver, ver2));
        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(200L);
        sdlc.setName("前端标准流");
        when(sdlcDao.findById(200L)).thenReturn(sdlc);
        SdlcStepDO step1 = new SdlcStepDO();
        step1.setId(201L);
        step1.setSdlcId(200L);
        step1.setStepOrder(1);
        step1.setName("需求澄清");
        step1.setHandlerType("AGENT");
        step1.setHandlerRoleRef("PM_PRODUCT");
        SdlcStepDO step2 = new SdlcStepDO();
        step2.setId(202L);
        step2.setSdlcId(200L);
        step2.setStepOrder(2);
        step2.setName("开发实现");
        step2.setHandlerType("AGENT");
        step2.setHandlerRoleRef("DEVELOPER");
        when(sdlcStepDao.listBySdlc(200L)).thenReturn(List.of(step2, step1));

        List<SquadMemberVO> result = service.listMembers(10L, 100L);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getAgentId());
        assertEquals("测试Agent", result.get(0).getAgentName());
        assertEquals("DEVELOPER", result.get(0).getRoleCode());
        assertEquals("前端开发工程师", result.get(0).getRoleName());
        assertEquals("负责前端页面实现和接口联调", result.get(0).getResponsibilities());
        assertEquals(200L, result.get(0).getSdlcId());
        assertEquals("前端标准流", result.get(0).getSdlcName());
        assertEquals(2, result.get(0).getSdlcSteps().size());
        assertEquals("需求澄清", result.get(0).getSdlcSteps().get(0).getName());
        assertEquals("开发实现", result.get(0).getSdlcSteps().get(1).getName());
        assertEquals("前端标准流", result.get(1).getSdlcName());
        verify(sdlcDao, times(1)).findById(200L);
        verify(sdlcStepDao, times(1)).listBySdlc(200L);
    }

    @Test
    void delete_soft_deletes_and_clears_members() {
        SquadDO sq = new SquadDO();
        sq.setId(10L);
        sq.setVersion(0);
        when(squadDao.findById(10L)).thenReturn(sq);
        when(squadDao.softDelete(10L, 100L, 0, 7L)).thenReturn(1);

        service.delete(10L, 100L, 7L);
        verify(squadDao).softDelete(10L, 100L, 0, 7L);
        verify(memberDao).deleteBySquad(10L, 100L);
    }
}
