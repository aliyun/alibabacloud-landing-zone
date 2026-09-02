package com.aliyun.autowonder.guidance;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunCommentService;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuidanceServiceTest {
    private GuidanceDao guidanceDao;
    private DispatchDao dispatchDao;
    private AgentDao agentDao;
    private GuidanceTransport transport;
    private GuidanceService service;
    private WorkitemService workitemService;
    private DispatchService dispatchService;
    private AgentSdlcResolver sdlcResolver;
    private ApplicationEventPublisher eventPublisher;
    private WorkitemCommentDao commentDao;

    @BeforeEach
    void setUp() {
        guidanceDao = mock(GuidanceDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        agentDao = mock(AgentDao.class);
        dispatchDao = mock(DispatchDao.class);
        transport = mock(GuidanceTransport.class);
        workitemService = mock(WorkitemService.class);
        dispatchService = mock(DispatchService.class);
        sdlcResolver = mock(AgentSdlcResolver.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new GuidanceService(guidanceDao, workitemDao, commentDao, agentDao, dispatchDao, transport,
                workitemService, dispatchService, sdlcResolver, eventPublisher);

        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(50L);
        workitem.setTenantId(100L);
        when(workitemDao.findById(50L)).thenReturn(workitem);
        AgentDO agent = new AgentDO();
        agent.setId(40013L);
        agent.setTenantId(100L);
        agent.setName("AW全栈开发");
        agent.setOnlineVersionId(40033L);
        when(agentDao.findById(40013L)).thenReturn(agent);
        when(sdlcResolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO interactionStep = new SdlcStepDO();
        interactionStep.setId(811L);
        interactionStep.setTenantId(100L);
        when(sdlcResolver.firstStep(100L, 810L)).thenReturn(interactionStep);
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setId(600L);
        comment.setTenantId(100L);
        comment.setWorkitemId(50L);
        comment.setContentMd("comment body from canonical table");
        when(commentDao.findById(100L, 600L)).thenReturn(comment);
        doAnswer(invocation -> {
            GuidanceDO row = invocation.getArgument(0);
            row.setId(701L);
            return null;
        }).when(guidanceDao).insert(any(GuidanceDO.class));
        when(guidanceDao.bindPendingDispatch(anyLong(), anyLong(), anyLong())).thenReturn(1);
        CommentVO acknowledgement = new CommentVO();
        acknowledgement.setId(901L);
        when(workitemService.addAgentComment(anyLong(), eq("收到，已转入正式工作流程。"),
                anyLong(), anyLong())).thenReturn(acknowledgement);
        when(guidanceDao.bindReplyComment(701L, 100L, 901L)).thenReturn(1);
        DispatchDO formal = dispatch(93L, "PENDING");
        when(dispatchService.enqueue(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong()))
                .thenReturn(formal);
    }

    @Test
    void commentTargetsExplicitAgentsBeforeParsingText() {
        service.createForComment(100L, 50L, 600L, "@AW测试工程师 请执行", List.of(40013L), 7L);

        verify(guidanceDao).insert(argThat(row -> Long.valueOf(40013L).equals(row.getTargetAgentId())));
        verify(workitemService, never()).getParticipants(anyLong(), anyLong());
    }

    @Test
    void inboundAcknowledgementOwnerUsesBoundDispatchWhenGuidanceSourceIsStale() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(77L);
        guidance.setTenantId(100L);
        guidance.setWorkitemId(200L);
        guidance.setSourceType(ExecutionSourceType.WORKITEM.name());
        guidance.setDispatchId(88L);
        guidance.setExecutorId(10005L);
        DispatchDO dispatch = dispatch(88L, "RUNNING");
        dispatch.setWorkitemId(200L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(guidanceDao.findById(77L)).thenReturn(guidance);
        when(dispatchDao.findById(88L)).thenReturn(dispatch);

        GuidanceService.InboundAcknowledgementBinding binding =
                service.bindingForInboundAcknowledgement(100L, 10005L, 77L);
        assertEquals(88L, binding.dispatchId());
        assertEquals(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, 200L), binding.owner());
    }

    @Test
    void inboundAcknowledgementOwnerRejectsForeignGuidance() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(77L);
        guidance.setTenantId(101L);
        guidance.setWorkitemId(200L);
        when(guidanceDao.findById(77L)).thenReturn(guidance);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.bindingForInboundAcknowledgement(100L, 10005L, 77L));
    }

    @Test
    void inboundAcknowledgementOwnerRejectsMismatchedBoundDispatch() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(77L);
        guidance.setTenantId(100L);
        guidance.setWorkitemId(200L);
        guidance.setDispatchId(88L);
        guidance.setExecutorId(10005L);
        DispatchDO unrelated = dispatch(88L, "RUNNING");
        unrelated.setWorkitemId(201L);
        when(guidanceDao.findById(77L)).thenReturn(guidance);
        when(dispatchDao.findById(88L)).thenReturn(unrelated);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.bindingForInboundAcknowledgement(100L, 10005L, 77L));
    }

    @Test
    void inboundAcknowledgementBindingRejectsDifferentExecutor() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(77L);
        guidance.setTenantId(100L);
        guidance.setWorkitemId(200L);
        guidance.setDispatchId(88L);
        guidance.setExecutorId(10006L);
        DispatchDO dispatch = dispatch(88L, "RUNNING");
        dispatch.setWorkitemId(200L);
        when(guidanceDao.findById(77L)).thenReturn(guidance);
        when(dispatchDao.findById(88L)).thenReturn(dispatch);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.bindingForInboundAcknowledgement(100L, 10005L, 77L));
    }

    @Test
    void runGuidanceDeliveryUsesRunSourceEvenWhenWorkitemHasSameId() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L); guidance.setTenantId(100L); guidance.setSourceType("SCHEDULED_TASK_RUN");
        guidance.setWorkitemId(50L); guidance.setCommentId(600L); guidance.setTargetAgentId(40013L);
        DispatchDO dispatch = dispatch(93L, "PENDING");
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        dispatch.setWorkitemId(50L); dispatch.setExecutorId(9L); dispatch.setAgentId(40013L);
        dispatch.setResumeMode("CANONICAL_INTERACTION");
        WorkitemCommentDO runComment = new WorkitemCommentDO();
        runComment.setId(600L); runComment.setTenantId(100L); runComment.setWorkitemId(50L);
        runComment.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name()); runComment.setContentMd("run only");
        when(guidanceDao.listQueuedForDispatch(100L, 93L)).thenReturn(List.of(guidance));
        when(guidanceDao.bindDispatch(701L, 100L, 93L, 9L)).thenReturn(1);
        when(commentDao.findBySourceAndId(100L, "SCHEDULED_TASK_RUN", 50L, 600L)).thenReturn(runComment);

        service.deliverQueued(dispatch);

        verify(commentDao).findBySourceAndId(100L, "SCHEDULED_TASK_RUN", 50L, 600L);
        verify(commentDao, never()).findById(anyLong(), anyLong());
        verify(transport).send(guidance, "run only");
    }

    @Test
    void runGuidancePinsTargetFrozenVersionBeforeItsPendingDispatchCanPackage() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runs);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(50L); run.setWorkspaceId(100L); run.setVersion(1); run.setStatus("RUNNING");
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"agentId\":40013,\"agentVersionId\":40033}]}");
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setId(600L); comment.setTenantId(100L); comment.setWorkitemId(50L);
        comment.setSourceType("SCHEDULED_TASK_RUN");
        DispatchDO interaction = dispatch(93L, "PENDING"); interaction.setSourceType("SCHEDULED_TASK_RUN");
        interaction.setAgentId(40013L); interaction.setWorkitemId(50L);
        when(runs.findById(100L, 50L)).thenReturn(run);
        when(commentDao.findBySourceAndId(100L, "SCHEDULED_TASK_RUN", 50L, 600L)).thenReturn(comment);
        when(dispatchService.enqueueScheduledRunCommentInteraction(100L, 50L, 40013L, null, 701L, 7L))
                .thenReturn(interaction);

        service.createForScheduledRunComment(100L, 50L, 600L, 40013L, 7L);

        verify(dispatchService).pinScheduledAgentVersion(93L, 100L, 40033L);
        verify(workitemService, never()).addAgentComment(anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void scheduledRunGuidanceReplyUsesRunCommentServiceForRealtimePublish() {
        ScheduledTaskRunCommentService runComments = mock(ScheduledTaskRunCommentService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunCommentService", runComments);
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L); guidance.setTenantId(100L); guidance.setSourceType("SCHEDULED_TASK_RUN");
        guidance.setWorkitemId(50L); guidance.setTargetAgentId(40013L); guidance.setDispatchId(93L);
        CommentVO reply = new CommentVO(); reply.setId(902L);
        when(guidanceDao.acknowledge(701L, 100L, 9L, GuidanceStatus.APPLIED, null)).thenReturn(1);
        when(guidanceDao.findById(701L)).thenReturn(guidance);
        when(runComments.addAgentComment(100L, 50L, 40013L, "已完成")).thenReturn(reply);
        when(guidanceDao.bindReplyComment(701L, 100L, 902L)).thenReturn(1);

        service.acknowledge(100L, 9L, 701L, GuidanceStatus.APPLIED, null, "已完成");

        verify(runComments).addAgentComment(100L, 50L, 40013L, "已完成");
        verify(workitemService, never()).addAgentComment(anyLong(), eq("已完成"), anyLong(), anyLong());
    }

    @Test
    void commentWithoutExplicitTargetsResolvesUniqueLeadingAgentName() {
        ParticipantVO developer = participant(40013L, "AW全栈开发");
        ParticipantVO tester = participant(40015L, "AW测试工程师");
        when(workitemService.getParticipants(50L, 100L)).thenReturn(List.of(developer, tester));

        service.createForComment(100L, 50L, 600L, "  @AW全栈开发 请重新修改", null, 7L);

        verify(guidanceDao).insert(argThat(row -> Long.valueOf(40013L).equals(row.getTargetAgentId())));
    }

    @Test
    void commentWithoutExplicitTargetsResolvesUniqueTenantAgentOutsideParticipants() {
        when(workitemService.getParticipants(50L, 100L)).thenReturn(List.of(participant(40013L, "AW全栈开发")));
        AgentDO conflictResolver = new AgentDO();
        conflictResolver.setId(40037L);
        conflictResolver.setTenantId(100L);
        conflictResolver.setName("AW代码冲突解决工程师");
        conflictResolver.setOnlineVersionId(40075L);
        when(agentDao.findByExactName(100L, "AW代码冲突解决工程师"))
                .thenReturn(List.of(conflictResolver));
        when(agentDao.findById(40037L)).thenReturn(conflictResolver);
        when(sdlcResolver.resolveSdlcId(100L, 40037L)).thenReturn(null);
        DispatchDO interaction = dispatch(94L, "PENDING");
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40037L, null, false,
                null, 701L, 7L)).thenReturn(interaction);

        service.createForComment(100L, 50L, 600L, "@AW代码冲突解决工程师 解决下", null, 7L);

        verify(guidanceDao).insert(argThat(row -> Long.valueOf(40037L).equals(row.getTargetAgentId())));
        verify(dispatchService).enqueueCommentInteraction(100L, 50L, 40037L, null, false,
                null, 701L, 7L);
        verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 94L));
    }

    @Test
    void unpublishedTargetAgentFailsGuidanceWithoutCreatingPendingDispatch() {
        AgentDO draftAgent = new AgentDO();
        draftAgent.setId(40044L);
        draftAgent.setTenantId(100L);
        draftAgent.setName("AW代码冲突解决工程师");
        draftAgent.setOnlineVersionId(null);
        when(agentDao.findById(40044L)).thenReturn(draftAgent);

        GuidanceDO result = service.create(100L, 50L, 600L, 40044L, 7L);

        assertEquals(GuidanceStatus.FAILED, result.getStatus());
        assertEquals("目标数字员工未发布在线版本，无法启动会话", result.getError());
        verify(guidanceDao).insert(argThat(row -> GuidanceStatus.FAILED.equals(row.getStatus())
                && row.getDispatchId() == null
                && "目标数字员工未发布在线版本，无法启动会话".equals(row.getError())));
        verify(dispatchService, never()).enqueueCommentInteraction(
                anyLong(), anyLong(), anyLong(), any(), anyBoolean(), any(), anyLong(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void commentDoesNotResolveNonLeadingOrAmbiguousMention() {
        when(workitemService.getParticipants(50L, 100L)).thenReturn(List.of(
                participant(40013L, "AW全栈开发"), participant(40016L, "AW全栈开发")));

        service.createForComment(100L, 50L, 600L, "请 @AW全栈开发 看一下", null, 7L);
        service.createForComment(100L, 50L, 600L, "@AW全栈开发 看一下", null, 7L);

        verify(guidanceDao, never()).insert(any());
    }

    @Test
    void runningWorkerGetsAnIndependentSideInteraction() {
        DispatchDO running = dispatch(91L, "RUNNING");
        DispatchDO side = dispatch(92L, "PENDING");
        side.setResumeMode("SIDE_INTERACTION");
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of(running));
        when(dispatchService.hasResumableSession(100L, 91L)).thenReturn(true);
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40013L, 91L, true,
                811L, 701L, 7L)).thenReturn(side);

        GuidanceDO result = service.create(100L, 50L, 600L, 40013L, 7L);

        assertEquals(GuidanceStatus.QUEUED, result.getStatus());
        assertEquals(92L, result.getDispatchId());
        verify(dispatchService).enqueueCommentInteraction(100L, 50L, 40013L, 91L, true,
                811L, 701L, 7L);
        verify(guidanceDao).bindPendingDispatch(701L, 100L, 92L);
        verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 92L));
        verify(dispatchService, never()).runPending(92L);
        verifyNoInteractions(transport);
    }

    @Test
    void pendingWorkerWithoutPinnedSessionGetsCanonicalInteraction() {
        DispatchDO pending = dispatch(91L, "PENDING");
        DispatchDO interaction = dispatch(92L, "PENDING");
        interaction.setResumeMode("CANONICAL_INTERACTION");
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of(pending));
        when(dispatchService.hasResumableSession(100L, 91L)).thenReturn(false);
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40013L, 91L, false,
                811L, 701L, 7L)).thenReturn(interaction);

        GuidanceDO result = service.create(100L, 50L, 600L, 40013L, 7L);

        assertEquals(92L, result.getDispatchId());
        verify(dispatchService).enqueueCommentInteraction(100L, 50L, 40013L, 91L, false,
                811L, 701L, 7L);
    }

    @Test
    void timelineCommentCarriesTheCurrentWorkerInteractionStatus() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setCommentId(600L);
        guidance.setTargetAgentId(40013L);
        guidance.setStatus(GuidanceStatus.DELIVERED);
        when(guidanceDao.listByWorkitem(100L, 50L)).thenReturn(List.of(guidance));
        TimelineItemVO comment = new TimelineItemVO();
        comment.setId(600L);
        comment.setType("comment");

        service.attachInteractionStatuses(100L, 50L, List.of(comment));

        assertEquals(1, comment.getInteractions().size());
        assertEquals(GuidanceStatus.DELIVERED, comment.getInteractions().get(0).getStatus());
        assertEquals(40013L, comment.getInteractions().get(0).getTargetAgentId());
    }

    @Test
    void reconnectRedeliversDeliveredGuidanceThatHasNoAck() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setTenantId(100L);
        guidance.setWorkitemId(50L);
        guidance.setCommentId(600L);
        guidance.setTargetAgentId(40013L);
        guidance.setDispatchId(91L);
        guidance.setExecutorId(10032L);
        guidance.setStatus(GuidanceStatus.DELIVERED);
        when(guidanceDao.listDeliveredForExecutor(100L, 10032L)).thenReturn(List.of(guidance));

        service.redeliverUnacknowledged(100L, 10032L);

        verify(transport).send(guidance, "comment body from canonical table");
        verify(guidanceDao, never()).updateStatus(eq(701L), eq(100L), anyString(), any());
    }

    @Test
    void consecutiveMentionsKeepAnIndependentThinkingStatusBelowEachQuestion() {
        GuidanceDO older = new GuidanceDO();
        older.setId(701L); older.setCommentId(600L); older.setTargetAgentId(40013L);
        older.setStatus(GuidanceStatus.DELIVERED);
        GuidanceDO latest = new GuidanceDO();
        latest.setId(702L); latest.setCommentId(601L); latest.setTargetAgentId(40013L);
        latest.setStatus(GuidanceStatus.QUEUED);
        when(guidanceDao.listByWorkitem(100L, 50L)).thenReturn(List.of(older, latest));
        TimelineItemVO first = new TimelineItemVO(); first.setId(600L); first.setType("comment");
        TimelineItemVO second = new TimelineItemVO(); second.setId(601L); second.setType("comment");

        service.attachInteractionStatuses(100L, 50L, List.of(first, second));

        assertEquals(1, first.getInteractions().size());
        assertEquals(701L, first.getInteractions().get(0).getGuidanceId());
        assertEquals(1, second.getInteractions().size());
        assertEquals(702L, second.getInteractions().get(0).getGuidanceId());
    }

    @Test
    void pauseStateQueuesGuidanceForTheResumedDispatch() {
        DispatchDO paused = dispatch(91L, "PAUSING");
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of(paused));
        when(dispatchService.hasResumableSession(100L, 91L)).thenReturn(true);
        GuidanceDO result = service.create(100L, 50L, 600L, 40013L, 7L);
        assertEquals(GuidanceStatus.QUEUED, result.getStatus());
        verify(dispatchService).enqueueCommentInteraction(100L, 50L, 40013L, 91L, true,
                811L, 701L, 7L);
        verifyNoInteractions(transport);
    }

    @Test
    void completedWorkerCommentStartsAnExactResumeInteractionDispatch() {
        DispatchDO completed = dispatch(90L, "SUCCEEDED");
        DispatchDO interaction = dispatch(92L, "PENDING");
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of(completed));
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40013L, 90L, false,
                811L, 701L, 7L)).thenReturn(interaction);

        GuidanceDO result = service.create(100L, 50L, 600L, 40013L, 7L);

        assertEquals(GuidanceStatus.QUEUED, result.getStatus());
        assertEquals(92L, result.getDispatchId());
    }

    @Test
    void firstMentionWithoutInstructionStartsFormalSdlcAtEntryStep() {
        DispatchDO formal = dispatch(93L, "PENDING");
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of());
        when(dispatchService.enqueue(100L, 50L, 811L, 40013L, 1, 7L)).thenReturn(formal);

        service.createForComment(100L, 50L, 600L, "@AW全栈开发", List.of(40013L), 7L);

        verify(workitemService).rebindForInteractionRework(100L, 50L, 40013L, 810L, 811L, 7L);
        verify(workitemService).addAgentComment(50L, "收到，已转入正式工作流程。", 100L, 40013L);
        verify(guidanceDao).bindReplyComment(701L, 100L, 901L);
        verify(dispatchService).enqueue(100L, 50L, 811L, 40013L, 1, 7L);
        verify(dispatchService, never()).enqueueCommentInteraction(
                anyLong(), anyLong(), anyLong(), any(), anyBoolean(), any(), anyLong(), anyLong());
    }

    @Test
    void firstMentionWithInstructionStartsConversationBeforeFormalWorkflow() {
        DispatchDO interaction = dispatch(94L, "PENDING");
        interaction.setResumeMode("CANONICAL_INTERACTION");
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of());
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40013L, null, false,
                811L, 701L, 7L)).thenReturn(interaction);

        service.createForComment(100L, 50L, 600L, "@AW全栈开发 在吗", List.of(40013L), 7L);

        verify(dispatchService).enqueueCommentInteraction(100L, 50L, 40013L, null, false,
                811L, 701L, 7L);
        verify(dispatchService, never()).enqueue(
                anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(workitemService, never()).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void workerOutsideDeliverySdlcCanStillAnswerWithAFullContextInteraction() {
        when(sdlcResolver.resolveSdlcId(100L, 40013L)).thenReturn(null);
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of());
        DispatchDO interaction = dispatch(94L, "PENDING");
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40013L, null, false,
                null, 701L, 7L)).thenReturn(interaction);

        GuidanceDO result = service.create(100L, 50L, 600L, 40013L, 7L);

        assertEquals(94L, result.getDispatchId());
    }

    @Test
    void priorSideInteractionMeansMentionIsNotTheWorkersFirstSession() {
        DispatchDO pending = dispatch(93L, "PENDING");
        pending.setResumeMode("SIDE_INTERACTION");
        pending.setExecutorId(null);
        when(dispatchDao.listByWorkitem(100L, 50L)).thenReturn(List.of(pending));
        DispatchDO interaction = dispatch(94L, "PENDING");
        interaction.setResumeMode("CANONICAL_INTERACTION");
        when(dispatchService.enqueueCommentInteraction(100L, 50L, 40013L, null, false,
                811L, 701L, 7L)).thenReturn(interaction);

        service.createForComment(100L, 50L, 600L, "@AW全栈开发", List.of(40013L), 7L);

        verify(dispatchService).enqueueCommentInteraction(100L, 50L, 40013L, null, false,
                811L, 701L, 7L);
        verify(dispatchService, never()).enqueue(
                anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(workitemService, never()).rebindForInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void dispatchAckDeliversQueuedGuidance() {
        GuidanceDO queued = new GuidanceDO();
        queued.setId(701L);
        queued.setTenantId(100L);
        queued.setWorkitemId(50L);
        queued.setCommentId(600L);
        queued.setTargetAgentId(40013L);
        when(guidanceDao.listQueuedForDispatch(100L, 92L)).thenReturn(List.of(queued));
        when(guidanceDao.bindDispatch(701L, 100L, 92L, 10005L)).thenReturn(1);
        DispatchDO interaction = dispatch(92L, "DISPATCHED");
        interaction.setResumeMode("SIDE_INTERACTION");
        service.deliverQueued(interaction);
        assertEquals(92L, queued.getDispatchId());
        verify(transport).send(queued, "comment body from canonical table");
    }

    @Test
    void formalDeliveryDispatchCannotConsumeSideConversationComments() {
        service.deliverQueued(dispatch(92L, "ACKED"));

        verify(guidanceDao, never()).listQueuedForDispatch(anyLong(), anyLong());
        verifyNoInteractions(transport);
    }

    @Test
    void successfulPauseRequeuesUnappliedGuidance() {
        service.requeueDeliveredForDispatch(100L, 91L);
        verify(guidanceDao).requeueDeliveredForDispatch(100L, 91L);
    }

    @Test
    void failedSideDispatchMarksItsUnansweredQuestionAsFailed() {
        service.failForDispatch(100L, 92L, "timed out waiting for bound user comments");

        verify(guidanceDao).failForDispatch(100L, 92L,
                "timed out waiting for bound user comments");
    }

    @Test
    void appliedGuidanceWritesOneAgentReplyToTheCommentTimeline() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setTenantId(100L);
        guidance.setWorkitemId(50L);
        guidance.setTargetAgentId(40013L);
        when(guidanceDao.acknowledge(701L, 100L, 10005L, GuidanceStatus.APPLIED, null)).thenReturn(1);
        when(guidanceDao.findById(701L)).thenReturn(guidance);
        CommentVO reply = new CommentVO();
        reply.setId(900L);
        when(workitemService.addAgentComment(50L, "Handled both comments.", 100L, 40013L)).thenReturn(reply);
        when(guidanceDao.bindReplyComment(701L, 100L, 900L)).thenReturn(1);

        service.acknowledge(100L, 10005L, 701L, GuidanceStatus.APPLIED, null, "Handled both comments.");

        verify(workitemService).addAgentComment(50L, "Handled both comments.", 100L, 40013L);
        verify(guidanceDao).bindReplyComment(701L, 100L, 900L);
    }

    @Test
    void failedGuidanceAckTerminatesItsInteractionDispatch() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setTenantId(100L);
        guidance.setDispatchId(92L);
        guidance.setExecutorId(10005L);
        DispatchDO interaction = dispatch(92L, "RUNNING");
        interaction.setResumeMode("SIDE_INTERACTION");
        when(guidanceDao.acknowledge(701L, 100L, 10005L, GuidanceStatus.FAILED,
                "Invalid session identifier")).thenReturn(1);
        when(guidanceDao.findById(701L)).thenReturn(guidance);
        when(dispatchDao.findById(92L)).thenReturn(interaction);
        when(dispatchService.onResult(100L, 10005L, 92L, false,
                null, "Invalid session identifier", false)).thenReturn(true);

        service.acknowledge(100L, 10005L, 701L, GuidanceStatus.FAILED,
                "Invalid session identifier", null);

        verify(dispatchService).onResult(100L, 10005L, 92L, false,
                null, "Invalid session identifier", false);
        verifyNoInteractions(workitemService);
    }

    @Test
    void failedGuidanceAckRollsBackWhenInteractionDispatchCannotTerminate() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setTenantId(100L);
        guidance.setDispatchId(92L);
        DispatchDO dispatch = dispatch(92L, "RUNNING");
        dispatch.setResumeMode("SIDE_INTERACTION");
        when(guidanceDao.acknowledge(701L, 100L, 10005L, GuidanceStatus.FAILED,
                "Invalid session identifier")).thenReturn(1);
        when(guidanceDao.findById(701L)).thenReturn(guidance);
        when(dispatchDao.findById(92L)).thenReturn(dispatch);
        when(dispatchService.onResult(100L, 10005L, 92L, false,
                null, "Invalid session identifier", false)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.acknowledge(
                100L, 10005L, 701L, GuidanceStatus.FAILED,
                "Invalid session identifier", null));
    }

    @Test
    void duplicateFailedGuidanceAckDoesNotTerminateDispatchAgain() {
        when(guidanceDao.acknowledge(701L, 100L, 10005L, GuidanceStatus.FAILED,
                "Invalid session identifier")).thenReturn(0);

        service.acknowledge(100L, 10005L, 701L, GuidanceStatus.FAILED,
                "Invalid session identifier", null);

        verify(dispatchService, never()).onResult(anyLong(), anyLong(), anyLong(), anyBoolean(),
                any(), any(), anyBoolean());
    }

    @Test
    void appliedReplyIsAttachedBelowItsQuestionInsteadOfRenderedAsAStandaloneReport() {
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(701L);
        guidance.setCommentId(600L);
        guidance.setTargetAgentId(40013L);
        guidance.setStatus(GuidanceStatus.APPLIED);
        guidance.setReplyCommentId(900L);
        when(guidanceDao.listByWorkitem(100L, 50L)).thenReturn(List.of(guidance));
        TimelineItemVO question = new TimelineItemVO();
        question.setId(600L);
        question.setType("comment");
        TimelineItemVO reply = new TimelineItemVO();
        reply.setId(900L);
        reply.setType("comment");
        reply.setContent("因为当时正式流程已经交接。 ");
        reply.setAuthorName("AW全栈开发");
        List<TimelineItemVO> timeline = new java.util.ArrayList<>(List.of(question, reply));

        service.attachInteractionStatuses(100L, 50L, timeline);

        assertEquals(1, timeline.size());
        assertEquals(600L, timeline.get(0).getId());
        assertEquals(1, question.getInteractions().size());
        assertEquals(GuidanceStatus.APPLIED, question.getInteractions().get(0).getStatus());
        assertEquals(900L, question.getInteractions().get(0).getReplyCommentId());
        assertEquals("因为当时正式流程已经交接。 ", question.getInteractions().get(0).getReplyContent());
    }

    @Test
    void duplicateGuidanceAckDoesNotDuplicateAgentReply() {
        when(guidanceDao.acknowledge(701L, 100L, 10005L, GuidanceStatus.APPLIED, null)).thenReturn(0);

        service.acknowledge(100L, 10005L, 701L, GuidanceStatus.APPLIED, null, "duplicate");

        verifyNoInteractions(workitemService);
    }

    private DispatchDO dispatch(long id, String status) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(50L);
        dispatch.setAgentId(40013L);
        dispatch.setExecutorId(10005L);
        dispatch.setStatus(status);
        return dispatch;
    }

    private ParticipantVO participant(long id, String name) {
        ParticipantVO participant = new ParticipantVO();
        participant.setUserId(id);
        participant.setName(name);
        participant.setAgent(true);
        return participant;
    }
}
