package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDao;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDO;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledTaskRunInteractionTest {
    @Test
    void agentCommentIsStrictlyOwnedByScheduledRun() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());

        var out = new ScheduledTaskRunCommentService(runs, comments)
                .addAgentComment(1L, 77L, 20L, "分析完成");

        ArgumentCaptor<WorkitemCommentDO> captured = ArgumentCaptor.forClass(WorkitemCommentDO.class);
        verify(comments).insert(captured.capture());
        assertEquals(ExecutionSourceType.SCHEDULED_TASK_RUN.name(), captured.getValue().getSourceType());
        assertEquals(77L, captured.getValue().getWorkitemId());
        assertEquals(9L, out.getId());
    }

    @Test
    void rejectsCrossTenantRunBeforeWriting() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        assertThrows(RuntimeException.class, () -> new ScheduledTaskRunCommentService(runs, comments)
                .addAgentComment(1L, 77L, 20L, "x"));
        verify(comments, never()).insert(any());
    }

    @Test
    void agentMentionPersistsRunMentionAndQueuesRunGuidance() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        AgentDO target = new AgentDO(); target.setId(21L); target.setName("tester");
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(agents.findByExactName(1L, "tester")).thenReturn(java.util.List.of(target));
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));

        service.addAgentComment(1L, 77L, 20L, "@tester 请复核");

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("SCHEDULED_TASK_RUN", mention.getValue().getSourceType());
        verify(guidance).createForScheduledRunComment(1L, 77L, 9L, 21L, 20L);
    }

    @Test
    void explicitHumanTargetPersistsHumanMentionAndPublishesNotification() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        UserDao users = mock(UserDao.class);
        WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        UserDO user = new UserDO(); user.setId(10000L); user.setUsername("caihe"); user.setNickname("蔡何");
        WorkspaceMemberDO member = new WorkspaceMemberDO(); member.setStatus(0);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(users.findById(10000L)).thenReturn(user);
        when(members.findByWorkspaceAndUser(1L, 10000L)).thenReturn(member);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, members, null, events);

        service.addAgentComment(1L, 77L, 20L, "分析完成 @蔡何", List.of(), List.of(10000L));

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("SCHEDULED_TASK_RUN", mention.getValue().getSourceType());
        assertEquals(77L, mention.getValue().getWorkitemId());
        assertEquals("HUMAN", mention.getValue().getTargetType());
        assertEquals(10000L, mention.getValue().getTargetRef());
        assertEquals("蔡何", mention.getValue().getDisplayNameSnapshot());
        verify(events).publishEvent(any(WorkitemCommentMentionedEvent.class));
        verifyNoInteractions(guidance);
    }

    @Test
    void explicitHumanTargetRejectsNonWorkspaceMember() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        UserDao users = mock(UserDao.class);
        WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(members.findByWorkspaceAndUser(1L, 10000L)).thenReturn(null);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, members, null, events);

        assertThrows(BizException.class, () -> service.addAgentComment(1L, 77L, 20L,
                "分析完成 @蔡何", List.of(), List.of(10000L)));

        verify(mentions, never()).insert(any());
        verifyNoInteractions(events, guidance);
    }

    @Test
    void autoParseFallsBackToHumanWhenAgentNameDoesNotMatch() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        UserDao users = mock(UserDao.class);
        WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        UserDO user = new UserDO(); user.setId(10000L); user.setUsername("caihe"); user.setNickname("蔡何");
        WorkspaceMemberDO member = new WorkspaceMemberDO(); member.setStatus(0);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(agents.findByExactName(1L, "蔡何")).thenReturn(List.of());
        when(users.findByUsernameOrNickname("蔡何")).thenReturn(List.of(user));
        when(members.findByWorkspaceAndUser(1L, 10000L)).thenReturn(member);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, members, null, events);

        service.addAgentComment(1L, 77L, 20L, "@蔡何 请查看");

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("HUMAN", mention.getValue().getTargetType());
        assertEquals(10000L, mention.getValue().getTargetRef());
        verify(events).publishEvent(any(WorkitemCommentMentionedEvent.class));
        verifyNoInteractions(guidance);
    }
}
