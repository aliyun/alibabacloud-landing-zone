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
    void agentTextAgentMentionIsDroppedForAgentAuthoredComment() {
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

        verify(agents).findByExactName(1L, "tester");
        verify(comments).insert(any());
        verifyNoInteractions(mentions, guidance);
    }

    @Test
    void agentExplicitAgentTargetIsDroppedSilently() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));

        service.addAgentComment(1L, 77L, 20L, "请复核", List.of(21L), List.of());

        verify(comments).insert(any());
        verifyNoInteractions(mentions, guidance, agents);
    }

    @Test
    void agentMixedTargetsPersistOnlyHumanMention() {
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

        service.addAgentComment(1L, 77L, 20L, "分析完成", List.of(21L), List.of(10000L));

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("HUMAN", mention.getValue().getTargetType());
        assertEquals(10000L, mention.getValue().getTargetRef());
        verify(events).publishEvent(any(WorkitemCommentMentionedEvent.class));
        verifyNoInteractions(guidance);
    }

    @Test
    void agentExplicitAgentTargetDoesNotFallBackToTextHuman() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        UserDao users = mock(UserDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, mock(WorkspaceMemberDao.class), null, mock(ApplicationEventPublisher.class));

        service.addAgentComment(1L, 77L, 20L, "@蔡何 请查看", List.of(21L), List.of());

        verify(comments).insert(any());
        verifyNoInteractions(mentions, guidance, users);
    }

    @Test
    void agentTextAgentMentionDoesNotFallBackToSameNameHuman() {
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
        AgentDO frozen = new AgentDO(); frozen.setId(21L); frozen.setName("tester");
        AgentDO outsider = new AgentDO(); outsider.setId(22L); outsider.setName("tester");
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(agents.findByExactName(1L, "tester")).thenReturn(List.of(frozen, outsider));
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, members, null, events);

        service.addAgentComment(1L, 77L, 20L, "@tester 请复核");

        verify(comments).insert(any());
        verifyNoInteractions(mentions, guidance, events);
        verify(users, never()).findByUsernameOrNickname(anyString());
    }

    @Test
    void humanTextAgentMentionStillQueuesGuidance() {
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
        when(agents.findByExactName(1L, "tester")).thenReturn(List.of(target));
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));

        service.addHumanComment(1L, 77L, 10000L, "@tester 请复核");

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("AGENT", mention.getValue().getTargetType());
        assertEquals(21L, mention.getValue().getTargetRef());
        verify(guidance).createForScheduledRunComment(1L, 77L, 9L, 21L, 10000L);
    }

    @Test
    void humanTextMentionFallsBackToHumanWhenNoAgentMatches() {
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
        UserDO user = new UserDO(); user.setId(20000L); user.setUsername("bob"); user.setNickname("Bob");
        WorkspaceMemberDO member = new WorkspaceMemberDO(); member.setStatus(0);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(agents.findByExactName(1L, "Bob")).thenReturn(List.of());
        when(users.findByUsernameOrNickname("Bob")).thenReturn(List.of(user));
        when(members.findByWorkspaceAndUser(1L, 20000L)).thenReturn(member);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, members, null, events);

        service.addHumanComment(1L, 77L, 10000L, "@Bob 请查看");

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("HUMAN", mention.getValue().getTargetType());
        assertEquals(20000L, mention.getValue().getTargetRef());
        verify(events).publishEvent(any(WorkitemCommentMentionedEvent.class));
        verifyNoInteractions(guidance);
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
    void humanCommentWithExplicitAgentTargetQueuesGuidanceForFrozenParticipant() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        AgentDO target = new AgentDO(); target.setId(21L); target.setName("tester"); target.setTenantId(1L);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(agents.findById(21L)).thenReturn(target);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));

        service.addHumanComment(1L, 77L, 10000L, "请复核失败日志", List.of(21L), List.of());

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("AGENT", mention.getValue().getTargetType());
        assertEquals(21L, mention.getValue().getTargetRef());
        assertEquals("SCHEDULED_TASK_RUN", mention.getValue().getSourceType());
        verify(guidance).createForScheduledRunComment(1L, 77L, 9L, 21L, 10000L);
    }

    @Test
    void humanCommentWithExplicitAgentTargetRejectsNonFrozenParticipant() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        AgentDO outsider = new AgentDO(); outsider.setId(22L); outsider.setName("outsider"); outsider.setTenantId(1L);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(agents.findById(22L)).thenReturn(outsider);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> service.addHumanComment(
                1L, 77L, 10000L, "请复核失败日志", List.of(22L), List.of()));
        assertEquals("guidance target is not a frozen scheduled-run participant", failure.getMessage());
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleIllegalArg(failure);
            assertEquals("10001", response.getCode());
            assertEquals("Guidance target is not a frozen scheduled-run participant", response.getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }

        verify(mentions, never()).insert(any());
        verifyNoInteractions(guidance);
    }

    @Test
    void humanCommentWithHumanTargetsOnlyOverloadNotifiesWithoutGuidance() {
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
        UserDO target = new UserDO(); target.setId(20000L); target.setUsername("bob"); target.setNickname("Bob");
        WorkspaceMemberDO member = new WorkspaceMemberDO(); member.setStatus(0);
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(users.findById(20000L)).thenReturn(target);
        when(members.findByWorkspaceAndUser(1L, 20000L)).thenReturn(member);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, members, null, events);

        service.addHumanComment(1L, 77L, 10000L, "请复核失败日志 @Bob", List.of(20000L));

        ArgumentCaptor<WorkitemCommentMentionDO> mention = ArgumentCaptor.forClass(WorkitemCommentMentionDO.class);
        verify(mentions).insert(mention.capture());
        assertEquals("HUMAN", mention.getValue().getTargetType());
        assertEquals(20000L, mention.getValue().getTargetRef());
        assertEquals("Bob", mention.getValue().getDisplayNameSnapshot());
        verify(events).publishEvent(any(WorkitemCommentMentionedEvent.class));
        verifyNoInteractions(guidance);
    }

    @Test
    void plainHumanCommentWithoutTargetsNeverQueuesGuidance() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));

        service.addHumanComment(1L, 77L, 10000L, "记录一下本次运行很顺利");

        verifyNoInteractions(guidance, mentions);
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
    @Test
    void missingTaskTitleIsRecipientOwnedButAuthoredSameLookingTitleStaysRaw() {
        var runs = mock(ScheduledTaskRunDao.class); var comments = mock(WorkitemCommentDao.class);
        var mentions = mock(WorkitemCommentMentionDao.class); var users = mock(UserDao.class);
        var members = mock(WorkspaceMemberDao.class); var events = mock(ApplicationEventPublisher.class);
        var tasks = mock(ScheduledTaskDao.class);
        var run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L); run.setScheduledTaskId(12L);
        when(runs.findById(1L, 77L)).thenReturn(run);
        var user = new UserDO(); user.setId(10L); user.setUsername("raw");
        var member = new WorkspaceMemberDO(); member.setStatus(0);
        when(users.findById(10L)).thenReturn(user); when(members.findByWorkspaceAndUser(1L, 10L)).thenReturn(member);
        doAnswer(i -> { i.<WorkitemCommentDO>getArgument(0).setId(9L); return null; }).when(comments).insert(any());
        var service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, null, null, null); service.configureHumanMentions(users, members, tasks, events);
        var recipient = new com.aliyun.autowonder.notification.RecipientNotificationTestSupport();
        recipient.preference(10L, "\"en-US\"");
        doAnswer(i -> { new com.aliyun.autowonder.notification.InAppCommentMentionedListener(recipient.service)
                .onMentioned(i.getArgument(0)); return null; }).when(events).publishEvent(any(WorkitemCommentMentionedEvent.class));
        service.addAgentComment(1L, 77L, 20L, "raw {0} <b>", List.of(), List.of(10L));
        var task = new ScheduledTaskDO(); task.setName("定时任务运行 #77");
        when(tasks.findById(1L, 12L)).thenReturn(task);
        service.addAgentComment(1L, 77L, 20L, "raw {0} <b>", List.of(), List.of(10L));
        assertEquals(2, recipient.rows.size());
        verify(tasks, times(2)).findById(1L, 12L);
        assertEquals("AGENT mentioned you in a run comment for Scheduled task “定时任务运行 #77”: raw {0} <b>", recipient.rows.get(1).getContent());
        assertEquals("AGENT mentioned you in a run comment for Scheduled task “Scheduled task run #77”: raw {0} <b>", recipient.rows.get(0).getContent());
    }

    @Test
    void humanCommentResolvesTheDeletedAuthorSnapshotForTheMentionEvent() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        WorkitemCommentDao comments = mock(WorkitemCommentDao.class);
        WorkitemCommentMentionDao mentions = mock(WorkitemCommentMentionDao.class);
        GuidanceService guidance = mock(GuidanceService.class);
        AgentDao agents = mock(AgentDao.class);
        UserDao users = mock(UserDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO(); run.setId(77L); run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":21,\"agentVersionId\":2}]}");
        UserDO author = new UserDO(); author.setId(500L); author.setIsDeleted(1); author.setDeletedUsername("bob");
        UserDO target = new UserDO(); target.setId(10000L); target.setUsername("caihe"); target.setNickname("蔡何");
        when(runs.findById(1L, 77L)).thenReturn(run);
        doAnswer(i -> { ((WorkitemCommentDO) i.getArgument(0)).setId(9L); return null; }).when(comments).insert(any());
        when(users.findById(10000L)).thenReturn(target);
        when(users.findByIdIncludingDeleted(500L)).thenReturn(author);
        ScheduledTaskRunCommentService service = new ScheduledTaskRunCommentService(runs, comments);
        service.configureInteractions(mentions, guidance, agents, mock(RedisManager.class));
        service.configureHumanMentions(users, null, null, events);

        service.addHumanComment(1L, 77L, 500L, "@蔡何 请复核", List.of(), List.of(10000L));

        ArgumentCaptor<WorkitemCommentMentionedEvent> event = ArgumentCaptor.forClass(WorkitemCommentMentionedEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals("HUMAN", event.getValue().actorType());
        assertEquals(500L, event.getValue().actorRef());
        assertEquals("bob", event.getValue().actorDisplayName());
        assertEquals(10000L, event.getValue().recipientUserId());
    }

}
