package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandoffServiceTest {

    private final WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
    private final WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
    private final UserDao userDao = mock(UserDao.class);

    @BeforeEach
    void setUpHumanMember() {
        for (long id : new long[]{77L, 42L, 7L}) {
            UserDO user = new UserDO();
            user.setId(id);
            user.setStatus(0);
            when(userDao.findById(id)).thenReturn(user);
            WorkspaceMemberDO member = new WorkspaceMemberDO();
            member.setTenantId(100L);
            member.setUserId(id);
            member.setStatus(0);
            member.setAccessLevel("READ_ONLY");
            when(workspaceMemberDao.findByWorkspaceAndUser(100L, id)).thenReturn(member);
        }
    }

    @AfterEach
    void clearRequestContext() {
        MDC.clear();
        com.aliyun.autowonder.context.AutoWonderContext.destroy();
    }

    @Test
    void scheduledRunHandoffIsRejectedBeforeWorkitemRowLock() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        doThrow(new BizException(ErrorCode.DISPATCH_NOT_FOUND)).when(dispatchService)
                .requireWorkitemDispatchBoundary(100L, 500L, 300L);
        HandoffService service = new HandoffService(workitemDao, dispatchService,
                mock(AgentRoleResolver.class), mock(AgentSdlcResolver.class),
                workspaceDao, workspaceMemberDao, userDao, mock(WorkitemEventDao.class));

        HandoffResult result = service.handle(100L, 500L, 300L, "QA", "AGENT");

        assertEquals(HandoffResult.Status.REJECTED, result.status());
        assertEquals("DISPATCH_NOT_FOUND", result.reasonCode());
        verify(dispatchService).requireWorkitemDispatchBoundary(100L, 500L, 300L);
        verify(workitemDao, never()).findByIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void staleHandoffIsRejectedAfterCommentReworkBecomesAuthoritative() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        when(dispatchService.isSupersededByInteractionRework(100L, 500L, 300L)).thenReturn(true);

        HandoffService service = new HandoffService(workitemDao, dispatchService,
                mock(AgentRoleResolver.class), mock(AgentSdlcResolver.class),
                workspaceDao, workspaceMemberDao, userDao, mock(WorkitemEventDao.class));

        HandoffResult result = service.handle(100L, 500L, 300L, "AW_CR", "AGENT");

        assertEquals(HandoffResult.Status.REJECTED, result.status());
        assertEquals("SOURCE_SUPERSEDED", result.reasonCode());
        InOrder order = inOrder(workitemDao, dispatchService);
        order.verify(workitemDao).findByIdForUpdate(500L, 100L);
        order.verify(dispatchService).isSupersededByInteractionRework(100L, 500L, 300L);
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
    }

    private WorkitemDO workitem(long id, long tenantId, int version) {
        WorkitemDO w = new WorkitemDO();
        w.setId(id);
        w.setTenantId(tenantId);
        w.setVersion(version);
        return w;
    }

    private DispatchDO workitemDispatch(long id, long tenantId, long workitemId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        dispatch.setSourceType(ExecutionSourceType.WORKITEM.name());
        dispatch.setWorkitemId(workitemId);
        return dispatch;
    }

    @Test
    void concreteNumericHumanTargetIsAssignedAndStops() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        when(roleResolver.resolveOnlineAgentId(100L, "77")).thenReturn(77L);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 77L, 3, 0L)).thenReturn(1);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "77", "HUMAN");

        assertEquals(HandoffResult.Status.HUMAN_ASSIGNED, result.status());
        assertEquals(77L, result.targetRef());
        assertEquals("REQUESTED_HUMAN", result.reasonCode());
        verify(workitemDao).updateAssignee(eq(500L), eq(100L), eq("HUMAN"), eq(77L), eq(3), anyLong());
        verify(dispatchService, never()).enqueue(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void humanHandoffRechecksActiveMembershipBeforeAssignment() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        HandoffService svc = new HandoffService(workitemDao, mock(DispatchService.class),
                mock(AgentRoleResolver.class), mock(AgentSdlcResolver.class), workspaceDao, workspaceMemberDao, userDao, eventDao);
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(100L, 77L);
        member.setStatus(1);
        assertEquals(HandoffResult.Status.REJECTED, svc.handle(100L, 500L, 300L, "77", "HUMAN").status());
        member.setStatus(0);
        userDao.findById(77L).setStatus(1);
        assertEquals(HandoffResult.Status.REJECTED, svc.handle(100L, 500L, 300L, "77", "HUMAN").status());
        userDao.findById(77L).setStatus(0);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 77L)).thenReturn(null);
        assertEquals(HandoffResult.Status.REJECTED, svc.handle(100L, 500L, 300L, "77", "HUMAN").status());
        when(userDao.findById(77L)).thenReturn(null);
        assertEquals(HandoffResult.Status.REJECTED, svc.handle(100L, 500L, 300L, "77", "HUMAN").status());
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
        verifyNoInteractions(eventDao);
    }

    @Test
    void agentHandoff_startsTargetOwnSdlcFirstStep_rebindsWorkitem_andSyncsAssignee() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 10000L)).thenReturn(workitem(500L, 10000L, 4));
        when(roleResolver.resolveOnlineAgentId(10000L, "QA")).thenReturn(10002L);
        when(sdlcResolver.resolveSdlcId(10000L, 10002L)).thenReturn(30003L);
        SdlcStepDO first = new SdlcStepDO();
        first.setId(300031L);
        first.setSdlcId(30003L);
        first.setStepOrder(1);
        when(sdlcResolver.firstStep(10000L, 30003L)).thenReturn(first);
        DispatchDO newDispatch = new DispatchDO();
        newDispatch.setId(9001L);
        when(dispatchService.enqueueHandoff(10000L, 500L, 300031L, 10002L, 300L, 0L)).thenReturn(newDispatch);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(10000L, 500L, 300L, "QA", "AGENT");

        assertEquals(HandoffResult.Status.AGENT_DISPATCHED, result.status());
        assertEquals(10002L, result.targetRef());
        assertEquals(9001L, result.downstreamDispatchId());
        verify(workitemDao).updateSdlcAndStep(eq(500L), eq(10000L), eq(30003L), eq(300031L), anyInt(), anyLong());
        verify(workitemDao).updateAssignee(eq(500L), eq(10000L), eq("AGENT"), eq(10002L), anyInt(), eq(0L));
        verify(dispatchService).enqueueHandoff(10000L, 500L, 300031L, 10002L, 300L, 0L);
        verify(dispatchService).runPending(9001L);
    }

    @Test
    void sixthAutomaticHandoffToSameWorkerFallsBackToHumanDecision() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        WorkitemDO w = workitem(500L, 100L, 3);
        w.setAssignOperatorId(42L);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(100L, "AW_CR")).thenReturn(12L);
        when(dispatchService.hasReachedAutomaticHandoffLimit(100L, 500L, 300L, 12L, 5))
                .thenReturn(true);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 42L, 3, 0L)).thenReturn(1);

        HandoffService svc = new HandoffService(workitemDao, dispatchService,
                roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "AW_CR", "AGENT");

        assertEquals(HandoffResult.Status.HUMAN_ASSIGNED, result.status());
        assertEquals(42L, result.targetRef());
        assertEquals("AUTOMATIC_HANDOFF_LIMIT", result.reasonCode());
        verify(workitemDao).updateAssignee(500L, 100L, "HUMAN", 42L, 3, 0L);
        verify(dispatchService, never()).enqueueHandoff(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(sdlcResolver);
    }

    @Test
    void unavailableAgentDoesNotEscalateToWorkspaceOwner() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        when(roleResolver.resolveOnlineAgentId(100L, "reviewer")).thenReturn(null);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "reviewer", "AGENT");

        assertEquals(HandoffResult.Status.REJECTED, result.status());
        assertEquals("TARGET_UNRESOLVED", result.reasonCode());
        verifyNoInteractions(workspaceMemberDao, userDao);
        verify(dispatchService, never()).enqueue(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(workitemDao, never()).updateSdlcAndStep(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
    }

    @Test
    void unresolvedAgentFallsBackToWorkitemHumanOwner() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        WorkitemDO w = workitem(500L, 100L, 3);
        w.setAssignOperatorId(42L);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(100L, "AW_CR")).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 42L, 3, 0L)).thenReturn(1);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "AW_CR", "AGENT");

        assertEquals(HandoffResult.Status.HUMAN_ASSIGNED, result.status());
        assertEquals(42L, result.targetRef());
        assertEquals("UNKNOWN_AGENT_FALLBACK_HUMAN", result.reasonCode());
        verify(workitemDao).updateAssignee(500L, 100L, "HUMAN", 42L, 3, 0L);
        verify(dispatchService, never()).enqueueHandoff(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void targetAgentWithoutSdlcIsRejectedExplicitly() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        when(roleResolver.resolveOnlineAgentId(100L, "QA")).thenReturn(12L);
        when(sdlcResolver.resolveSdlcId(100L, 12L)).thenReturn(null);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "QA", "AGENT");

        assertEquals(HandoffResult.Status.REJECTED, result.status());
        assertEquals("TARGET_AGENT_HAS_NO_SDLC", result.reasonCode());
    }

    @Test
    void replayedAgentHandoffReturnsExistingDispatchWithoutMutatingWorkitem() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        DispatchDO existing = new DispatchDO();
        existing.setId(901L);
        existing.setAgentId(12L);
        existing.setStatus(DispatchStatus.PENDING);
        when(dispatchService.findHandoffBySource(100L, 300L)).thenReturn(existing);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "QA", "AGENT");

        assertEquals(HandoffResult.Status.AGENT_DISPATCHED, result.status());
        assertEquals(12L, result.targetRef());
        assertEquals(901L, result.downstreamDispatchId());
        verify(dispatchService).runPending(901L);
        verifyNoInteractions(roleResolver, sdlcResolver);
        verify(workitemDao, never()).updateSdlcAndStep(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(eventDao, never()).insert(any());
    }

    @Test
    void unresolvedTo_withAssignOperator_assignsOperatorAsHuman() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        WorkitemDO w = workitem(500L, 100L, 3);
        w.setAssignOperatorId(42L);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(eq(100L), anyString())).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 42L, 3, 0L)).thenReturn(1);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        HandoffResult result = svc.handle(100L, 500L, 300L, "需求决策人", "HUMAN");

        assertEquals(HandoffResult.Status.HUMAN_ASSIGNED, result.status());
        verify(workitemDao).updateAssignee(500L, 100L, "HUMAN", 42L, 3, 0L);
        verify(dispatchService, never()).enqueue(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void unresolvedTo_noOperator_fallsBackToTenantAdmin() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(100L);
        workspace.setOwnerId(7L);
        when(workspaceDao.findById(100L)).thenReturn(workspace);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 7L, 3, 0L)).thenReturn(1);
        HandoffService svc = new HandoffService(workitemDao, mock(DispatchService.class),
                mock(AgentRoleResolver.class), mock(AgentSdlcResolver.class), workspaceDao, workspaceMemberDao, userDao, eventDao);

        HandoffResult result = svc.handle(100L, 500L, 300L, "someone", "HUMAN");

        assertEquals(HandoffResult.Status.HUMAN_ASSIGNED, result.status());
        assertEquals(7L, result.targetRef());
        verify(workitemDao).updateAssignee(500L, 100L, "HUMAN", 7L, 3, 0L);
    }

    @Test
    void missingOrInvalidHumanTargetWithoutFallbackIsRejected() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        HandoffService svc = new HandoffService(workitemDao, dispatchService,
                mock(AgentRoleResolver.class), mock(AgentSdlcResolver.class), workspaceDao, workspaceMemberDao, userDao,
                mock(WorkitemEventDao.class));

        for (String target : new String[]{null, "", "someone", "0", "-1", "AONE:77", "9223372036854775808"}) {
            assertEquals(HandoffResult.Status.REJECTED, svc.handle(100L, 500L, 300L, target, "HUMAN").status());
        }
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
    }

    @Test
    void agentHandoff_writesAssignTimelineEvent() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 10000L)).thenReturn(workitem(500L, 10000L, 4));
        when(roleResolver.resolveOnlineAgentId(10000L, "QA")).thenReturn(10002L);
        when(sdlcResolver.resolveSdlcId(10000L, 10002L)).thenReturn(30003L);
        SdlcStepDO first = new SdlcStepDO();
        first.setId(300031L);
        first.setSdlcId(30003L);
        first.setStepOrder(1);
        when(sdlcResolver.firstStep(10000L, 30003L)).thenReturn(first);
        DispatchDO newDispatch = new DispatchDO();
        newDispatch.setId(9001L);
        when(dispatchService.enqueueHandoff(10000L, 500L, 300031L, 10002L, 300L, 0L)).thenReturn(newDispatch);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        svc.handle(10000L, 500L, 300L, "QA", "AGENT");

        verify(eventDao).insert(argThat(e ->
                "ASSIGN".equals(e.getEventType())
                        && "SYSTEM".equals(e.getActorType())
                        && Long.valueOf(0L).equals(e.getActorRef())
                        && "10002".equals(e.getToVal())
                        && e.getTenantId() == 10000L
                        && e.getWorkitemId() == 500L));
    }

    @Test
    void agentHandoffAttributesAssignEventToSourceAgentWhenResolvable() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        AgentDao agentDao = mock(AgentDao.class);

        WorkitemDO w = workitem(500L, 10000L, 4);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(40014L);
        when(workitemDao.findByIdForUpdate(500L, 10000L)).thenReturn(w);
        when(workitemDao.findById(500L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(10000L, "QA")).thenReturn(10002L);
        when(sdlcResolver.resolveSdlcId(10000L, 10002L)).thenReturn(30003L);
        SdlcStepDO first = new SdlcStepDO();
        first.setId(300031L);
        first.setSdlcId(30003L);
        first.setStepOrder(1);
        when(sdlcResolver.firstStep(10000L, 30003L)).thenReturn(first);
        DispatchDO newDispatch = new DispatchDO();
        newDispatch.setId(9001L);
        when(dispatchService.enqueueHandoff(10000L, 500L, 300031L, 10002L, 300L, 0L)).thenReturn(newDispatch);
        DispatchDO source = new DispatchDO();
        source.setId(300L);
        source.setTenantId(10000L);
        source.setWorkitemId(500L);
        source.setAgentId(40014L);
        when(dispatchDao.findById(300L)).thenReturn(source);
        AgentDO agent = new AgentDO();
        agent.setId(40014L);
        agent.setName("AW开发数字人");
        when(agentDao.findById(40014L)).thenReturn(agent);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver,
                workspaceDao, workspaceMemberDao, userDao, eventDao, dispatchDao, agentDao, mock(ApplicationEventPublisher.class));
        svc.handle(10000L, 500L, 300L, "QA", "AGENT");

        verify(eventDao).insert(argThat(e ->
                "ASSIGN".equals(e.getEventType())
                        && "AGENT".equals(e.getActorType())
                        && Long.valueOf(40014L).equals(e.getActorRef())
                        && "10002".equals(e.getToVal())
                        && e.getTenantId() == 10000L
                        && e.getWorkitemId() == 500L));
    }

    @Test
    void humanHandoff_writesAssignTimelineEvent() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(workitem(500L, 100L, 3));
        when(roleResolver.resolveOnlineAgentId(100L, "77")).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 77L, 3, 0L)).thenReturn(1);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        svc.handle(100L, 500L, 300L, "77", "HUMAN");

        verify(eventDao).insert(argThat(e ->
                "ASSIGN".equals(e.getEventType())
                        && "SYSTEM".equals(e.getActorType())
                        && Long.valueOf(0L).equals(e.getActorRef())
                        && "77".equals(e.getToVal())
                        && e.getTenantId() == 100L
                        && e.getWorkitemId() == 500L));
    }

    @Test
    void humanHandoffPublishesHumanNotificationWithSourceDispatchAgentActor() {
        MDC.put("requestId", "rid-handoff");
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WorkitemDO w = workitem(500L, 100L, 3);
        w.setTitle("需要人工决策");
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(40014L);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(100L, "77")).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 77L, 3, 0L)).thenReturn(1);
        DispatchDO source = new DispatchDO();
        source.setId(300L);
        source.setTenantId(100L);
        source.setWorkitemId(500L);
        source.setAgentId(40014L);
        when(dispatchDao.findById(300L)).thenReturn(source);
        AgentDO agent = new AgentDO();
        agent.setId(40014L);
        agent.setName("AW开发数字人");
        when(agentDao.findById(40014L)).thenReturn(agent);
        doAnswer(inv -> {
            inv.<com.aliyun.autowonder.workitem.WorkitemEventDO>getArgument(0).setId(8001L);
            return null;
        }).when(eventDao).insert(any());

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver,
                workspaceDao, workspaceMemberDao, userDao, eventDao, dispatchDao, agentDao, publisher);
        svc.handle(100L, 500L, 300L, "77", "HUMAN");

        verify(eventDao).insert(argThat(e -> "ASSIGN".equals(e.getEventType())
                && "AGENT".equals(e.getActorType())
                && Long.valueOf(40014L).equals(e.getActorRef())));
        ArgumentCaptor<WorkitemHumanAssignedEvent> published =
                ArgumentCaptor.forClass(WorkitemHumanAssignedEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertEquals(8001L, published.getValue().workitemEventId());
        assertEquals(77L, published.getValue().recipientUserId());
        assertEquals("AGENT", published.getValue().actorType());
        assertEquals(40014L, published.getValue().actorRef());
        assertEquals("AW开发数字人", published.getValue().actorDisplayName());
        assertEquals("需要人工决策", published.getValue().workitemTitle());
        assertEquals("rid-handoff", published.getValue().requestId());
    }

    @Test
    void humanHandoffVersionConflictDoesNotWriteEventOrPublishNotification() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WorkitemDO w = workitem(500L, 100L, 3);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(40014L);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(100L, "77")).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 77L, 3, 0L)).thenReturn(0);

        DispatchDao dispatchDao = mock(DispatchDao.class);
        when(dispatchDao.findById(300L)).thenReturn(workitemDispatch(300L, 100L, 500L));
        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver,
                workspaceDao, workspaceMemberDao, userDao, eventDao, dispatchDao, mock(AgentDao.class), publisher);

        BizException ex = assertThrows(BizException.class,
                () -> svc.handle(100L, 500L, 300L, "77", "HUMAN"));

        assertEquals(ErrorCode.WORKITEM_VERSION_CONFLICT.getCode(), ex.getCode());
        verify(eventDao, never()).insert(any());
        verify(publisher, never()).publishEvent(isA(WorkitemHumanAssignedEvent.class));
    }

    @Test
    void humanHandoffPublishesNotificationWithSystemActorWhenSourceAgentCannotResolve() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WorkitemDO w = workitem(500L, 100L, 3);
        w.setTitle("需要兜底");
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(100L, "77")).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 77L, 3, 0L)).thenReturn(1);
        doAnswer(inv -> {
            inv.<com.aliyun.autowonder.workitem.WorkitemEventDO>getArgument(0).setId(8002L);
            return null;
        }).when(eventDao).insert(any());
        when(dispatchDao.findById(300L)).thenReturn(workitemDispatch(300L, 100L, 500L));

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver,
                workspaceDao, workspaceMemberDao, userDao, eventDao, dispatchDao, agentDao, publisher);
        svc.handle(100L, 500L, 300L, "77", "HUMAN");

        ArgumentCaptor<WorkitemHumanAssignedEvent> published =
                ArgumentCaptor.forClass(WorkitemHumanAssignedEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertEquals("SYSTEM", published.getValue().actorType());
        assertEquals(0L, published.getValue().actorRef());
        assertEquals("系统", published.getValue().actorDisplayName());
        assertEquals(77L, published.getValue().recipientUserId());
    }

    @Test
    void agentHandoffDoesNotPublishHumanNotification() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        when(workitemDao.findByIdForUpdate(500L, 10000L)).thenReturn(workitem(500L, 10000L, 4));
        when(roleResolver.resolveOnlineAgentId(10000L, "QA")).thenReturn(10002L);
        when(sdlcResolver.resolveSdlcId(10000L, 10002L)).thenReturn(30003L);
        SdlcStepDO first = new SdlcStepDO();
        first.setId(300031L);
        first.setSdlcId(30003L);
        when(sdlcResolver.firstStep(10000L, 30003L)).thenReturn(first);
        DispatchDO newDispatch = new DispatchDO();
        newDispatch.setId(9001L);
        when(dispatchService.enqueueHandoff(10000L, 500L, 300031L, 10002L, 300L, 0L)).thenReturn(newDispatch);

        DispatchDao dispatchDao = mock(DispatchDao.class);
        when(dispatchDao.findById(300L)).thenReturn(workitemDispatch(300L, 10000L, 500L));
        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver,
                workspaceDao, workspaceMemberDao, userDao, eventDao, dispatchDao, mock(AgentDao.class), publisher);
        svc.handle(10000L, 500L, 300L, "QA", "AGENT");

        verify(publisher, never()).publishEvent(isA(WorkitemHumanAssignedEvent.class));
    }

    @Test
    void agentHandoff_writesDetailJsonWithFromTypeAndToType() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        WorkitemDO w = workitem(500L, 10000L, 4);
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(77L);
        when(workitemDao.findByIdForUpdate(500L, 10000L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(10000L, "QA")).thenReturn(10002L);
        when(sdlcResolver.resolveSdlcId(10000L, 10002L)).thenReturn(30003L);
        SdlcStepDO first = new SdlcStepDO();
        first.setId(300031L);
        first.setSdlcId(30003L);
        first.setStepOrder(1);
        when(sdlcResolver.firstStep(10000L, 30003L)).thenReturn(first);
        DispatchDO newDispatch = new DispatchDO();
        newDispatch.setId(9001L);
        when(dispatchService.enqueueHandoff(10000L, 500L, 300031L, 10002L, 300L, 0L)).thenReturn(newDispatch);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        svc.handle(10000L, 500L, 300L, "QA", "AGENT");

        ArgumentCaptor<com.aliyun.autowonder.workitem.WorkitemEventDO> captor =
                ArgumentCaptor.forClass(com.aliyun.autowonder.workitem.WorkitemEventDO.class);
        verify(eventDao).insert(captor.capture());
        String detailJson = captor.getValue().getDetailJson();
        assertNotNull(detailJson);
        com.alibaba.fastjson.JSONObject detail = com.alibaba.fastjson.JSON.parseObject(detailJson);
        assertEquals("HUMAN", detail.getString("fromType"));
        assertEquals("AGENT", detail.getString("toType"));
    }

    @Test
    void humanHandoff_writesDetailJsonWithFromTypeAndToType() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        AgentRoleResolver roleResolver = mock(AgentRoleResolver.class);
        AgentSdlcResolver sdlcResolver = mock(AgentSdlcResolver.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);

        WorkitemDO w = workitem(500L, 100L, 3);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(40014L);
        when(workitemDao.findByIdForUpdate(500L, 100L)).thenReturn(w);
        when(roleResolver.resolveOnlineAgentId(100L, "77")).thenReturn(null);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 77L, 3, 0L)).thenReturn(1);

        HandoffService svc = new HandoffService(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, workspaceMemberDao, userDao, eventDao);
        svc.handle(100L, 500L, 300L, "77", "HUMAN");

        ArgumentCaptor<com.aliyun.autowonder.workitem.WorkitemEventDO> captor =
                ArgumentCaptor.forClass(com.aliyun.autowonder.workitem.WorkitemEventDO.class);
        verify(eventDao).insert(captor.capture());
        String detailJson = captor.getValue().getDetailJson();
        assertNotNull(detailJson);
        com.alibaba.fastjson.JSONObject detail = com.alibaba.fastjson.JSON.parseObject(detailJson);
        assertEquals("AGENT", detail.getString("fromType"));
        assertEquals("HUMAN", detail.getString("toType"));
    }
}
