package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledTaskRunParticipantServiceTest {
    private DispatchDao dispatchDao;
    private AgentDao agentDao;
    private ExecutorDao executorDao;
    private UserDao userDao;
    private PresenceManager presenceManager;
    private ScheduledTaskRunParticipantService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        agentDao = mock(AgentDao.class);
        executorDao = mock(ExecutorDao.class);
        userDao = mock(UserDao.class);
        presenceManager = mock(PresenceManager.class);
        service = new ScheduledTaskRunParticipantService(dispatchDao, agentDao, executorDao, userDao, presenceManager);
    }

    @Test
    void returnsOwnerAndAgentParticipants() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(100L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);
        run.setInitialAgentId(50L);

        // Mock owner
        UserDO user = new UserDO();
        user.setId(10L);
        user.setNickname("Alice");
        user.setUsername("alice");
        when(userDao.findById(10L)).thenReturn(user);

        // Mock agent
        AgentDO agent = new AgentDO();
        agent.setId(50L);
        agent.setName("CodeBot");
        agent.setStatus("ONLINE");
        when(agentDao.findById(50L)).thenReturn(agent);

        // Mock executor online (live presence)
        ExecutorDO executor = new ExecutorDO();
        executor.setId(9001L);
        executor.setStatus("ONLINE");
        when(executorDao.listByAgent(1L, 50L)).thenReturn(List.of(executor));
        when(presenceManager.isExecutorOnline(9001L)).thenReturn(true);

        // Mock dispatch with same agent (should deduplicate)
        DispatchDO dispatch = new DispatchDO();
        dispatch.setAgentId(50L);
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 100L))
                .thenReturn(List.of(dispatch));

        List<ParticipantVO> result = service.getParticipants(1L, run);

        assertEquals(2, result.size());

        // Owner participant
        ParticipantVO owner = result.get(0);
        assertEquals(10L, owner.getUserId());
        assertEquals("HUMAN", owner.getTargetType());
        assertEquals("Alice", owner.getName());
        assertEquals("alice", owner.getDisplayId());
        assertFalse(owner.isAgent());

        // Agent participant
        ParticipantVO agentP = result.get(1);
        assertEquals(50L, agentP.getUserId());
        assertEquals("AGENT", agentP.getTargetType());
        assertEquals("CodeBot", agentP.getName());
        assertTrue(agentP.isAgent());
        assertTrue(agentP.isOnline());
        assertEquals("ONLINE", agentP.getExecutorStatus());
    }

    @Test
    void returnsMultipleAgentsFromDispatches() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(200L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);
        run.setInitialAgentId(50L);

        UserDO user = new UserDO();
        user.setId(10L);
        user.setNickname("Bob");
        user.setUsername("bob");
        when(userDao.findById(10L)).thenReturn(user);

        AgentDO agent1 = new AgentDO();
        agent1.setId(50L);
        agent1.setName("Agent-A");
        agent1.setStatus("ONLINE");
        when(agentDao.findById(50L)).thenReturn(agent1);

        AgentDO agent2 = new AgentDO();
        agent2.setId(60L);
        agent2.setName("Agent-B");
        agent2.setStatus("ONLINE");
        when(agentDao.findById(60L)).thenReturn(agent2);

        when(executorDao.listByAgent(1L, 50L)).thenReturn(List.of());
        ExecutorDO busyExecutor = new ExecutorDO();
        busyExecutor.setId(9002L);
        busyExecutor.setStatus("BUSY");
        when(executorDao.listByAgent(1L, 60L)).thenReturn(List.of(busyExecutor));
        when(presenceManager.isExecutorOnline(9002L)).thenReturn(true);

        DispatchDO dispatch1 = new DispatchDO();
        dispatch1.setAgentId(50L);
        DispatchDO dispatch2 = new DispatchDO();
        dispatch2.setAgentId(60L);
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 200L))
                .thenReturn(List.of(dispatch1, dispatch2));

        List<ParticipantVO> result = service.getParticipants(1L, run);

        assertEquals(3, result.size());
        assertEquals("Bob", result.get(0).getName());
        assertEquals("Agent-A", result.get(1).getName());
        assertFalse(result.get(1).isOnline());
        assertEquals("OFFLINE", result.get(1).getExecutorStatus());
        assertEquals("Agent-B", result.get(2).getName());
        assertTrue(result.get(2).isOnline());
        assertEquals("BUSY", result.get(2).getExecutorStatus());
    }

    @Test
    void emptyDispatchesReturnsOnlyOwnerAndInitialAgent() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(300L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);
        run.setInitialAgentId(50L);

        UserDO user = new UserDO();
        user.setId(10L);
        user.setNickname("Carol");
        user.setUsername("carol");
        when(userDao.findById(10L)).thenReturn(user);

        AgentDO agent = new AgentDO();
        agent.setId(50L);
        agent.setName("InitAgent");
        agent.setStatus("ONLINE");
        when(agentDao.findById(50L)).thenReturn(agent);
        when(executorDao.listByAgent(1L, 50L)).thenReturn(List.of());

        // Empty dispatches
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 300L))
                .thenReturn(List.of());

        List<ParticipantVO> result = service.getParticipants(1L, run);

        assertEquals(2, result.size());
        assertEquals("Carol", result.get(0).getName());
        assertEquals("HUMAN", result.get(0).getTargetType());
        assertEquals("InitAgent", result.get(1).getName());
        assertEquals("AGENT", result.get(1).getTargetType());
        assertFalse(result.get(1).isOnline());
    }

    @Test
    void nullDispatchesHandledGracefully() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(400L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);
        run.setInitialAgentId(null); // no initial agent

        UserDO user = new UserDO();
        user.setId(10L);
        user.setUsername("dave");
        when(userDao.findById(10L)).thenReturn(user);

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 400L))
                .thenReturn(null);

        List<ParticipantVO> result = service.getParticipants(1L, run);

        assertEquals(1, result.size());
        assertEquals("dave", result.get(0).getName()); // falls back to username when no nickname
    }

    @Test
    void livePresenceOverridesStaleDbExecutorStatus() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(500L);
        run.setWorkspaceId(1L);
        run.setOwnerId(null);
        run.setInitialAgentId(null);

        AgentDO staleOnlineAgent = new AgentDO();
        staleOnlineAgent.setId(70L);
        staleOnlineAgent.setName("StaleOnlineAgent");
        staleOnlineAgent.setStatus("ONLINE");
        when(agentDao.findById(70L)).thenReturn(staleOnlineAgent);

        AgentDO staleOfflineAgent = new AgentDO();
        staleOfflineAgent.setId(71L);
        staleOfflineAgent.setName("StaleOfflineAgent");
        staleOfflineAgent.setStatus("ONLINE");
        when(agentDao.findById(71L)).thenReturn(staleOfflineAgent);

        // DB says ONLINE but live presence is gone -> must render OFFLINE
        ExecutorDO staleExecutor = new ExecutorDO();
        staleExecutor.setId(9003L);
        staleExecutor.setStatus("ONLINE");
        when(executorDao.listByAgent(1L, 70L)).thenReturn(List.of(staleExecutor));
        when(presenceManager.isExecutorOnline(9003L)).thenReturn(false);

        // DB stale but executor is actually registered in live presence -> must render ONLINE
        ExecutorDO liveExecutor = new ExecutorDO();
        liveExecutor.setId(9004L);
        liveExecutor.setStatus("OFFLINE");
        when(executorDao.listByAgent(1L, 71L)).thenReturn(List.of(liveExecutor));
        when(presenceManager.isExecutorOnline(9004L)).thenReturn(true);

        DispatchDO dispatch1 = new DispatchDO();
        dispatch1.setAgentId(70L);
        DispatchDO dispatch2 = new DispatchDO();
        dispatch2.setAgentId(71L);
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 500L))
                .thenReturn(List.of(dispatch1, dispatch2));

        List<ParticipantVO> result = service.getParticipants(1L, run);

        assertEquals(2, result.size());
        ParticipantVO stale = result.stream().filter(p -> p.getUserId() == 70L).findFirst().orElseThrow();
        assertFalse(stale.isOnline());
        assertEquals("OFFLINE", stale.getExecutorStatus());
        ParticipantVO live = result.stream().filter(p -> p.getUserId() == 71L).findFirst().orElseThrow();
        assertTrue(live.isOnline());
        assertEquals("ONLINE", live.getExecutorStatus());
    }
}
