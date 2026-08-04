package com.aliyun.autowonder.template;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.template.dto.ApplyResultVO;
import com.aliyun.autowonder.template.dto.SquadTemplateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SquadTemplateServiceTest {

    SquadTemplateDao templateDao;
    SquadDao squadDao;
    SquadMemberDao squadMemberDao;
    AgentDao agentDao;
    AgentVersionDao agentVersionDao;
    AgentRepoPermDao agentRepoPermDao;
    SdlcDao sdlcDao;
    SdlcStepDao sdlcStepDao;
    RepoDao repoDao;
    SquadTemplateService service;

    @BeforeEach
    void setUp() {
        templateDao = mock(SquadTemplateDao.class);
        squadDao = mock(SquadDao.class);
        squadMemberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        agentRepoPermDao = mock(AgentRepoPermDao.class);
        sdlcDao = mock(SdlcDao.class);
        sdlcStepDao = mock(SdlcStepDao.class);
        repoDao = mock(RepoDao.class);
        service = new SquadTemplateService(templateDao, squadDao, squadMemberDao,
                agentDao, agentVersionDao, agentRepoPermDao, sdlcDao, sdlcStepDao, repoDao);
    }

    @Test
    void list_returns_templates_for_tenant() {
        SquadTemplateDO t1 = new SquadTemplateDO();
        t1.setId(1L);
        t1.setName("独立开发者");
        t1.setDescription("一人全栈");
        t1.setSquadSize(1);
        t1.setIcon("solo");
        t1.setTags("推荐,快速");
        t1.setTenantId(null);

        when(templateDao.listActive(100L)).thenReturn(List.of(t1));

        List<SquadTemplateVO> result = service.list(100L);
        assertEquals(1, result.size());
        assertEquals("独立开发者", result.get(0).getName());
        assertEquals(1, result.get(0).getSquadSize());
        assertEquals(List.of("推荐", "快速"), result.get(0).getTags());
        assertTrue(result.get(0).isSystem());
    }

    @Test
    void list_handles_null_tags() {
        SquadTemplateDO t = new SquadTemplateDO();
        t.setId(2L);
        t.setName("测试");
        t.setDescription("desc");
        t.setSquadSize(2);
        t.setTags(null);
        t.setTenantId(50L);

        when(templateDao.listActive(50L)).thenReturn(List.of(t));

        List<SquadTemplateVO> result = service.list(50L);
        assertEquals(List.of(), result.get(0).getTags());
        assertFalse(result.get(0).isSystem());
    }

    @Test
    void apply_throws_when_template_not_found() {
        when(templateDao.findById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.apply(999L, 100L, 7L));
        assertEquals("15010", ex.getCode());
    }

    @Test
    void apply_creates_squad_and_agents() {
        SquadTemplateDO template = new SquadTemplateDO();
        template.setId(1L);
        template.setName("独立开发者");
        template.setContentJson("{\"squad\":{\"name\":\"测试小队\",\"description\":\"描述\"},"
                + "\"agents\":[{\"name\":\"Dev\",\"roleCode\":\"FS_DEV\",\"roleName\":\"全栈开发\","
                + "\"businessBackground\":\"\",\"responsibilities\":\"编码\","
                + "\"sdlc\":{\"name\":\"DevSDLC\",\"description\":\"开发流程\","
                + "\"steps\":[{\"order\":1,\"name\":\"编码\",\"kind\":\"WORK\",\"instruction\":\"写代码\"}]}}]}");

        when(templateDao.findById(1L)).thenReturn(template);
        when(repoDao.list(100L, 0, 200)).thenReturn(List.of(makeRepo(10L), makeRepo(20L)));

        doAnswer(inv -> {
            SquadDO s = inv.getArgument(0);
            s.setId(500L);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));

        doAnswer(inv -> {
            SdlcDO s = inv.getArgument(0);
            s.setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));

        doAnswer(inv -> {
            SdlcStepDO s = inv.getArgument(0);
            s.setId(700L);
            return null;
        }).when(sdlcStepDao).insert(any(SdlcStepDO.class));

        doAnswer(inv -> {
            AgentDO a = inv.getArgument(0);
            a.setId(800L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));

        doAnswer(inv -> {
            AgentVersionDO v = inv.getArgument(0);
            v.setId(900L);
            return null;
        }).when(agentVersionDao).insert(any(AgentVersionDO.class));

        ApplyResultVO result = service.apply(1L, 100L, 7L);

        assertEquals(500L, result.getSquadId());
        assertEquals(1, result.getAgents().size());
        assertEquals(800L, result.getAgents().get(0).getAgentId());
        assertEquals("全栈开发", result.getAgents().get(0).getRoleName());
        assertEquals("FS_DEV", result.getAgents().get(0).getRoleCode());

        verify(squadDao).insert(any(SquadDO.class));
        verify(sdlcDao).insert(any(SdlcDO.class));
        verify(sdlcStepDao).insert(any(SdlcStepDO.class));
        verify(agentDao).insert(any(AgentDO.class));
        verify(agentVersionDao).insert(any(AgentVersionDO.class));
        verify(squadMemberDao).insert(any());
        verify(agentRepoPermDao, times(2)).insert(any(AgentRepoPermDO.class));
        verify(sdlcDao).updateStatus(eq(600L), eq(100L), eq("ENABLED"), eq(700L), eq(0), eq(7L));
        verify(agentDao).updateStatus(eq(800L), eq(100L), eq("ONLINE"), eq(900L), isNull(), eq(1), eq(0), eq(7L));
    }

    private RepoDO makeRepo(Long id) {
        RepoDO repo = new RepoDO();
        repo.setId(id);
        repo.setName("repo-" + id);
        return repo;
    }
}
