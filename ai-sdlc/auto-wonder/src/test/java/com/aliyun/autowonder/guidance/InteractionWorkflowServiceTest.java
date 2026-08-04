package com.aliyun.autowonder.guidance;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InteractionWorkflowServiceTest {

    @Test
    void replayForDeletedWorkitemIsTerminalNoop() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                mock(WorkitemService.class), mock(ApplicationEventPublisher.class),
                transactionManager(), workitemDao);
        DispatchDO side = dispatch(103L, 40015L, DispatchStatus.SUCCEEDED,
                "CANONICAL_INTERACTION");
        when(dao.findById(103L)).thenReturn(side);
        when(resolver.resolveSdlcId(100L, 40015L)).thenReturn(820L);
        SdlcStepDO testing = new SdlcStepDO();
        testing.setId(821L);
        when(resolver.resolveStep(100L, 820L, null, "获取上下文与评论")).thenReturn(testing);
        when(workitemDao.findByIdForUpdate(50L, 100L)).thenReturn(null);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 123L);
        plan.put("targetStepHint", "获取上下文与评论");

        DispatchDO result = assertDoesNotThrow(() -> service.apply(100L, 103L, plan));

        assertNull(result);
        verify(resolver).resolveSdlcId(100L, 40015L);
        verify(resolver, never()).resolveSdlcId(100L, 123L);
        verify(dao, never()).listByWorkitem(anyLong(), anyLong());
        verify(dispatchService, never()).enqueueInteractionRework(
                anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), any(), anyLong());
    }

    @Test
    void workflowChangingReplyPausesMainAndQueuesReworkAtEarliestAffectedStep() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        DispatchPauseService pauseService = mock(DispatchPauseService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, pauseService, resolver, workitemService, eventPublisher,
                transactionManager(), workitemDao());

        DispatchDO side = dispatch(103L, 40013L, DispatchStatus.SUCCEEDED, "SIDE_INTERACTION");
        DispatchDO main = dispatch(102L, 40014L, DispatchStatus.RUNNING, null);
        DispatchDO priorDev = dispatch(99L, 40013L, DispatchStatus.SUCCEEDED, null);
        DispatchDO reworkBoundary = dispatch(98L, 40013L, DispatchStatus.SUCCEEDED, "COMMENT_REWORK");
        DispatchDO olderWaiter = dispatch(101L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        olderWaiter.setResultSummary("waitForDispatchId=102");
        DispatchDO historicalTesting = dispatch(90L, 40015L, DispatchStatus.SUCCEEDED, null);
        when(dao.findById(103L)).thenReturn(side);
        when(dao.listByWorkitem(100L, 50L))
                .thenReturn(List.of(historicalTesting, reworkBoundary, priorDev, olderWaiter, main, side));
        when(dispatchService.isInteractionDispatch(side)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO coding = new SdlcStepDO();
        coding.setId(812L);
        when(resolver.resolveStep(100L, 810L, null, "编码实现")).thenReturn(coding);
        DispatchDO waiting = dispatch(104L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40013L, 812L, 101L, 103L, 102L, 0L)).thenReturn(waiting);

        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40013L);
        plan.put("targetStepHint", "编码实现");
        service.apply(100L, 103L, plan);

        InOrder order = inOrder(dispatchService, pauseService);
        order.verify(dispatchService).enqueueInteractionRework(
                100L, 50L, 40013L, 812L, 101L, 103L, 102L, 0L);
        order.verify(dispatchService).cancelWaitingInteractionRework(100L, 101L);
        order.verify(pauseService).requestPause(100L, 50L, 102L, 0L);
    }

    @Test
    void durablePauseRebindsOwnershipBeforeStartingWaitingRework() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                workitemService, eventPublisher, transactionManager(), workitemDao());
        DispatchDO paused = dispatch(102L, 40014L, DispatchStatus.PAUSED, null);
        DispatchDO rework = dispatch(104L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        rework.setSdlcStepId(812L);
        rework.setResultSummary("waitForDispatchId=102");
        when(dao.findById(102L)).thenReturn(paused);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(paused, rework));
        when(resolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO coding = new SdlcStepDO();
        coding.setId(812L);
        when(resolver.resolveStep(100L, 810L, "812", null)).thenReturn(coding);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);

        service.onPaused(100L, 102L);

        InOrder order = inOrder(workitemService, dispatchService, eventPublisher);
        order.verify(workitemService).rebindForInteractionRework(100L, 50L, 40013L, 810L, 812L, 0L);
        order.verify(dispatchService).releaseInteractionRework(100L, 104L);
        order.verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 104L));
    }

    @Test
    void completedFormalWorkerImmediatelyRebindsBeforeStartingDevelopmentRework() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        DispatchPauseService pauseService = mock(DispatchPauseService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, pauseService, resolver, workitemService, eventPublisher,
                transactionManager(), workitemDao());
        DispatchDO side = dispatch(103L, 40013L, DispatchStatus.SUCCEEDED, "SIDE_INTERACTION");
        DispatchDO priorDev = dispatch(99L, 40013L, DispatchStatus.SUCCEEDED, null);
        DispatchDO rework = dispatch(104L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        rework.setSdlcStepId(812L);
        when(dao.findById(103L)).thenReturn(side);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(priorDev, side));
        when(dispatchService.isInteractionDispatch(side)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO coding = new SdlcStepDO();
        coding.setId(812L);
        when(resolver.resolveStep(100L, 810L, null, "编码实现")).thenReturn(coding);
        when(resolver.resolveStep(100L, 810L, "812", null)).thenReturn(coding);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40013L, 812L, 99L, 103L, null, 0L)).thenReturn(rework);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40013L);
        plan.put("targetStepHint", "编码实现");

        service.apply(100L, 103L, plan);

        verify(pauseService, never()).requestPause(anyLong(), anyLong(), anyLong(), anyLong());
        InOrder order = inOrder(workitemService, dispatchService, eventPublisher);
        order.verify(workitemService).rebindForInteractionRework(100L, 50L, 40013L, 810L, 812L, 0L);
        order.verify(dispatchService).releaseInteractionRework(100L, 104L);
        order.verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 104L));
    }

    @Test
    void idleFutureWorkerCanonicalInteractionStartsAtRequestedAffectedStep() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                workitemService, eventPublisher, transactionManager(), workitemDao());
        DispatchDO interaction = dispatch(103L, 40030L, DispatchStatus.SUCCEEDED,
                "CANONICAL_INTERACTION");
        DispatchDO rework = dispatch(104L, 40030L, DispatchStatus.WAITING_FOR_PAUSE,
                "COMMENT_REWORK");
        rework.setSdlcStepId(822L);
        when(dao.findById(103L)).thenReturn(interaction);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(interaction));
        when(dispatchService.isInteractionDispatch(interaction)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40030L)).thenReturn(820L);
        SdlcStepDO affectedStep = new SdlcStepDO();
        affectedStep.setId(822L);
        when(resolver.resolveStep(100L, 820L, null, "执行变更")).thenReturn(affectedStep);
        when(resolver.resolveStep(100L, 820L, "822", null)).thenReturn(affectedStep);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40030L, 822L, 103L, 103L, null, 0L)).thenReturn(rework);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40030L);
        plan.put("targetStepHint", "执行变更");

        service.apply(100L, 103L, plan);

        verify(dispatchService).enqueueInteractionRework(
                100L, 50L, 40030L, 822L, 103L, 103L, null, 0L);
        verify(workitemService).rebindForInteractionRework(100L, 50L, 40030L, 820L, 822L, 0L);
        verify(dispatchService).releaseInteractionRework(100L, 104L);
        verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 104L));
    }

    @Test
    void firstFormalDispatchFallsBackToSdlcEntryWhenRouterHintIsInvalid() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                workitemService, eventPublisher, transactionManager(), workitemDao());
        DispatchDO interaction = dispatch(103L, 40044L, DispatchStatus.SUCCEEDED,
                "CANONICAL_INTERACTION");
        DispatchDO rework = dispatch(104L, 40044L, DispatchStatus.WAITING_FOR_PAUSE,
                "COMMENT_REWORK");
        rework.setSdlcStepId(400284L);
        when(dao.findById(103L)).thenReturn(interaction);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(interaction));
        when(dispatchService.isInteractionDispatch(interaction)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40044L)).thenReturn(40087L);
        when(resolver.resolveStep(100L, 40087L, null, "interaction")).thenReturn(null);
        SdlcStepDO entry = new SdlcStepDO();
        entry.setId(400284L);
        when(resolver.firstStep(100L, 40087L)).thenReturn(entry);
        when(resolver.resolveStep(100L, 40087L, "400284", null)).thenReturn(entry);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40044L, 400284L, 103L, 103L, null, 0L)).thenReturn(rework);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40044L);
        plan.put("targetStepHint", "interaction");

        service.apply(100L, 103L, plan);

        verify(dispatchService).enqueueInteractionRework(
                100L, 50L, 40044L, 400284L, 103L, 103L, null, 0L);
        verify(workitemService).rebindForInteractionRework(
                100L, 50L, 40044L, 40087L, 400284L, 0L);
    }

    @Test
    void returningWorkerFallsBackToAuthoritativeInteractionStepWhenRouterHintIsInvalid() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                workitemService, eventPublisher, transactionManager(), workitemDao());
        DispatchDO priorFormal = dispatch(99L, 40044L, DispatchStatus.SUCCEEDED, "COMMENT_REWORK");
        DispatchDO interaction = dispatch(103L, 40044L, DispatchStatus.SUCCEEDED,
                "CANONICAL_INTERACTION");
        interaction.setSdlcStepId(400284L);
        DispatchDO rework = dispatch(104L, 40044L, DispatchStatus.WAITING_FOR_PAUSE,
                "COMMENT_REWORK");
        rework.setSdlcStepId(400284L);
        when(dao.findById(103L)).thenReturn(interaction);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(priorFormal, interaction));
        when(dispatchService.isInteractionDispatch(interaction)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40044L)).thenReturn(40087L);
        when(resolver.resolveStep(100L, 40087L, null, "解决冲突")).thenReturn(null);
        SdlcStepDO authoritativeStep = new SdlcStepDO();
        authoritativeStep.setId(400284L);
        when(resolver.resolveStep(100L, 40087L, "400284", null)).thenReturn(authoritativeStep);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40044L, 400284L, 99L, 103L, null, 0L)).thenReturn(rework);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40044L);
        plan.put("targetStepHint", "解决冲突");

        service.apply(100L, 103L, plan);

        verify(dispatchService).enqueueInteractionRework(
                100L, 50L, 40044L, 400284L, 99L, 103L, null, 0L);
        verify(workitemService).rebindForInteractionRework(
                100L, 50L, 40044L, 40087L, 400284L, 0L);
        verify(resolver, never()).firstStep(100L, 40087L);
    }

    @Test
    void explicitlyAssignedNewWorkerCanEnterDeliveryFromItsCanonicalInteraction() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        WorkitemDao workitemDao = workitemDao();
        WorkitemDO assigned = new WorkitemDO();
        assigned.setId(50L);
        assigned.setTenantId(100L);
        assigned.setAssigneeType("AGENT");
        assigned.setAssigneeRef(40030L);
        when(workitemDao.findByIdForUpdate(50L, 100L)).thenReturn(assigned);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                workitemService, eventPublisher, transactionManager(), workitemDao);
        DispatchDO interaction = dispatch(103L, 40030L, DispatchStatus.SUCCEEDED,
                "CANONICAL_INTERACTION");
        DispatchDO rework = dispatch(104L, 40030L, DispatchStatus.WAITING_FOR_PAUSE,
                "COMMENT_REWORK");
        rework.setSdlcStepId(821L);
        when(dao.findById(103L)).thenReturn(interaction);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(interaction));
        when(dispatchService.isInteractionDispatch(interaction)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40030L)).thenReturn(820L);
        SdlcStepDO firstStep = new SdlcStepDO();
        firstStep.setId(821L);
        when(resolver.resolveStep(100L, 820L, null, "扫描变更与甄别")).thenReturn(firstStep);
        when(resolver.resolveStep(100L, 820L, "821", null)).thenReturn(firstStep);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40030L, 821L, 103L, 103L, null, 0L)).thenReturn(rework);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40030L);
        plan.put("targetStepHint", "扫描变更与甄别");

        service.apply(100L, 103L, plan);

        verify(dispatchService).enqueueInteractionRework(
                100L, 50L, 40030L, 821L, 103L, 103L, null, 0L);
        verify(workitemService).rebindForInteractionRework(100L, 50L, 40030L, 820L, 821L, 0L);
        verify(dispatchService).releaseInteractionRework(100L, 104L);
        verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 104L));
    }

    @Test
    void durablePauseStartsOnlyLatestWaitingCommentRework() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, mock(DispatchPauseService.class), resolver,
                workitemService, eventPublisher, transactionManager(), workitemDao());
        DispatchDO paused = dispatch(102L, 40014L, DispatchStatus.PAUSED, null);
        DispatchDO older = dispatch(104L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        older.setSdlcStepId(811L);
        older.setResultSummary("waitForDispatchId=102");
        DispatchDO latest = dispatch(105L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        latest.setSdlcStepId(812L);
        latest.setResultSummary("waitForDispatchId=102");
        when(dao.findById(102L)).thenReturn(paused);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(latest, older, paused));
        when(resolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO coding = new SdlcStepDO();
        coding.setId(812L);
        when(resolver.resolveStep(100L, 810L, "812", null)).thenReturn(coding);
        when(dispatchService.releaseInteractionRework(100L, 105L)).thenReturn(true);

        service.onPaused(100L, 102L);

        verify(dispatchService).cancelWaitingInteractionRework(100L, 104L);
        verify(workitemService).rebindForInteractionRework(100L, 50L, 40013L, 810L, 812L, 0L);
        verify(dispatchService).releaseInteractionRework(100L, 105L);
        verify(dispatchService, never()).releaseInteractionRework(100L, 104L);
        verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 105L));
    }

    @Test
    void pauseThatWinsBeforeCommandReturnStillActivatesPersistedRework() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        DispatchPauseService pauseService = mock(DispatchPauseService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        WorkitemService workitemService = mock(WorkitemService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, pauseService, resolver, workitemService, eventPublisher,
                transactionManager(), workitemDao());
        DispatchDO side = dispatch(103L, 40013L, DispatchStatus.SUCCEEDED, "SIDE_INTERACTION");
        DispatchDO main = dispatch(102L, 40014L, DispatchStatus.RUNNING, null);
        DispatchDO priorDev = dispatch(99L, 40013L, DispatchStatus.SUCCEEDED, null);
        DispatchDO rework = dispatch(104L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        rework.setSdlcStepId(812L);
        rework.setResultSummary("waitForDispatchId=102");
        DispatchDO paused = dispatch(102L, 40014L, DispatchStatus.PAUSED, null);
        when(dao.findById(103L)).thenReturn(side);
        when(dao.listByWorkitem(100L, 50L))
                .thenReturn(List.of(priorDev, main, side), List.of(priorDev, paused, side, rework));
        when(dispatchService.isInteractionDispatch(side)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO coding = new SdlcStepDO();
        coding.setId(812L);
        when(resolver.resolveStep(100L, 810L, null, "编码实现")).thenReturn(coding);
        when(resolver.resolveStep(100L, 810L, "812", null)).thenReturn(coding);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40013L, 812L, 99L, 103L, 102L, 0L)).thenReturn(rework);
        when(pauseService.requestPause(100L, 50L, 102L, 0L)).thenReturn(paused);
        when(dispatchService.releaseInteractionRework(100L, 104L)).thenReturn(true);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40013L);
        plan.put("targetStepHint", "编码实现");

        service.apply(100L, 103L, plan);

        verify(workitemService).rebindForInteractionRework(100L, 50L, 40013L, 810L, 812L, 0L);
        verify(dispatchService).releaseInteractionRework(100L, 104L);
        verify(eventPublisher).publishEvent(new GuidanceDispatchQueuedEvent(100L, 104L));
    }

    @Test
    void ambiguousPauseTransportFailureKeepsWaitingReworkForLateReceiptOrRetry() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        DispatchPauseService pauseService = mock(DispatchPauseService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, pauseService, resolver, mock(WorkitemService.class),
                mock(ApplicationEventPublisher.class), transactionManager(), workitemDao());
        DispatchDO side = dispatch(103L, 40013L, DispatchStatus.SUCCEEDED, "SIDE_INTERACTION");
        DispatchDO main = dispatch(102L, 40014L, DispatchStatus.RUNNING, null);
        DispatchDO pausing = dispatch(102L, 40014L, DispatchStatus.PAUSING, null);
        DispatchDO priorDev = dispatch(99L, 40013L, DispatchStatus.SUCCEEDED, null);
        DispatchDO rework = dispatch(104L, 40013L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        rework.setSdlcStepId(812L);
        rework.setResultSummary("waitForDispatchId=102");
        when(dao.findById(103L)).thenReturn(side);
        when(dao.findById(102L)).thenReturn(pausing);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(priorDev, main, side));
        when(dispatchService.isInteractionDispatch(side)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40013L)).thenReturn(810L);
        SdlcStepDO coding = new SdlcStepDO();
        coding.setId(812L);
        when(resolver.resolveStep(100L, 810L, null, "编码实现")).thenReturn(coding);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40013L, 812L, 99L, 103L, 102L, 0L)).thenReturn(rework);
        RuntimeException transportFailure = new RuntimeException("ambiguous send failure");
        when(pauseService.requestPause(100L, 50L, 102L, 0L)).thenThrow(transportFailure);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40013L);
        plan.put("targetStepHint", "编码实现");

        assertThrows(RuntimeException.class, () -> service.apply(100L, 103L, plan));

        verify(dispatchService, never()).cancelWaitingInteractionRework(100L, 104L);
        verify(dispatchService, never()).releaseInteractionRework(100L, 104L);
    }

    @Test
    void canonicalInteractionPausesActiveMainBeforeStartingFutureWorkerWorkflow() {
        DispatchDao dao = mock(DispatchDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        DispatchPauseService pauseService = mock(DispatchPauseService.class);
        AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
        InteractionWorkflowService service = new InteractionWorkflowService(
                dao, dispatchService, pauseService, resolver,
                mock(WorkitemService.class), mock(ApplicationEventPublisher.class),
                transactionManager(), workitemDao());
        DispatchDO historicalTesting = dispatch(90L, 40015L, DispatchStatus.SUCCEEDED, null);
        DispatchDO activeDevelopment = dispatch(102L, 40013L, DispatchStatus.RUNNING, "COMMENT_REWORK");
        activeDevelopment.setResumeFromDispatchId(80L);
        DispatchDO interaction = dispatch(103L, 40015L, DispatchStatus.SUCCEEDED,
                "CANONICAL_INTERACTION");
        when(dao.findById(103L)).thenReturn(interaction);
        when(dao.listByWorkitem(100L, 50L)).thenReturn(List.of(historicalTesting, activeDevelopment, interaction));
        when(dispatchService.isInteractionDispatch(interaction)).thenReturn(true);
        when(resolver.resolveSdlcId(100L, 40015L)).thenReturn(820L);
        SdlcStepDO testing = new SdlcStepDO();
        testing.setId(821L);
        when(resolver.resolveStep(100L, 820L, null, "测试开始")).thenReturn(testing);
        JSONObject plan = new JSONObject();
        plan.put("targetAgentId", 40015L);
        plan.put("targetStepHint", "测试开始");

        DispatchDO rework = dispatch(104L, 40015L, DispatchStatus.WAITING_FOR_PAUSE, "COMMENT_REWORK");
        rework.setSdlcStepId(821L);
        when(resolver.resolveStep(100L, 820L, "821", null)).thenReturn(testing);
        when(dispatchService.enqueueInteractionRework(
                100L, 50L, 40015L, 821L, 103L, 103L, 102L, 0L)).thenReturn(rework);

        service.apply(100L, 103L, plan);

        verify(dispatchService).enqueueInteractionRework(
                100L, 50L, 40015L, 821L, 103L, 103L, 102L, 0L);
        verify(pauseService).requestPause(100L, 50L, 102L, 0L);
        verify(dispatchService, never()).cancelUndeliveredForInteraction(anyLong(), anyLong());
    }

    private DispatchDO dispatch(long id, long agentId, String status, String mode) {
        DispatchDO row = new DispatchDO();
        row.setId(id);
        row.setTenantId(100L);
        row.setWorkitemId(50L);
        row.setAgentId(agentId);
        row.setStatus(status);
        row.setResumeMode(mode);
        return row;
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    private WorkitemDao workitemDao() {
        WorkitemDao dao = mock(WorkitemDao.class);
        WorkitemDO row = new WorkitemDO();
        row.setId(50L);
        row.setTenantId(100L);
        when(dao.findByIdForUpdate(anyLong(), anyLong())).thenReturn(row);
        return dao;
    }
}
