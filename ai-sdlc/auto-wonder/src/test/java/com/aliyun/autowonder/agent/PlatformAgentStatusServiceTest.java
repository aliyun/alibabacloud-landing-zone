package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.agent.dto.PlatformAgentStatusVO;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAgentStatusServiceTest {

    AgentDao agentDao;
    ExecutorDao executorDao;
    ExecutorRegistry executorRegistry;
    PlatformAgentStatusService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        executorDao = mock(ExecutorDao.class);
        executorRegistry = mock(ExecutorRegistry.class);
        service = new PlatformAgentStatusService(agentDao, executorDao, executorRegistry);
    }

    private AgentDO platformAgent(long id) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setName(PlatformAgentSeeder.PLATFORM_AGENT_NAME);
        agent.setKind(PlatformAgentSeeder.PLATFORM_KIND);
        return agent;
    }

    private ExecutorDO executor(Long id) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        return executor;
    }

    @Test
    void not_configured_when_platform_agent_missing() {
        when(agentDao.findPlatformAgent(100L)).thenReturn(null);

        PlatformAgentStatusVO vo = service.getStatus(100L);

        assertEquals(PlatformAgentStatusVO.STATE_NOT_CONFIGURED, vo.getState());
        assertNull(vo.getAgentId());
        assertEquals(0, vo.getExecutorCount());
        assertEquals(0, vo.getOnlineExecutorCount());
        verify(executorDao, never()).listByAgent(anyLong(), anyLong());
    }

    @Test
    void not_configured_when_agent_has_no_executor() {
        when(agentDao.findPlatformAgent(100L)).thenReturn(platformAgent(11L));
        when(executorDao.listByAgent(100L, 11L)).thenReturn(List.of());

        PlatformAgentStatusVO vo = service.getStatus(100L);

        assertEquals(PlatformAgentStatusVO.STATE_NOT_CONFIGURED, vo.getState());
        assertEquals(11L, vo.getAgentId());
        assertEquals(0, vo.getExecutorCount());
        assertEquals(0, vo.getOnlineExecutorCount());
        verify(executorRegistry, never()).isOnline(anyLong());
    }

    @Test
    void not_configured_when_executor_query_returns_null() {
        when(agentDao.findPlatformAgent(100L)).thenReturn(platformAgent(11L));
        when(executorDao.listByAgent(100L, 11L)).thenReturn(null);

        PlatformAgentStatusVO vo = service.getStatus(100L);

        assertEquals(PlatformAgentStatusVO.STATE_NOT_CONFIGURED, vo.getState());
        assertEquals(11L, vo.getAgentId());
        assertEquals(0, vo.getExecutorCount());
    }

    @Test
    void offline_when_no_executor_is_online() {
        when(agentDao.findPlatformAgent(100L)).thenReturn(platformAgent(11L));
        when(executorDao.listByAgent(100L, 11L)).thenReturn(List.of(executor(21L), executor(22L)));
        when(executorRegistry.isOnline(anyLong())).thenReturn(false);

        PlatformAgentStatusVO vo = service.getStatus(100L);

        assertEquals(PlatformAgentStatusVO.STATE_OFFLINE, vo.getState());
        assertEquals(11L, vo.getAgentId());
        assertEquals(2, vo.getExecutorCount());
        assertEquals(0, vo.getOnlineExecutorCount());
        verify(executorRegistry).isOnline(21L);
        verify(executorRegistry).isOnline(22L);
    }

    @Test
    void ok_when_at_least_one_executor_is_online() {
        when(agentDao.findPlatformAgent(100L)).thenReturn(platformAgent(11L));
        when(executorDao.listByAgent(100L, 11L)).thenReturn(List.of(executor(21L), executor(22L), executor(23L)));
        when(executorRegistry.isOnline(21L)).thenReturn(false);
        when(executorRegistry.isOnline(22L)).thenReturn(true);
        when(executorRegistry.isOnline(23L)).thenReturn(true);

        PlatformAgentStatusVO vo = service.getStatus(100L);

        assertEquals(PlatformAgentStatusVO.STATE_OK, vo.getState());
        assertEquals(3, vo.getExecutorCount());
        assertEquals(2, vo.getOnlineExecutorCount());
    }

    @Test
    void executor_without_id_is_skipped_for_online_probe() {
        when(agentDao.findPlatformAgent(100L)).thenReturn(platformAgent(11L));
        when(executorDao.listByAgent(100L, 11L)).thenReturn(List.of(executor(null), executor(22L)));
        when(executorRegistry.isOnline(22L)).thenReturn(false);

        PlatformAgentStatusVO vo = service.getStatus(100L);

        assertEquals(PlatformAgentStatusVO.STATE_OFFLINE, vo.getState());
        assertEquals(2, vo.getExecutorCount());
        assertEquals(0, vo.getOnlineExecutorCount());
        verify(executorRegistry, times(1)).isOnline(anyLong());
        verify(executorRegistry).isOnline(22L);
    }
}
