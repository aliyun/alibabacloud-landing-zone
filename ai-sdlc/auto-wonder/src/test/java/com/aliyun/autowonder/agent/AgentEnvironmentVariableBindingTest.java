package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.environment.EnvironmentVariableDO;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentEnvironmentVariableBindingTest {
    private AgentDao agentDao;
    private AgentVersionDao versionDao;
    private AgentRepoPermDao repoPermDao;
    private AgentSkillDao skillDao;
    private AgentMemoryRefDao memoryRefDao;
    private AgentEnvironmentVariableRefDao environmentRefDao;
    private EnvironmentVariableDao environmentVariableDao;
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        repoPermDao = mock(AgentRepoPermDao.class);
        skillDao = mock(AgentSkillDao.class);
        memoryRefDao = mock(AgentMemoryRefDao.class);
        environmentRefDao = mock(AgentEnvironmentVariableRefDao.class);
        environmentVariableDao = mock(EnvironmentVariableDao.class);
        service = new AgentService(agentDao, versionDao, repoPermDao, skillDao, memoryRefDao,
                mock(WorkspaceDao.class), mock(ExecutorDao.class), mock(ExecutorRegistry.class), null,
                environmentRefDao, environmentVariableDao);
    }

    @Test
    void mountLocksActiveVariableBeforeInsertAndIsTransactional() throws Exception {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft());
        when(versionDao.findById(20L)).thenReturn(draftVersion());
        when(environmentVariableDao.findActiveByIdForUpdate(100L, 30L)).thenReturn(variable(30L));
        when(environmentRefDao.exists(100L, 20L, 30L)).thenReturn(false);

        service.addEnvironmentVariableRef(10L, 30L, 100L, 7L);

        InOrder order = inOrder(environmentVariableDao, environmentRefDao);
        order.verify(environmentVariableDao).findActiveByIdForUpdate(100L, 30L);
        order.verify(environmentRefDao).exists(100L, 20L, 30L);
        order.verify(environmentRefDao).insert(argThat(ref -> ref.getTenantId() == 100L
                && ref.getAgentVersionId() == 20L && ref.getEnvironmentVariableId() == 30L));
        Method method = AgentService.class.getMethod("addEnvironmentVariableRef",
                long.class, long.class, long.class, long.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void mountIsIdempotent() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft());
        when(versionDao.findById(20L)).thenReturn(draftVersion());
        when(environmentVariableDao.findActiveByIdForUpdate(100L, 30L)).thenReturn(variable(30L));
        when(environmentRefDao.exists(100L, 20L, 30L)).thenReturn(true);

        service.addEnvironmentVariableRef(10L, 30L, 100L, 7L);

        verify(environmentRefDao, never()).insert(any());
    }

    @Test
    void mountRejectsDeletedOrCrossTenantVariable() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft());
        when(versionDao.findById(20L)).thenReturn(draftVersion());
        when(environmentVariableDao.findActiveByIdForUpdate(100L, 30L)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.addEnvironmentVariableRef(10L, 30L, 100L, 7L));

        assertEquals("33001", error.getCode());
        verify(environmentRefDao, never()).insert(any());
    }

    @Test
    void unmountIsIdempotentAndUsesEditableDraft() {
        when(agentDao.findById(10L)).thenReturn(agentWithDraft());
        when(versionDao.findById(20L)).thenReturn(draftVersion());

        service.removeEnvironmentVariableRef(10L, 30L, 100L, 7L);

        verify(environmentRefDao).delete(100L, 20L, 30L);
    }

    @Test
    void listUsesPublishedVersionAndReturnsMetadataWithoutSecret() {
        AgentDO agent = agentWithDraft();
        agent.setOnlineVersionId(40L);
        when(agentDao.findById(10L)).thenReturn(agent);
        AgentEnvironmentVariableRefVO metadata = new AgentEnvironmentVariableRefVO(
                30L, "API_TOKEN", "deployment token", "**");
        when(environmentRefDao.listMetadataByVersion(100L, 40L)).thenReturn(List.of(metadata));

        List<AgentEnvironmentVariableRefVO> result = service.listEnvironmentVariableRefs(10L, 100L);

        assertEquals(List.of(metadata), result);
        assertEquals("**", result.get(0).getValue());
        assertFalse(hasGetter(AgentEnvironmentVariableRefVO.class, "getCredentialRef"));
        verify(environmentRefDao).listMetadataByVersion(100L, 40L);
    }

    @Test
    void agentDetailAndVersionExposeOnlyMetadataForTheirResolvedVersion() {
        AgentDO agent = agentWithDraft();
        agent.setOnlineVersionId(40L);
        when(agentDao.findById(10L)).thenReturn(agent);
        AgentVersionDO online = draftVersion();
        online.setId(40L);
        online.setVersionNo(3);
        online.setStatus("APPROVED");
        when(versionDao.findById(40L)).thenReturn(online);
        when(versionDao.findByAgentAndNo(10L, 3)).thenReturn(online);
        AgentEnvironmentVariableRefVO metadata = new AgentEnvironmentVariableRefVO(
                30L, "API_TOKEN", "deployment token", "**");
        when(environmentRefDao.listMetadataByVersion(100L, 40L)).thenReturn(List.of(metadata));

        AgentVO detail = service.get(10L, 100L);
        AgentVersionVO version = service.getVersion(10L, 3, 100L);

        assertEquals(List.of(metadata), detail.getEnvironmentVariables());
        assertEquals(List.of(metadata), version.getEnvironmentVariables());
        verify(environmentRefDao, never()).listMetadataByVersion(100L, 20L);
    }

    @Test
    void tenantScopedAgentDetailRejectsCrossTenantMetadataAccess() {
        AgentDO otherTenantAgent = agentWithDraft();
        otherTenantAgent.setTenantId(200L);
        when(agentDao.findById(10L)).thenReturn(otherTenantAgent);

        BizException error = assertThrows(BizException.class, () -> service.get(10L, 100L));

        assertEquals("14001", error.getCode());
        verify(environmentRefDao, never()).listMetadataByVersion(anyLong(), anyLong());
    }

    @Test
    void approveRejectsVersionReferencingDeletedVariable() {
        AgentDO agent = agentWithDraft();
        agent.setStatus("PENDING_REVIEW");
        AgentVersionDO pending = draftVersion();
        pending.setStatus("PENDING_REVIEW");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findById(20L)).thenReturn(pending);
        when(environmentRefDao.countInvalidByVersion(100L, 20L)).thenReturn(1);

        BizException error = assertThrows(BizException.class,
                () -> service.approve(10L, 100L, 7L, "ok"));

        assertEquals("33008", error.getCode());
        verify(versionDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void rollbackRejectsHistoricalVersionReferencingDeletedVariable() {
        AgentDO agent = agentWithDraft();
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(40L);
        AgentVersionDO target = draftVersion();
        target.setId(15L);
        target.setVersionNo(1);
        target.setStatus("APPROVED");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findByAgentAndNo(10L, 1)).thenReturn(target);
        when(environmentRefDao.countInvalidByVersion(100L, 15L)).thenReturn(1);

        BizException error = assertThrows(BizException.class,
                () -> service.rollback(10L, 1, 100L, 7L));

        assertEquals("33008", error.getCode());
        verify(agentDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void rollbackLocksReferencedVariablesBeforePublishingHistoricalVersion() {
        AgentDO agent = agentWithDraft();
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(40L);
        AgentVersionDO target = draftVersion();
        target.setId(15L);
        target.setVersionNo(1);
        target.setStatus("APPROVED");
        AgentEnvironmentVariableRefDO ref = new AgentEnvironmentVariableRefDO();
        ref.setEnvironmentVariableId(30L);
        when(agentDao.findById(10L)).thenReturn(agent, agent);
        when(versionDao.findByAgentAndNo(10L, 1)).thenReturn(target);
        when(environmentRefDao.listByVersion(100L, 15L)).thenReturn(List.of(ref));
        when(environmentVariableDao.findActiveByIdForUpdate(100L, 30L)).thenReturn(variable(30L));
        when(agentDao.updateStatus(10L, 100L, "ONLINE", 15L, 20L, 1, 0, 7L)).thenReturn(1);

        service.rollback(10L, 1, 100L, 7L);

        InOrder order = inOrder(environmentVariableDao, agentDao);
        order.verify(environmentVariableDao).findActiveByIdForUpdate(100L, 30L);
        order.verify(agentDao).updateStatus(10L, 100L, "ONLINE", 15L, 20L, 1, 0, 7L);
    }

    @Test
    void onlineRejectsApprovedVersionReferencingDeletedVariable() {
        AgentDO agent = agentWithDraft();
        agent.setStatus("OFFLINE");
        agent.setOnlineVersionId(null);
        AgentVersionDO target = draftVersion();
        target.setId(15L);
        target.setVersionNo(1);
        target.setStatus("APPROVED");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.listApprovedByAgent(10L)).thenReturn(List.of(target));
        when(environmentRefDao.countInvalidByVersion(100L, 15L)).thenReturn(1);

        BizException error = assertThrows(BizException.class,
                () -> service.online(10L, 100L, 7L));

        assertEquals("33008", error.getCode());
        verify(agentDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void deletingAgentDeletesEnvironmentReferencesForEveryVersion() {
        AgentDO agent = agentWithDraft();
        agent.setStatus("DRAFT");
        AgentVersionDO first = draftVersion();
        first.setId(20L);
        AgentVersionDO second = draftVersion();
        second.setId(21L);
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.listByAgent(10L)).thenReturn(List.of(first, second));
        when(agentDao.softDelete(10L, 100L, 0, 7L)).thenReturn(1);

        service.delete(10L, 100L, 7L);

        verify(environmentRefDao).deleteByVersion(100L, 20L);
        verify(environmentRefDao).deleteByVersion(100L, 21L);
    }

    @Test
    void controllerMutationEndpointsRequireReadWrite() throws Exception {
        assertMutationEndpoint("addEnvironmentVariableRef", PostMapping.class);
        assertMutationEndpoint("removeEnvironmentVariableRef", DeleteMapping.class);
    }

    private void assertMutationEndpoint(String name, Class<?> mappingType) throws Exception {
        Method method = AgentController.class.getMethod(name, Long.class, Long.class);
        RequireWorkspaceAccess access = method.getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
        assertNotNull(method.getAnnotation(mappingType.asSubclass(java.lang.annotation.Annotation.class)));
    }

    private boolean hasGetter(Class<?> type, String methodName) {
        try {
            type.getMethod(methodName);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private AgentDO agentWithDraft() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setEditingVersionId(20L);
        agent.setLatestVersionNo(1);
        agent.setStatus("DRAFT");
        agent.setVersion(0);
        return agent;
    }

    private AgentVersionDO draftVersion() {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(20L);
        version.setTenantId(100L);
        version.setAgentId(10L);
        version.setStatus("DRAFT");
        version.setVersion(0);
        return version;
    }

    private EnvironmentVariableDO variable(long id) {
        EnvironmentVariableDO variable = new EnvironmentVariableDO();
        variable.setId(id);
        variable.setTenantId(100L);
        variable.setName("API_TOKEN");
        variable.setCredentialRef("secret-ref-must-not-escape");
        variable.setDescription("deployment token");
        return variable;
    }
}
