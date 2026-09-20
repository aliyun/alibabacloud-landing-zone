package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLifecycleIsolationTest {
    private AgentDao agentDao;
    private AgentVersionDao versionDao;
    private AgentEnvironmentVariableRefDao environmentRefDao;
    private EnvironmentVariableDao environmentVariableDao;
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        versionDao = mock(AgentVersionDao.class);
        environmentRefDao = mock(AgentEnvironmentVariableRefDao.class);
        environmentVariableDao = mock(EnvironmentVariableDao.class);
        service = new AgentService(agentDao, versionDao, mock(AgentRepoPermDao.class),
                mock(AgentSkillDao.class), mock(AgentMemoryRefDao.class), mock(WorkspaceDao.class),
                mock(ExecutorDao.class), mock(ExecutorRegistry.class), null,
                environmentRefDao, environmentVariableDao);
    }

    @Test
    void mountLocksAgentThenRechecksThatEditingVersionIsStillDraft() {
        AgentDO lockedAgent = agent(100L, "PENDING_REVIEW");
        AgentVersionDO pending = version(100L, 10L, "PENDING_REVIEW");
        when(agentDao.findById(10L)).thenReturn(lockedAgent);
        when(versionDao.findById(20L)).thenReturn(pending);

        BizException error = assertThrows(BizException.class,
                () -> service.addEnvironmentVariableRef(10L, 30L, 100L, 7L));

        assertEquals("14004", error.getCode());
        InOrder order = inOrder(agentDao, versionDao);
        order.verify(agentDao).lockByIdForUpdate(100L, 10L);
        order.verify(agentDao).findById(10L);
        order.verify(versionDao).findById(20L);
        verify(environmentVariableDao, never()).findActiveByIdForUpdate(anyLong(), anyLong());
        verify(environmentRefDao, never()).insert(any());
    }

    @Test
    void submitRejectsAnotherTenantsAgentBeforeInspectingVersion() {
        assertCrossTenantRejected(() -> service.submit(10L, 100L, 7L));
    }

    @Test
    void rejectRejectsAnotherTenantsAgentBeforeInspectingVersion() {
        assertCrossTenantRejected(() -> service.reject(10L, 100L, 7L, "no"));
    }

    @Test
    void rollbackRejectsAnotherTenantsAgentBeforeValidatingReferences() {
        assertCrossTenantRejected(() -> service.rollback(10L, 1, 100L, 7L));
    }

    @Test
    void offlineRejectsAnotherTenantsAgentBeforeInspectingState() {
        assertCrossTenantRejected(() -> service.offline(10L, 100L, 7L));
    }

    @Test
    void onlineRejectsAnotherTenantsAgentBeforeValidatingReferences() {
        assertCrossTenantRejected(() -> service.online(10L, 100L, 7L));
    }

    private void assertCrossTenantRejected(Runnable operation) {
        when(agentDao.findById(10L)).thenReturn(agent(200L, "OFFLINE"));

        BizException error = assertThrows(BizException.class, operation::run);

        assertEquals("14001", error.getCode());
        verify(versionDao, never()).findById(anyLong());
        verify(versionDao, never()).findByAgentAndNo(anyLong(), any());
        verify(versionDao, never()).listApprovedByAgent(anyLong());
        verify(environmentRefDao, never()).countInvalidByVersion(anyLong(), anyLong());
        verify(environmentRefDao, never()).listByVersion(anyLong(), anyLong());
        verify(environmentRefDao, never()).listMetadataByVersion(anyLong(), anyLong());
        verify(environmentVariableDao, never()).findActiveByIdForUpdate(anyLong(), anyLong());
    }

    private AgentDO agent(long tenantId, String status) {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(tenantId);
        agent.setStatus(status);
        agent.setEditingVersionId(20L);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        return agent;
    }

    private AgentVersionDO version(long tenantId, long agentId, String status) {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(20L);
        version.setTenantId(tenantId);
        version.setAgentId(agentId);
        version.setVersionNo(1);
        version.setStatus(status);
        version.setVersion(0);
        return version;
    }
}
