package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;

import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRollbackTest {

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

    @Test
    void rollback_switches_online_pointer() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(30L);
        agent.setEditingVersionId(null);
        agent.setLatestVersionNo(3);
        agent.setVersion(2);
        AgentVersionDO target = new AgentVersionDO();
        target.setId(20L);
        target.setAgentId(10L);
        target.setVersionNo(1);
        target.setStatus("APPROVED");
        when(versionDao.findByAgentAndNo(10L, 1)).thenReturn(target);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("ONLINE"),
                eq(20L), isNull(), eq(3), eq(2), eq(7L))).thenReturn(1);
        AgentDO rolledBack = new AgentDO();
        rolledBack.setId(10L);
        rolledBack.setStatus("ONLINE");
        rolledBack.setOnlineVersionId(20L);
        rolledBack.setLatestVersionNo(3);
        when(agentDao.findById(10L)).thenReturn(agent, rolledBack);

        AgentVO vo = service.rollback(10L, 1, 100L, 7L);
        assertEquals(Long.valueOf(20L), vo.getOnlineVersionId());
    }

    @Test
    void rollback_to_non_approved_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("ONLINE");
        agent.setVersion(0);
        AgentVersionDO rejected = new AgentVersionDO();
        rejected.setId(20L);
        rejected.setStatus("REJECTED");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.findByAgentAndNo(10L, 1)).thenReturn(rejected);
        BizException ex = assertThrows(BizException.class, () -> service.rollback(10L, 1, 100L, 7L));
        assertEquals("14007", ex.getCode());
    }

    @Test
    void offline_sets_status() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("ONLINE");
        agent.setOnlineVersionId(20L);
        agent.setEditingVersionId(null);
        agent.setLatestVersionNo(1);
        agent.setVersion(0);
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("OFFLINE"),
                isNull(), isNull(), eq(1), eq(0), eq(7L))).thenReturn(1);
        AgentDO offlined = new AgentDO();
        offlined.setId(10L);
        offlined.setStatus("OFFLINE");
        offlined.setLatestVersionNo(1);
        when(agentDao.findById(10L)).thenReturn(agent, offlined);

        AgentVO vo = service.offline(10L, 100L, 7L);
        assertEquals("OFFLINE", vo.getStatus());
    }

    @Test
    void offline_non_online_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setStatus("DRAFT");
        when(agentDao.findById(10L)).thenReturn(agent);
        BizException ex = assertThrows(BizException.class, () -> service.offline(10L, 100L, 7L));
        assertEquals("14006", ex.getCode());
    }

    @Test
    void online_reactivates_with_latest_approved_version() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setTenantId(100L);
        agent.setStatus("OFFLINE");
        agent.setOnlineVersionId(null);
        agent.setEditingVersionId(null);
        agent.setLatestVersionNo(2);
        agent.setVersion(3);
        AgentVersionDO latestApproved = new AgentVersionDO();
        latestApproved.setId(20L);
        latestApproved.setAgentId(10L);
        latestApproved.setVersionNo(2);
        latestApproved.setStatus("APPROVED");
        when(versionDao.listApprovedByAgent(10L)).thenReturn(List.of(latestApproved));
        when(agentDao.updateStatus(eq(10L), eq(100L), eq("ONLINE"),
                eq(20L), isNull(), eq(2), eq(3), eq(7L))).thenReturn(1);
        AgentDO reactivated = new AgentDO();
        reactivated.setId(10L);
        reactivated.setStatus("ONLINE");
        reactivated.setOnlineVersionId(20L);
        reactivated.setLatestVersionNo(2);
        when(agentDao.findById(10L)).thenReturn(agent, reactivated);

        AgentVO vo = service.online(10L, 100L, 7L);
        assertEquals("ONLINE", vo.getStatus());
        assertEquals(Long.valueOf(20L), vo.getOnlineVersionId());
    }

    @Test
    void online_non_offline_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setStatus("ONLINE");
        when(agentDao.findById(10L)).thenReturn(agent);
        BizException ex = assertThrows(BizException.class, () -> service.online(10L, 100L, 7L));
        assertEquals("14010", ex.getCode());
    }

    @Test
    void online_without_approved_version_throws() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        agent.setStatus("OFFLINE");
        when(agentDao.findById(10L)).thenReturn(agent);
        when(versionDao.listApprovedByAgent(10L)).thenReturn(List.of());
        BizException ex = assertThrows(BizException.class, () -> service.online(10L, 100L, 7L));
        assertEquals("14011", ex.getCode());
    }

    @Test
    void listVersions_returns_summaries() {
        AgentDO agent = new AgentDO();
        agent.setId(10L);
        when(agentDao.findById(10L)).thenReturn(agent);
        AgentVersionDO v1 = new AgentVersionDO();
        v1.setId(20L);
        v1.setVersionNo(1);
        v1.setStatus("APPROVED");
        v1.setRoleName("coder");
        when(versionDao.listByAgent(10L)).thenReturn(List.of(v1));

        List<AgentVersionSummaryVO> result = service.listVersions(10L);
        assertEquals(1, result.size());
        assertEquals(Integer.valueOf(1), result.get(0).getVersionNo());
    }
}
