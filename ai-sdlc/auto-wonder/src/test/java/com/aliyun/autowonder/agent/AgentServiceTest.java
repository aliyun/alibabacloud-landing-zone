package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;

import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.agent.dto.UpdateAgentRequest;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {

    AgentDao agentDao;
    AgentVersionDao versionDao;
    AgentRepoPermDao repoPermDao;
    AgentSkillDao skillDao;
    AgentMemoryRefDao memoryRefDao;
    WorkspaceDao workspaceDao;
    ExecutorDao executorDao;
    ExecutorRegistry executorRegistry;
    AgentService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        repoPermDao = mock(AgentRepoPermDao.class);
        skillDao = mock(AgentSkillDao.class);
        memoryRefDao = mock(AgentMemoryRefDao.class);
        workspaceDao = mock(WorkspaceDao.class);
        executorDao = mock(ExecutorDao.class);
        executorRegistry = mock(ExecutorRegistry.class);
        service = new AgentService(agentDao, versionDao, repoPermDao, skillDao, memoryRefDao, workspaceDao,
                executorDao, executorRegistry);
    }

    @Test
    void create_builds_agent_and_draft_version() {
        doAnswer(inv -> { ((AgentDO) inv.getArgument(0)).setId(1L); return null; })
                .when(agentDao).insert(any());
        doAnswer(inv -> { ((AgentVersionDO) inv.getArgument(0)).setId(2L); return null; })
                .when(versionDao).insert(any());

        CreateAgentRequest req = new CreateAgentRequest();
        req.setName("架构师");
        req.setRoleName("architect");

        AgentVO vo = service.create(req, 100L, 7L);

        assertNotNull(vo.getId());
        assertEquals("架构师", vo.getName());
        assertEquals("DRAFT", vo.getStatus());
        assertEquals(Integer.valueOf(1), vo.getLatestVersionNo());
        verify(agentDao).insert(argThat((AgentDO a) ->
                a.getTenantId() == 100L && "DRAFT".equals(a.getStatus())
                        && a.getLatestVersionNo() == 1));
        verify(versionDao).insert(argThat((AgentVersionDO v) ->
                v.getVersionNo() == 1 && "DRAFT".equals(v.getStatus())
                        && "architect".equals(v.getRoleName())));
    }

    @Test
    void create_blank_name_throws() {
        CreateAgentRequest req = new CreateAgentRequest();
        req.setName("  ");
        BizException ex = assertThrows(BizException.class, () -> service.create(req, 100L, 7L));
        assertEquals("14008", ex.getCode());
    }

    @Test
    void get_not_found_throws() {
        when(agentDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(9L));
        assertEquals("14001", ex.getCode());
    }

    @Test
    void get_includes_online_version_identity_fields() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("项目管理员");
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(10L);
        agent.setLatestVersionNo(3);
        when(agentDao.findById(1L)).thenReturn(agent);

        AgentVersionDO version = new AgentVersionDO();
        version.setId(10L);
        version.setRoleName("项目负责人");
        version.setRoleCode("PROJECT_MANAGER");
        version.setBusinessBackground("作为钉钉群里的项目接口人参与沟通。");
        version.setResponsibilities("推动工单流转、任务指派和进度汇报。");
        when(versionDao.findById(10L)).thenReturn(version);

        AgentVO vo = service.get(1L);

        assertEquals("项目负责人", vo.getRoleName());
        assertEquals("PROJECT_MANAGER", vo.getRoleCode());
        assertEquals("作为钉钉群里的项目接口人参与沟通。", vo.getBusinessBackground());
        assertEquals("推动工单流转、任务指派和进度汇报。", vo.getResponsibilities());
    }

    @Test
    void list_returns_vos() {
        AgentDO a = new AgentDO();
        a.setId(1L);
        a.setName("test");
        a.setStatus("DRAFT");
        a.setLatestVersionNo(1);
        when(agentDao.list(eq(100L), eq("DRAFT"), eq(0), eq(20))).thenReturn(List.of(a));
        List<AgentVO> vos = service.list(100L, "DRAFT", 1, 20);
        assertEquals(1, vos.size());
        assertEquals("test", vos.get(0).getName());
    }

    @Test
    void list_returns_card_summary_fields() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("前端Alpha");
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(10L);
        agent.setLatestVersionNo(3);
        when(agentDao.list(eq(100L), isNull(), eq(0), eq(20))).thenReturn(List.of(agent));

        AgentVersionDO version = new AgentVersionDO();
        version.setId(10L);
        version.setRoleName("前端开发工程师");
        version.setRoleCode("FRONTEND_DEV");
        when(versionDao.findById(10L)).thenReturn(version);

        AgentRepoPermDO repoPerm = new AgentRepoPermDO();
        repoPerm.setRepoId(1000L);
        when(repoPermDao.listByVersion(10L)).thenReturn(List.of(repoPerm));
        AgentSkillDO skill = new AgentSkillDO();
        skill.setSkillId(2000L);
        when(skillDao.listByVersion(10L)).thenReturn(List.of(skill));
        AgentMemoryRefDO memoryRef = new AgentMemoryRefDO();
        memoryRef.setMemoryId(3000L);
        when(memoryRefDao.listByVersion(10L)).thenReturn(List.of(memoryRef));

        ExecutorDO onlineExecutor = new ExecutorDO();
        onlineExecutor.setId(91L);
        onlineExecutor.setAgentId(1L);
        ExecutorDO offlineExecutor = new ExecutorDO();
        offlineExecutor.setId(92L);
        offlineExecutor.setAgentId(1L);
        when(executorDao.listByAgent(100L, 1L)).thenReturn(List.of(onlineExecutor, offlineExecutor));
        when(executorRegistry.isOnline(91L)).thenReturn(true);
        when(executorRegistry.isOnline(92L)).thenReturn(false);

        List<AgentVO> vos = service.list(100L, null, 1, 20);

        assertEquals(1, vos.size());
        AgentVO vo = vos.get(0);
        assertEquals("前端开发工程师", vo.getRoleName());
        assertEquals("FRONTEND_DEV", vo.getRoleCode());
        assertEquals(1, vo.getRepoPermCount());
        assertEquals(1, vo.getSkillCount());
        assertEquals(1, vo.getMemoryCount());
        assertEquals(2, vo.getExecutorTotalCount());
        assertEquals(1, vo.getExecutorOnlineCount());
    }

    @Test
    void getVersion_includes_config_relations() {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(10L);
        version.setAgentId(1L);
        version.setVersionNo(1);
        version.setStatus("DRAFT");
        when(versionDao.findByAgentAndNo(1L, 1)).thenReturn(version);

        AgentRepoPermDO repoPerm = new AgentRepoPermDO();
        repoPerm.setRepoId(11L);
        repoPerm.setPermLevel("WRITE");
        when(repoPermDao.listByVersion(10L)).thenReturn(List.of(repoPerm));

        AgentSkillDO skill = new AgentSkillDO();
        skill.setSkillId(22L);
        when(skillDao.listByVersion(10L)).thenReturn(List.of(skill));

        AgentMemoryRefDO memoryRef = new AgentMemoryRefDO();
        memoryRef.setMemoryId(33L);
        memoryRef.setSource("ORG");
        when(memoryRefDao.listByVersion(10L)).thenReturn(List.of(memoryRef));

        AgentVersionVO vo = service.getVersion(1L, 1);

        assertEquals(11L, vo.getRepoPerms().get(0).getRepoId());
        assertEquals("WRITE", vo.getRepoPerms().get(0).getPermLevel());
        assertEquals(22L, vo.getSkills().get(0).getSkillId());
        assertEquals(33L, vo.getMemoryRefs().get(0).getMemoryId());
        assertEquals("ORG", vo.getMemoryRefs().get(0).getSource());
    }

    @Test
    void getVersion_rejects_agent_from_another_tenant() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(200L);
        when(agentDao.findById(1L)).thenReturn(agent);

        BizException ex = assertThrows(BizException.class,
                () -> service.getVersion(1L, 1, 100L));

        assertEquals("14001", ex.getCode());
        verify(versionDao, never()).findByAgentAndNo(anyLong(), anyInt());
    }

    @Test
    void editConfig_rejects_agent_from_another_tenant() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(200L);
        when(agentDao.findById(1L)).thenReturn(agent);

        BizException ex = assertThrows(BizException.class,
                () -> service.editConfig(1L, new UpdateConfigRequest(), 100L, 7L));

        assertEquals("14001", ex.getCode());
        verify(versionDao, never()).updateConfig(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyLong());
    }

    @Test
    void listMemoryRefs_uses_online_version_when_present() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setOnlineVersionId(50L);
        agent.setEditingVersionId(60L);
        when(agentDao.findById(1L)).thenReturn(agent);
        AgentMemoryRefDO r = new AgentMemoryRefDO();
        r.setMemoryId(900L);
        r.setSource("DIRECT");
        when(memoryRefDao.listByVersion(50L)).thenReturn(java.util.List.of(r));

        var refs = service.listMemoryRefs(1L);

        assertEquals(1, refs.size());
        assertEquals(900L, refs.get(0).getMemoryId());
        assertEquals("DIRECT", refs.get(0).getSource());
        verify(memoryRefDao).listByVersion(50L);
    }

    @Test
    void listMemoryRefs_falls_back_to_editing_version() {
        AgentDO agent = new AgentDO();
        agent.setId(2L);
        agent.setOnlineVersionId(null);
        agent.setEditingVersionId(60L);
        when(agentDao.findById(2L)).thenReturn(agent);
        when(memoryRefDao.listByVersion(60L)).thenReturn(java.util.List.of());

        var refs = service.listMemoryRefs(2L);

        assertTrue(refs.isEmpty());
        verify(memoryRefDao).listByVersion(60L);
    }

    @Test
    void listMemoryRefs_empty_when_no_version() {
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        when(agentDao.findById(3L)).thenReturn(agent);

        assertTrue(service.listMemoryRefs(3L).isEmpty());
        verify(memoryRefDao, never()).listByVersion(any());
    }

    @Test
    void listMemoryRefs_agent_not_found_throws() {
        when(agentDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.listMemoryRefs(9L));
        assertEquals("14001", ex.getCode());
    }

    @Test
    void delete_cascades_versions_and_soft_deletes_agent() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setStatus("DRAFT");
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        AgentVersionDO v1 = new AgentVersionDO();
        v1.setId(10L);
        AgentVersionDO v2 = new AgentVersionDO();
        v2.setId(11L);
        when(versionDao.listByAgent(1L)).thenReturn(List.of(v1, v2));
        when(agentDao.softDelete(1L, 100L, 2, 7L)).thenReturn(1);

        service.delete(1L, 100L, 7L);

        verify(skillDao).deleteByVersion(10L);
        verify(skillDao).deleteByVersion(11L);
        verify(repoPermDao).deleteByVersion(10L);
        verify(repoPermDao).deleteByVersion(11L);
        verify(memoryRefDao).deleteByVersion(10L);
        verify(memoryRefDao).deleteByVersion(11L);
        verify(versionDao).softDeleteByAgent(1L, 100L, 7L);
        verify(agentDao).softDelete(1L, 100L, 2, 7L);
    }

    @Test
    void delete_online_agent_throws_and_does_not_mutate() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setStatus("ONLINE");
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 100L, 7L));
        assertEquals("14009", ex.getCode());

        verify(versionDao, never()).softDeleteByAgent(anyLong(), anyLong(), anyLong());
        verify(agentDao, never()).softDelete(anyLong(), anyLong(), any(), anyLong());
        verify(skillDao, never()).deleteByVersion(anyLong());
    }

    @Test
    void delete_not_found_throws() {
        when(agentDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 100L, 7L));
        assertEquals("14001", ex.getCode());
    }

    @Test
    void delete_tenant_mismatch_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(999L);
        agent.setStatus("DRAFT");
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 100L, 7L));
        assertEquals("14001", ex.getCode());
        verify(agentDao, never()).softDelete(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void delete_version_conflict_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setStatus("PENDING_REVIEW");
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);
        when(versionDao.listByAgent(1L)).thenReturn(List.of());
        when(agentDao.softDelete(1L, 100L, 2, 7L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 100L, 7L));
        assertEquals("14003", ex.getCode());
    }

    @Test
    void countPendingReviews_delegates_to_dao() {
        when(agentDao.countByStatus(100L, "PENDING_REVIEW")).thenReturn(3);
        assertEquals(3, service.countPendingReviews(100L));
        verify(agentDao).countByStatus(100L, "PENDING_REVIEW");
    }

    @Test
    void updateAgent_updates_name_only() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("旧名称");
        agent.setStatus("DRAFT");
        agent.setEditingVersionId(10L);
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        AgentDO updated = new AgentDO();
        updated.setId(1L);
        updated.setTenantId(100L);
        updated.setName("新名称");
        updated.setStatus("DRAFT");
        updated.setEditingVersionId(10L);
        updated.setVersion(3);
        when(agentDao.findById(1L)).thenReturn(agent).thenReturn(updated).thenReturn(updated);
        when(agentDao.updateName(1L, 100L, "新名称", 2, 7L)).thenReturn(1);

        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(1L);
        req.setName("新名称");

        AgentVO vo = service.updateAgent(req, 100L, 7L);

        assertEquals("新名称", vo.getName());
        verify(agentDao).updateName(1L, 100L, "新名称", 2, 7L);
        verify(versionDao, never()).updateConfig(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void updateAgent_updates_version_fields_only() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("worker");
        agent.setStatus("DRAFT");
        agent.setEditingVersionId(10L);
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        AgentVersionDO draft = new AgentVersionDO();
        draft.setId(10L);
        draft.setStatus("DRAFT");
        draft.setRoleName("old-role");
        draft.setRoleCode("OLD");
        draft.setBusinessBackground("old-bg");
        draft.setResponsibilities("old-resp");
        draft.setVersion(0);
        when(versionDao.findById(10L)).thenReturn(draft);
        when(versionDao.updateConfig(eq(10L), eq(100L), any(), eq("NEW_ROLE"), eq("old-bg"), eq("old-resp"),
                any(), any(), eq(0), eq(7L))).thenReturn(1);

        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(1L);
        req.setRoleCode("NEW_ROLE");

        AgentVO vo = service.updateAgent(req, 100L, 7L);

        verify(versionDao).updateConfig(eq(10L), eq(100L), eq("old-role"), eq("NEW_ROLE"),
                eq("old-bg"), eq("old-resp"), any(), any(), eq(0), eq(7L));
        verify(agentDao, never()).updateName(anyLong(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void updateAgent_no_op_returns_current_data() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("worker");
        agent.setStatus("DRAFT");
        agent.setEditingVersionId(10L);
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(1L);

        AgentVO vo = service.updateAgent(req, 100L, 7L);

        assertEquals("worker", vo.getName());
        verify(agentDao, never()).updateName(anyLong(), anyLong(), any(), any(), anyLong());
        verify(versionDao, never()).updateConfig(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void updateAgent_not_found_throws() {
        when(agentDao.findById(99L)).thenReturn(null);
        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(99L);
        req.setName("new");
        BizException ex = assertThrows(BizException.class, () -> service.updateAgent(req, 100L, 7L));
        assertEquals("14001", ex.getCode());
    }

    @Test
    void updateAgent_tenant_mismatch_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(999L);
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(1L);
        req.setName("new");
        BizException ex = assertThrows(BizException.class, () -> service.updateAgent(req, 100L, 7L));
        assertEquals("14001", ex.getCode());
        verify(agentDao, never()).updateName(anyLong(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void updateAgent_blank_name_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("worker");
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);

        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(1L);
        req.setName("  ");
        BizException ex = assertThrows(BizException.class, () -> service.updateAgent(req, 100L, 7L));
        assertEquals("14008", ex.getCode());
        verify(agentDao, never()).updateName(anyLong(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void updateAgent_name_conflict_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(1L);
        agent.setTenantId(100L);
        agent.setName("worker");
        agent.setVersion(2);
        when(agentDao.findById(1L)).thenReturn(agent);
        when(agentDao.updateName(1L, 100L, "new", 2, 7L)).thenReturn(0);

        UpdateAgentRequest req = new UpdateAgentRequest();
        req.setId(1L);
        req.setName("new");
        BizException ex = assertThrows(BizException.class, () -> service.updateAgent(req, 100L, 7L));
        assertEquals("14003", ex.getCode());
    }
}
