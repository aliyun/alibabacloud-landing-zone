package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;

import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.aliyun.autowonder.agent.dto.AgentVO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentVersionLifecycleTest {

    AgentDao agentDao;
    AgentVersionDao versionDao;
    AgentRepoPermDao repoPermDao;
    AgentSkillDao skillDao;
    AgentMemoryRefDao memoryRefDao;
    WorkspaceDao workspaceDao;
    AgentService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        repoPermDao = mock(AgentRepoPermDao.class);
        skillDao = mock(AgentSkillDao.class);
        memoryRefDao = mock(AgentMemoryRefDao.class);
        workspaceDao = mock(WorkspaceDao.class);
        service = new AgentService(agentDao, versionDao, repoPermDao, skillDao, memoryRefDao, workspaceDao,
                mock(ExecutorDao.class), mock(ExecutorRegistry.class));
    }

    private AgentDO agentWithDraft(long agentId, long versionId) {
        AgentDO a = new AgentDO();
        a.setId(agentId);
        a.setTenantId(100L);
        a.setStatus("DRAFT");
        a.setEditingVersionId(versionId);
        a.setLatestVersionNo(1);
        a.setVersion(0);
        return a;
    }

    private AgentVersionDO draftVersion(long versionId, long agentId) {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(versionId);
        v.setTenantId(100L);
        v.setAgentId(agentId);
        v.setVersionNo(1);
        v.setStatus("DRAFT");
        v.setVersion(0);
        return v;
    }

    @Test
    void editConfig_updates_existing_draft() {
        AgentDO agent = agentWithDraft(10L, 20L);
        AgentVersionDO draft = draftVersion(20L, 10L);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(draft);
        when(versionDao.updateConfig(eq(20L), eq(100L), eq("dev"), eq("DEV"),
                eq("bg"), eq("resp"), isNull(), isNull(), eq(0), eq(7L))).thenReturn(1);
        AgentVersionDO updated = draftVersion(20L, 10L);
        updated.setRoleName("dev");
        updated.setVersion(1);
        when(versionDao.findById(20L)).thenReturn(draft, updated);

        UpdateConfigRequest req = new UpdateConfigRequest();
        req.setRoleName("dev");
        req.setRoleCode("DEV");
        req.setBusinessBackground("bg");
        req.setResponsibilities("resp");

        AgentVersionVO vo = service.editConfig(10L, req, 100L, 7L);
        assertEquals("dev", vo.getRoleName());
    }

    @Test
    void editConfig_clones_from_online_when_no_draft() {
        doAnswer(inv -> { ((AgentVersionDO) inv.getArgument(0)).setId(999L); return null; })
                .when(versionDao).insert(any());
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(50L);
        agent.setEditingVersionId(null);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        AgentVersionDO online = new AgentVersionDO();
        online.setId(50L);
        online.setAgentId(10L);
        online.setVersionNo(1);
        online.setStatus("APPROVED");
        online.setRoleName("old-role");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(50L)).thenReturn(online);
        when(repoPermDao.listByVersion(50L)).thenReturn(List.of());
        when(skillDao.listByVersion(50L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(50L)).thenReturn(List.of());
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("ONLINE"), eq(50L),
                eq(999L), eq(2), eq(0), eq(7L))).thenReturn(1);
        when(versionDao.updateConfig(eq(999L), eq(100L), eq("new-role"), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(0), eq(7L))).thenReturn(1);
        AgentVersionDO created = new AgentVersionDO();
        created.setId(999L);
        created.setAgentId(10L);
        created.setVersionNo(2);
        created.setStatus("DRAFT");
        created.setRoleName("new-role");
        created.setVersion(1);
        when(versionDao.findById(999L)).thenReturn(created);

        UpdateConfigRequest req = new UpdateConfigRequest();
        req.setRoleName("new-role");

        AgentVersionVO vo = service.editConfig(10L, req, 100L, 7L);
        assertNotNull(vo);
        verify(versionDao).insert(argThat((AgentVersionDO v) -> v.getVersionNo() == 2 && "DRAFT".equals(v.getStatus())));
    }

    @Test
    void editConfigPersistsEvolutionModeInIdentityJson() {
        AgentDO agent = agentWithDraft(10L, 20L);
        AgentVersionDO draft = draftVersion(20L, 10L);
        draft.setIdentityJson("{\"theme\":\"quiet\"}");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(draft);
        when(versionDao.updateConfig(eq(20L), eq(100L), isNull(), isNull(),
                isNull(), isNull(), isNull(), argThat(json ->
                        json != null
                                && json.contains("\"theme\":\"quiet\"")
                                && json.contains("\"evolutionMode\":\"MANUAL\"")),
                eq(0), eq(7L))).thenReturn(1);
        AgentVersionDO updated = draftVersion(20L, 10L);
        updated.setIdentityJson("{\"theme\":\"quiet\",\"evolutionMode\":\"MANUAL\"}");
        updated.setVersion(1);
        when(versionDao.findById(20L)).thenReturn(draft, updated);

        UpdateConfigRequest req = new UpdateConfigRequest();
        req.setEvolutionMode("MANUAL");

        AgentVersionVO vo = service.editConfig(10L, req, 100L, 7L);

        assertTrue(vo.getIdentityJson().contains("\"evolutionMode\":\"MANUAL\""));
    }

    @Test
    void editConfig_agent_not_found_throws() {
        when(agentDao.findById(9L)).thenReturn(null);
        UpdateConfigRequest req = new UpdateConfigRequest();
        BizException ex = assertThrows(BizException.class, () -> service.editConfig(9L, req, 100L, 7L));
        assertEquals("14001", ex.getCode());
    }

    @Test
    void submit_transitions_draft_to_pending() {
        AgentDO agent = agentWithDraft(10L, 20L);
        AgentVersionDO draft = draftVersion(20L, 10L);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(draft);
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("PENDING_REVIEW"),
                isNull(), isNull(), isNull(), eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("PENDING_REVIEW"),
                isNull(), eq(20L), eq(1), eq(0), eq(7L))).thenReturn(1);
        AgentDO updated = agentWithDraft(10L, 20L);
        updated.setStatus("PENDING_REVIEW");
        when(agentDao.findById(10L)).thenReturn(agent, updated);

        AgentVO vo = service.submit(10L, 100L, 7L);
        assertEquals("PENDING_REVIEW", vo.getStatus());
    }

    @Test
    void approve_sets_online_and_generates_identity() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setName("TestBot");
        agent.setStatus("PENDING_REVIEW");
        agent.setEditingVersionId(20L);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        AgentVersionDO pending = new AgentVersionDO();
        pending.setId(20L);
        pending.setTenantId(100L);
        pending.setAgentId(10L);
        pending.setVersionNo(1);
        pending.setStatus("PENDING_REVIEW");
        pending.setRoleName("coder");
        pending.setVersion(0);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(pending);
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("APPROVED"),
                eq(7L), eq("lgtm"), argThat(json -> json != null && json.contains("TestBot")),
                eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("ONLINE"),
                eq(20L), isNull(), eq(1), eq(0), eq(7L))).thenReturn(1);
        AgentDO onlineAgent = new AgentDO();
        onlineAgent.setId(10L);
        onlineAgent.setName("TestBot");
        onlineAgent.setStatus("ONLINE");
        onlineAgent.setOnlineVersionId(20L);
        onlineAgent.setLatestVersionNo(1);
        when(agentDao.findById(10L)).thenReturn(agent, onlineAgent);

        AgentVO vo = service.approve(10L, 100L, 7L, "lgtm");
        assertEquals("ONLINE", vo.getStatus());
        assertEquals(Long.valueOf(20L), vo.getOnlineVersionId());
    }

    @Test
    void approve_allows_tenant_owner_to_approve_own_version() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setName("OwnerBot");
        agent.setStatus("PENDING_REVIEW");
        agent.setEditingVersionId(20L);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        AgentVersionDO pending = new AgentVersionDO();
        pending.setId(20L);
        pending.setTenantId(100L);
        pending.setAgentId(10L);
        pending.setVersionNo(1);
        pending.setStatus("PENDING_REVIEW");
        pending.setCreatorId(7L);
        pending.setRoleName("coder");
        pending.setVersion(0);
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(100L);
        workspace.setOwnerId(7L);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(pending);
        when(workspaceDao.findById(100L)).thenReturn(workspace);
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("APPROVED"),
                eq(7L), eq("owner approve"), anyString(), eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("ONLINE"),
                eq(20L), isNull(), eq(1), eq(0), eq(7L))).thenReturn(1);
        AgentDO onlineAgent = new AgentDO();
        onlineAgent.setId(10L);
        onlineAgent.setName("OwnerBot");
        onlineAgent.setStatus("ONLINE");
        onlineAgent.setOnlineVersionId(20L);
        onlineAgent.setLatestVersionNo(1);
        when(agentDao.findById(10L)).thenReturn(agent, onlineAgent);

        AgentVO vo = service.approve(10L, 100L, 7L, "owner approve");

        assertEquals("ONLINE", vo.getStatus());
    }

    @Test
    void approve_allows_non_owner_admin_to_approve_own_version() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setName("AdminBot");
        agent.setStatus("PENDING_REVIEW");
        agent.setEditingVersionId(20L);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        AgentVersionDO pending = new AgentVersionDO();
        pending.setId(20L);
        pending.setTenantId(100L);
        pending.setAgentId(10L);
        pending.setVersionNo(1);
        pending.setStatus("PENDING_REVIEW");
        pending.setCreatorId(7L);
        pending.setRoleName("coder");
        pending.setVersion(0);
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(100L);
        workspace.setOwnerId(9L);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(pending);
        when(workspaceDao.findById(100L)).thenReturn(workspace);
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("APPROVED"),
                eq(7L), eq("admin approve"), anyString(), eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("ONLINE"),
                eq(20L), isNull(), eq(1), eq(0), eq(7L))).thenReturn(1);
        AgentDO onlineAgent = new AgentDO();
        onlineAgent.setId(10L);
        onlineAgent.setName("AdminBot");
        onlineAgent.setStatus("ONLINE");
        onlineAgent.setOnlineVersionId(20L);
        onlineAgent.setLatestVersionNo(1);
        when(agentDao.findById(10L)).thenReturn(agent, onlineAgent);

        AutoWonderContext context = AutoWonderContext.get();
        context.setUserId(7L);
        context.setCurrentWorkspaceId(100L);
        context.setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
        try {
            AgentVO vo = service.approve(10L, 100L, 7L, "admin approve");

            assertEquals("ONLINE", vo.getStatus());
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void approve_rejects_read_write_creator_self_approval() {
        assertOwnVersionApprovalDenied(100L, 7L, WorkspaceAccessLevel.READ_WRITE);
    }

    @Test
    void approve_rejects_admin_context_for_different_user() {
        assertOwnVersionApprovalDenied(100L, 8L, WorkspaceAccessLevel.ADMIN);
    }

    @Test
    void approve_rejects_admin_context_for_different_org() {
        assertOwnVersionApprovalDenied(200L, 7L, WorkspaceAccessLevel.ADMIN);
    }

    private void assertOwnVersionApprovalDenied(
            long contextWorkspaceId, long contextUserId, WorkspaceAccessLevel contextAccessLevel) {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("PENDING_REVIEW");
        agent.setEditingVersionId(20L);
        AgentVersionDO pending = new AgentVersionDO();
        pending.setId(20L);
        pending.setTenantId(100L);
        pending.setStatus("PENDING_REVIEW");
        pending.setCreatorId(7L);
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(100L);
        workspace.setOwnerId(9L);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(pending);
        when(workspaceDao.findById(100L)).thenReturn(workspace);

        AutoWonderContext context = AutoWonderContext.get();
        context.setUserId(contextUserId);
        context.setCurrentWorkspaceId(contextWorkspaceId);
        context.setWorkspaceAccessLevel(contextAccessLevel);
        try {
            BizException ex = assertThrows(BizException.class,
                    () -> service.approve(10L, 100L, 7L, "self approve"));

            assertEquals("10403", ex.getCode());
            verify(versionDao, never()).updateStatus(anyLong(), anyLong(), eq("APPROVED"),
                    anyLong(), any(), any(), anyInt(), anyLong());
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void approve_rejects_agent_from_different_tenant() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(200L);
        agent.setStatus("PENDING_REVIEW");
        agent.setEditingVersionId(20L);
        when(agentDao.findById(10L)).thenReturn(agent);

        BizException ex = assertThrows(BizException.class, () -> service.approve(10L, 100L, 7L, "approve"));

        assertEquals("14001", ex.getCode());
        verify(versionDao, never()).findById(anyLong());
    }

    @Test
    void reject_clears_editing_version() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("PENDING_REVIEW");
        agent.setEditingVersionId(20L);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        AgentVersionDO pending = new AgentVersionDO();
        pending.setId(20L);
        pending.setTenantId(100L);
        pending.setAgentId(10L);
        pending.setStatus("PENDING_REVIEW");
        pending.setVersion(0);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(pending);
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("REJECTED"),
                eq(7L), eq("needs work"), isNull(), eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("DRAFT"),
                isNull(), isNull(), eq(1), eq(0), eq(7L))).thenReturn(1);
        AgentDO rejectedAgent = new AgentDO();
        rejectedAgent.setId(10L);
        rejectedAgent.setStatus("DRAFT");
        rejectedAgent.setLatestVersionNo(1);
        when(agentDao.findById(10L)).thenReturn(agent, rejectedAgent);

        AgentVO vo = service.reject(10L, 100L, 7L, "needs work");
        assertEquals("DRAFT", vo.getStatus());
    }

    @Test
    void submit_without_draft_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("ONLINE");
        agent.setEditingVersionId(null);
        when(agentDao.findById(10L)).thenReturn(agent);
        BizException ex = assertThrows(BizException.class, () -> service.submit(10L, 100L, 7L));
        assertEquals("14004", ex.getCode());
    }
}
