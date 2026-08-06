package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;

import com.aliyun.autowonder.agent.dto.RepoPermRequest;
import com.aliyun.autowonder.agent.dto.SkillRequest;
import com.aliyun.autowonder.agent.dto.MemoryRefRequest;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.org.OrgDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSubTableTest {

    AgentDao agentDao;
    AgentVersionDao versionDao;
    AgentRepoPermDao repoPermDao;
    AgentSkillDao skillDao;
    AgentMemoryRefDao memoryRefDao;
    OrgDao orgDao;
    AgentService service;
	SkillDao capabilityDao;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        repoPermDao = mock(AgentRepoPermDao.class);
        skillDao = mock(AgentSkillDao.class);
        memoryRefDao = mock(AgentMemoryRefDao.class);
        orgDao = mock(OrgDao.class);
		capabilityDao = mock(SkillDao.class);
        service = new AgentService(agentDao, versionDao, repoPermDao, skillDao, memoryRefDao, orgDao,
                mock(ExecutorDao.class), mock(ExecutorRegistry.class), capabilityDao);
    }

    private AgentDO agentWithDraft(long agentId, long versionId) {
        AgentDO a = new AgentDO();
        a.setId(agentId);
        a.setTenantId(100L);
        a.setEditingVersionId(versionId);
        a.setVersion(0);
        return a;
    }

    private AgentVersionDO draftVersion(long versionId) {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(versionId);
        v.setTenantId(100L);
        v.setStatus("DRAFT");
        v.setVersion(0);
        return v;
    }

    @Test
    void addRepoPerm_inserts_to_draft_version() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft(10L, 20L));
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        RepoPermRequest req = new RepoPermRequest();
        req.setRepoId(300L);
        req.setPermLevel("WRITE");

        service.addRepoPerm(10L, req, 100L, 7L);
        verify(repoPermDao).insert(argThat((AgentRepoPermDO p) ->
                p.getAgentVersionId() == 20L && p.getRepoId() == 300L && "WRITE".equals(p.getPermLevel())));
    }

    @Test
    void addRelationsAreIdempotentWhenAlreadyBound() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft(10L, 20L));
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        AgentRepoPermDO repo = new AgentRepoPermDO();
        repo.setRepoId(300L);
        when(repoPermDao.listByVersion(20L)).thenReturn(List.of(repo));
        AgentSkillDO skill = new AgentSkillDO();
        skill.setSkillId(400L);
        when(skillDao.listByVersion(20L)).thenReturn(List.of(skill));
        when(memoryRefDao.existsByVersionAndMemory(20L, 500L, 100L)).thenReturn(true);
        SkillDO capability = new SkillDO();
        capability.setId(400L);
        capability.setTenantId(100L);
        when(capabilityDao.findById(400L)).thenReturn(capability);

        RepoPermRequest repoRequest = new RepoPermRequest();
        repoRequest.setRepoId(300L);
        SkillRequest skillRequest = new SkillRequest();
        skillRequest.setSkillId(400L);
        MemoryRefRequest memoryRequest = new MemoryRefRequest();
        memoryRequest.setMemoryId(500L);

        service.addRepoPerm(10L, repoRequest, 100L, 7L);
        service.addSkill(10L, skillRequest, 100L, 7L);
        service.addMemoryRef(10L, memoryRequest, 100L, 7L);

        verify(repoPermDao, never()).insert(any());
        verify(skillDao, never()).insert(any());
        verify(memoryRefDao, never()).insert(any());
    }

    @Test
    void addSkill_inserts_to_draft_version() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft(10L, 20L));
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        SkillRequest req = new SkillRequest();
        req.setSkillId(400L);
		SkillDO capability = new SkillDO();
		capability.setId(400L);
		capability.setTenantId(100L);
		when(capabilityDao.findById(400L)).thenReturn(capability);

        service.addSkill(10L, req, 100L, 7L);
        verify(skillDao).insert(argThat((AgentSkillDO s) ->
                s.getAgentVersionId() == 20L && s.getSkillId() == 400L));
    }

	@Test
	void addSkill_rejects_capability_from_another_tenant() {
		SkillDO capability = new SkillDO();
		capability.setId(400L);
		capability.setTenantId(999L);
		when(capabilityDao.findById(400L)).thenReturn(capability);
		SkillRequest req = new SkillRequest();
		req.setSkillId(400L);

		BizException ex = assertThrows(BizException.class, () -> service.addSkill(10L, req, 100L, 7L));

		assertEquals("22001", ex.getCode());
		verifyNoInteractions(agentDao, versionDao, skillDao);
	}

    @Test
    void addMemoryRef_defaults_source_to_DIRECT() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft(10L, 20L));
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        MemoryRefRequest req = new MemoryRefRequest();
        req.setMemoryId(500L);

        service.addMemoryRef(10L, req, 100L, 7L);
        verify(memoryRefDao).insert(argThat((AgentMemoryRefDO m) ->
                m.getAgentVersionId() == 20L && m.getMemoryId() == 500L && "DIRECT".equals(m.getSource())));
    }

    @Test
    void attachReviewedMemoryAddsRefAndSubmitsDraft() {
        AgentDO agent = agentWithDraft(10L, 20L);
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(19L);
        agent.setLatestVersionNo(2);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        when(memoryRefDao.existsByVersionAndMemory(20L, 500L, 100L)).thenReturn(false);
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("PENDING_REVIEW"),
                isNull(), contains("自动同步已采纳记忆"), isNull(), eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("PENDING_REVIEW"),
                eq(19L), eq(20L), eq(2), eq(0), eq(7L))).thenReturn(1);

        service.attachReviewedMemory(10L, 500L, "ORG", 100L, 7L);

        verify(memoryRefDao).insert(argThat(ref -> ref.getAgentVersionId() == 20L
                && ref.getMemoryId() == 500L && "ORG_IMPORT".equals(ref.getSource())));
    }

    @Test
    void attachReviewedMemoryAddsToAlreadyPendingVersionWithoutResubmitting() {
        AgentDO agent = agentWithDraft(10L, 20L);
        agent.setStatus("PENDING_REVIEW");
        agent.setOnlineVersionId(19L);
        when(agentDao.findById(10L)).thenReturn(agent);
        AgentVersionDO pending = draftVersion(20L);
        pending.setStatus("PENDING_REVIEW");
        when(versionDao.findById(20L)).thenReturn(pending);
        when(memoryRefDao.existsByVersionAndMemory(20L, 500L, 100L)).thenReturn(false);

        service.attachReviewedMemory(10L, 500L, "SQUAD", 100L, 7L);

        verify(memoryRefDao).insert(argThat(ref -> ref.getAgentVersionId() == 20L
                && ref.getMemoryId() == 500L && "SQUAD_IMPORT".equals(ref.getSource())));
        verify(versionDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void submitReconcilesApplicableAdoptedMemoriesIntoDraft() {
        AgentDO agent = agentWithDraft(10L, 20L);
        agent.setStatus("DRAFT");
        agent.setLatestVersionNo(1);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        when(versionDao.updateStatus(eq(20L), eq(100L), eq("PENDING_REVIEW"),
                isNull(), isNull(), isNull(), eq(0), eq(7L))).thenReturn(1);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("PENDING_REVIEW"),
                isNull(), eq(20L), eq(1), eq(0), eq(7L))).thenReturn(1);
        MemoryDO orgMemory = new MemoryDO();
        orgMemory.setId(600L);
        orgMemory.setScope("ORG");
        MemoryScopeResolver resolver = mock(MemoryScopeResolver.class);
        when(resolver.listApplicable(100L, 10L)).thenReturn(List.of(orgMemory));
        when(memoryRefDao.existsByVersionAndMemory(20L, 600L, 100L)).thenReturn(false);
        service.setMemoryScopeResolver(resolver);

        service.submit(10L, 100L, 7L);

        verify(memoryRefDao).insert(argThat(ref -> ref.getAgentVersionId() == 20L
                && ref.getMemoryId() == 600L && "ORG_IMPORT".equals(ref.getSource())));
    }

    @Test
    void addRepoPerm_without_draft_creates_draft_from_online_version() {
        AgentDO a = new AgentDO();
        a.setId(10L);
        a.setTenantId(100L);
        a.setStatus("ONLINE");
        a.setOnlineVersionId(20L);
        a.setEditingVersionId(null);
        a.setLatestVersionNo(1);
        a.setVersion(0);
        AgentVersionDO online = draftVersion(20L);
        online.setStatus("APPROVED");
        online.setRoleName("coder");
        when(agentDao.findById(10L)).thenReturn(a);
        when(versionDao.findById(20L)).thenReturn(online);
        when(repoPermDao.listByVersion(20L)).thenReturn(List.of());
        when(skillDao.listByVersion(20L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(20L)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<AgentVersionDO>getArgument(0).setId(30L);
            return null;
        }).when(versionDao).insert(any(AgentVersionDO.class));
        when(agentDao.updateStatus(10L, 100L, "ONLINE", 20L, 30L, 2, 0, 7L)).thenReturn(1);
        RepoPermRequest req = new RepoPermRequest();
        req.setRepoId(300L);

        service.addRepoPerm(10L, req, 100L, 7L);

        verify(versionDao).insert(argThat((AgentVersionDO v) -> v.getVersionNo() == 2 && "DRAFT".equals(v.getStatus())));
        verify(repoPermDao).insert(argThat((AgentRepoPermDO p) ->
                p.getAgentVersionId() == 30L && p.getRepoId() == 300L));
    }

    @Test
    void removeRepoPerm_delegates_to_dao() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft(10L, 20L));
        when(versionDao.findById(20L)).thenReturn(draftVersion(20L));
        service.removeRepoPerm(10L, 300L, 100L, 7L);
        verify(repoPermDao).deleteByVersionAndRepo(20L, 300L, 100L);
    }
}
