package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledRunMentionCandidateVO;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledTaskRunParticipantServiceTest {
    private DispatchDao dispatchDao;
    private AgentDao agentDao;
    private ExecutorDao executorDao;
    private UserDao userDao;
    private PresenceManager presenceManager;
    private WorkspaceMemberDao workspaceMemberDao;
    private ScheduledTaskRunParticipantService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        agentDao = mock(AgentDao.class);
        executorDao = mock(ExecutorDao.class);
        userDao = mock(UserDao.class);
        presenceManager = mock(PresenceManager.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        service = new ScheduledTaskRunParticipantService(dispatchDao, agentDao, executorDao, userDao,
                presenceManager, workspaceMemberDao);
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
    void mentionCandidatesExposeFrozenAgentsAndDisableNonFrozenParticipants() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(600L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);
        run.setInitialAgentId(50L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":50,\"agentVersionId\":2}]}");

        UserDO owner = new UserDO();
        owner.setId(10L);
        owner.setUsername("alice");
        owner.setNickname("Alice");
        when(userDao.findById(10L)).thenReturn(owner);

        AgentDO frozen = new AgentDO();
        frozen.setId(50L);
        frozen.setName("CodeBot");
        AgentDO late = new AgentDO();
        late.setId(60L);
        late.setName("LateBot");
        when(agentDao.findById(50L)).thenReturn(frozen);
        when(agentDao.findById(60L)).thenReturn(late);

        DispatchDO dispatch = new DispatchDO();
        dispatch.setAgentId(60L);
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 600L))
                .thenReturn(List.of(dispatch));
        when(executorDao.listByAgent(1L, 50L)).thenReturn(List.of());
        when(executorDao.listByAgent(1L, 60L)).thenReturn(List.of());
        when(workspaceMemberDao.listByTenant(1L)).thenReturn(List.of());

        List<ScheduledRunMentionCandidateVO> result = service.getMentionCandidates(1L, run, null, 50);

        assertEquals(3, result.size());
        assertTrue(findCandidate(result, "HUMAN", 10L).isMentionable());
        ScheduledRunMentionCandidateVO frozenCandidate = findCandidate(result, "AGENT", 50L);
        assertTrue(frozenCandidate.isMentionable());
        assertNull(frozenCandidate.getMentionDisabledReason());
        ScheduledRunMentionCandidateVO lateCandidate = findCandidate(result, "AGENT", 60L);
        assertFalse(lateCandidate.isMentionable());
        assertNotNull(lateCandidate.getMentionDisabledReason());
    }

    @Test
    void frozenAgentWithoutDispatchIsStillMentionable() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(700L);
        run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":70}]}");

        AgentDO frozen = new AgentDO();
        frozen.setId(70L);
        frozen.setName("FrozenBot");
        when(agentDao.findById(70L)).thenReturn(frozen);
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 700L)).thenReturn(List.of());
        when(executorDao.listByAgent(1L, 70L)).thenReturn(List.of());
        when(workspaceMemberDao.listByTenant(1L)).thenReturn(null);

        List<ScheduledRunMentionCandidateVO> result = service.getMentionCandidates(1L, run, null, 50);

        assertEquals(1, result.size());
        ScheduledRunMentionCandidateVO candidate = findCandidate(result, "AGENT", 70L);
        assertTrue(candidate.isMentionable());
        assertFalse(candidate.isOnline());
        assertEquals("OFFLINE", candidate.getExecutorStatus());
    }

    @Test
    void mentionCandidatesFilterByQueryAndDeduplicateAgainstParticipants() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(800L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);

        UserDO owner = new UserDO();
        owner.setId(10L);
        owner.setUsername("alice");
        owner.setNickname("Alice");
        when(userDao.findById(10L)).thenReturn(owner);
        UserDO bob = new UserDO();
        bob.setId(20L);
        bob.setUsername("bob");
        bob.setNickname("Bob");
        when(userDao.findById(20L)).thenReturn(bob);
        UserDO carol = new UserDO();
        carol.setId(21L);
        carol.setUsername("carol");
        carol.setNickname("Carol");
        when(userDao.findById(21L)).thenReturn(carol);

        WorkspaceMemberDO ownerMember = new WorkspaceMemberDO();
        ownerMember.setUserId(10L);
        ownerMember.setStatus(0);
        WorkspaceMemberDO bobMember = new WorkspaceMemberDO();
        bobMember.setUserId(20L);
        bobMember.setStatus(0);
        WorkspaceMemberDO carolMember = new WorkspaceMemberDO();
        carolMember.setUserId(21L);
        carolMember.setStatus(0);
        when(workspaceMemberDao.listByTenant(1L)).thenReturn(List.of(ownerMember, bobMember, carolMember));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 800L)).thenReturn(List.of());

        List<ScheduledRunMentionCandidateVO> limited = service.getMentionCandidates(1L, run, null, 2);
        assertEquals(2, limited.size());
        assertEquals(10L, limited.get(0).getUserId());
        assertEquals(20L, limited.get(1).getUserId());

        List<ScheduledRunMentionCandidateVO> queried = service.getMentionCandidates(1L, run, "car", 50);
        assertEquals(1, queried.size());
        assertEquals(21L, queried.get(0).getUserId());
    }

    private static ScheduledRunMentionCandidateVO findCandidate(List<ScheduledRunMentionCandidateVO> candidates,
                                                                String targetType, long userId) {
        return candidates.stream()
                .filter(candidate -> targetType.equals(candidate.getTargetType()) && candidate.getUserId() == userId)
                .findFirst().orElseThrow();
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

    @Test
    void mentionCandidateLimitDefaultsWhenNonPositiveAndCapsAtHundred() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(980L);
        run.setWorkspaceId(1L);

        List<WorkspaceMemberDO> members = new ArrayList<>();
        for (long userId = 1L; userId <= 120L; userId++) {
            WorkspaceMemberDO member = new WorkspaceMemberDO();
            member.setUserId(userId);
            member.setStatus(0);
            members.add(member);
        }
        when(workspaceMemberDao.listByTenant(1L)).thenReturn(members);
        when(userDao.findById(anyLong())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            UserDO user = new UserDO();
            user.setId(userId);
            user.setUsername("user-" + userId);
            return user;
        });
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 980L)).thenReturn(List.of());

        assertEquals(50, service.getMentionCandidates(1L, run, null, 0).size());
        assertEquals(50, service.getMentionCandidates(1L, run, null, -5).size());
        assertEquals(100, service.getMentionCandidates(1L, run, null, 1000).size());
        assertEquals(3, service.getMentionCandidates(1L, run, null, 3).size());
    }

    @Test
    void mentionCandidatesSkipNullParticipantsAndResolveAgentByTargetType() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(960L);
        run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":60}]}");

        ParticipantVO nullUserId = new ParticipantVO();
        nullUserId.setTargetType("HUMAN");
        nullUserId.setName("Ghost");
        ParticipantVO agentByTargetType = new ParticipantVO();
        agentByTargetType.setUserId(60L);
        agentByTargetType.setTargetType("AGENT");
        agentByTargetType.setName("TypeOnlyAgent");
        agentByTargetType.setOnline(true);
        agentByTargetType.setExecutorStatus("ONLINE");

        ScheduledTaskRunParticipantService spied = spy(service);
        doReturn(Arrays.asList(null, nullUserId, agentByTargetType)).when(spied).getParticipants(1L, run);

        AgentDO agent = new AgentDO();
        agent.setId(60L);
        agent.setName("TypeOnlyAgent");
        when(agentDao.findById(60L)).thenReturn(agent);
        ExecutorDO executor = new ExecutorDO();
        executor.setId(9200L);
        executor.setStatus("ONLINE");
        when(executorDao.listByAgent(1L, 60L)).thenReturn(List.of(executor));
        when(presenceManager.isExecutorOnline(9200L)).thenReturn(true);

        List<ScheduledRunMentionCandidateVO> result = spied.getMentionCandidates(1L, run, null, 50);

        assertEquals(1, result.size());
        ScheduledRunMentionCandidateVO candidate = findCandidate(result, "AGENT", 60L);
        assertEquals("TypeOnlyAgent", candidate.getName());
        assertTrue(candidate.isMentionable());
        assertNull(candidate.getMentionDisabledReason());
        assertTrue(candidate.isOnline());
        assertEquals("ONLINE", candidate.getExecutorStatus());
    }

    @Test
    void frozenAgentsMissingFromAgentDaoAreSkipped() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(970L);
        run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21},{\"agentId\":22}]}");

        AgentDO existing = new AgentDO();
        existing.setId(21L);
        existing.setName("KnownBot");
        when(agentDao.findById(21L)).thenReturn(existing);
        when(agentDao.findById(22L)).thenReturn(null);
        when(executorDao.listByAgent(1L, 21L)).thenReturn(List.of());
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 970L)).thenReturn(List.of());

        List<ScheduledRunMentionCandidateVO> result = service.getMentionCandidates(1L, run, null, 50);

        assertEquals(1, result.size());
        assertEquals(21L, result.get(0).getUserId());
        assertEquals("KnownBot", result.get(0).getName());
    }

    @Test
    void workspaceMemberFilteringSkipsInvalidEntriesAndFallsBackToUsername() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(990L);
        run.setWorkspaceId(1L);

        WorkspaceMemberDO noUser = new WorkspaceMemberDO();
        noUser.setUserId(null);
        noUser.setStatus(0);
        WorkspaceMemberDO noStatus = new WorkspaceMemberDO();
        noStatus.setUserId(20L);
        WorkspaceMemberDO disabled = new WorkspaceMemberDO();
        disabled.setUserId(21L);
        disabled.setStatus(1);
        WorkspaceMemberDO missingUser = new WorkspaceMemberDO();
        missingUser.setUserId(22L);
        missingUser.setStatus(0);
        WorkspaceMemberDO nicknameMissing = new WorkspaceMemberDO();
        nicknameMissing.setUserId(23L);
        nicknameMissing.setStatus(0);
        WorkspaceMemberDO nicknameBlank = new WorkspaceMemberDO();
        nicknameBlank.setUserId(24L);
        nicknameBlank.setStatus(0);
        WorkspaceMemberDO nullUsername = new WorkspaceMemberDO();
        nullUsername.setUserId(25L);
        nullUsername.setStatus(0);
        WorkspaceMemberDO blankUsername = new WorkspaceMemberDO();
        blankUsername.setUserId(26L);
        blankUsername.setStatus(0);

        when(workspaceMemberDao.listByTenant(1L)).thenReturn(Arrays.asList(
                null, noUser, noStatus, disabled, missingUser, nicknameMissing, nicknameBlank,
                nullUsername, blankUsername));

        UserDO dave = new UserDO();
        dave.setId(23L);
        dave.setUsername("dave");
        when(userDao.findById(23L)).thenReturn(dave);
        UserDO blanky = new UserDO();
        blanky.setId(24L);
        blanky.setUsername("blanky");
        blanky.setNickname("   ");
        when(userDao.findById(24L)).thenReturn(blanky);
        UserDO ghost = new UserDO();
        ghost.setUsername("ghost");
        when(userDao.findById(25L)).thenReturn(ghost);
        UserDO unnamed = new UserDO();
        unnamed.setId(26L);
        when(userDao.findById(26L)).thenReturn(unnamed);

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 990L)).thenReturn(List.of());

        List<ScheduledRunMentionCandidateVO> result = service.getMentionCandidates(1L, run, null, 50);

        assertEquals(2, result.size());
        ScheduledRunMentionCandidateVO daveCandidate = findCandidate(result, "HUMAN", 23L);
        assertEquals("dave", daveCandidate.getName());
        assertEquals("dave", daveCandidate.getDisplayId());
        ScheduledRunMentionCandidateVO blankyCandidate = findCandidate(result, "HUMAN", 24L);
        assertEquals("blanky", blankyCandidate.getName());
        assertTrue(blankyCandidate.isMentionable());
    }

    @Test
    void nullWorkspaceMemberDaoSkipsMemberSuggestions() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(995L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);

        UserDO owner = new UserDO();
        owner.setId(10L);
        owner.setUsername("alice");
        owner.setNickname("Alice");
        when(userDao.findById(10L)).thenReturn(owner);
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 995L)).thenReturn(List.of());

        ScheduledTaskRunParticipantService withoutMembers = new ScheduledTaskRunParticipantService(
                dispatchDao, agentDao, executorDao, userDao, presenceManager, null);

        List<ScheduledRunMentionCandidateVO> result = withoutMembers.getMentionCandidates(1L, run, null, 50);

        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).getName());
        assertEquals("HUMAN", result.get(0).getTargetType());
    }

    @Test
    void queryFilteringHandlesBlankQueriesAndDisplayIdHits() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(996L);
        run.setWorkspaceId(1L);
        run.setOwnerId(10L);

        UserDO owner = new UserDO();
        owner.setId(10L);
        owner.setUsername("alice");
        owner.setNickname("Alice");
        when(userDao.findById(10L)).thenReturn(owner);

        WorkspaceMemberDO danaMember = new WorkspaceMemberDO();
        danaMember.setUserId(30L);
        danaMember.setStatus(0);
        WorkspaceMemberDO eveMember = new WorkspaceMemberDO();
        eveMember.setUserId(31L);
        eveMember.setStatus(0);
        when(workspaceMemberDao.listByTenant(1L)).thenReturn(List.of(danaMember, eveMember));
        UserDO dana = new UserDO();
        dana.setId(30L);
        dana.setUsername("u-40013");
        dana.setNickname("Dana");
        when(userDao.findById(30L)).thenReturn(dana);
        UserDO eve = new UserDO();
        eve.setId(31L);
        eve.setNickname("Eve");
        when(userDao.findById(31L)).thenReturn(eve);

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 996L)).thenReturn(List.of());

        assertEquals(3, service.getMentionCandidates(1L, run, "   ", 50).size());

        List<ScheduledRunMentionCandidateVO> hits = service.getMentionCandidates(1L, run, "40013", 50);
        assertEquals(1, hits.size());
        assertEquals(30L, hits.get(0).getUserId());
        assertEquals("Dana", hits.get(0).getName());
    }

    @Test
    void executorLookupToleratesNullListAndNullElements() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(998L);
        run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":70},{\"agentId\":71}]}");

        AgentDO agent70 = new AgentDO();
        agent70.setId(70L);
        agent70.setName("QuietBot");
        AgentDO agent71 = new AgentDO();
        agent71.setId(71L);
        agent71.setName("LiveBot");
        when(agentDao.findById(70L)).thenReturn(agent70);
        when(agentDao.findById(71L)).thenReturn(agent71);

        when(executorDao.listByAgent(1L, 70L)).thenReturn(null);
        ExecutorDO noId = new ExecutorDO();
        ExecutorDO online = new ExecutorDO();
        online.setId(9300L);
        online.setStatus("ONLINE");
        when(executorDao.listByAgent(1L, 71L)).thenReturn(Arrays.asList(null, noId, online));
        when(presenceManager.isExecutorOnline(9300L)).thenReturn(true);

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 998L)).thenReturn(List.of());

        List<ScheduledRunMentionCandidateVO> result = service.getMentionCandidates(1L, run, null, 50);

        assertEquals(2, result.size());
        ScheduledRunMentionCandidateVO quiet = findCandidate(result, "AGENT", 70L);
        assertFalse(quiet.isOnline());
        assertEquals("OFFLINE", quiet.getExecutorStatus());
        ScheduledRunMentionCandidateVO live = findCandidate(result, "AGENT", 71L);
        assertTrue(live.isOnline());
        assertEquals("ONLINE", live.getExecutorStatus());
    }
}
