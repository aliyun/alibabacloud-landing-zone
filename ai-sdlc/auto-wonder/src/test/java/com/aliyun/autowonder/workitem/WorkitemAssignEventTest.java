package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.WorkitemAssignedEvent;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class WorkitemAssignEventTest {

    private WorkitemDao workitemDao;
    private ApplicationEventPublisher publisher;
    private SdlcDao sdlcDao;
    private SdlcStepDao stepDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private SquadMemberDao squadMemberDao;
    private WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        publisher = mock(ApplicationEventPublisher.class);
        sdlcDao = mock(SdlcDao.class);
        stepDao = mock(SdlcStepDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        squadMemberDao = mock(SquadMemberDao.class);
        com.aliyun.autowonder.dispatch.AgentSdlcResolver sdlcResolver =
                new com.aliyun.autowonder.dispatch.AgentSdlcResolver(agentDao, agentVersionDao, stepDao);
        service = new WorkitemService(workitemDao, mock(WorkitemCommentDao.class),
                mock(WorkitemEventDao.class), mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), mock(StatusTransitionDao.class),
                sdlcDao, stepDao,
                mock(com.aliyun.autowonder.dispatch.DispatchDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                agentDao, sdlcResolver,
                squadMemberDao,
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                mock(com.aliyun.autowonder.user.UserDao.class),
                mock(com.aliyun.autowonder.guidance.GuidanceDao.class),
                mock(com.aliyun.autowonder.websocket.PresenceManager.class),
                mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class),
                publisher);
    }

    private WorkitemDO row() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setVersion(1);
        w.setCurrentStepId(5L);
        return w;
    }

    @Test
    void publishesEventWhenAssignedToAgent() {
        when(workitemDao.findById(100L)).thenReturn(row());
        when(workitemDao.updateAssignee(eq(100L), eq(7L), eq("AGENT"), eq(42L), eq(1), eq(3L)))
                .thenReturn(1);

        service.assign(100L, "AGENT", 42L, null, null, 7L, 3L);

        ArgumentCaptor<WorkitemAssignedEvent> cap = ArgumentCaptor.forClass(WorkitemAssignedEvent.class);
        verify(publisher).publishEvent(cap.capture());
        WorkitemAssignedEvent e = cap.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(7L, e.getTenantId());
        org.junit.jupiter.api.Assertions.assertEquals(100L, e.getWorkitemId());
        org.junit.jupiter.api.Assertions.assertEquals(5L, e.getSdlcStepId());
        org.junit.jupiter.api.Assertions.assertEquals(42L, e.getAgentId());
        org.junit.jupiter.api.Assertions.assertEquals(3L, e.getUserId());
    }

    @Test
    void doesNotPublishWhenAssignedToHuman() {
        when(workitemDao.findById(100L)).thenReturn(row());
        when(workitemDao.updateAssignee(eq(100L), eq(7L), eq("HUMAN"), eq(42L), eq(1), eq(3L)))
                .thenReturn(1);

        service.assign(100L, "HUMAN", 42L, null, null, 7L, 3L);

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void bindsSdlcAndFirstStepWhenStarting() {
        WorkitemDO before = row();
        before.setCurrentStepId(null); // fresh workitem, not yet bound
        before.setSdlcId(null);
        WorkitemDO afterBind = row();
        afterBind.setSdlcId(9L);
        afterBind.setCurrentStepId(50L); // first step
        when(workitemDao.findById(100L)).thenReturn(before, afterBind);
        when(workitemDao.updateAssignee(eq(100L), eq(7L), eq("AGENT"), eq(42L), eq(1), eq(3L)))
                .thenReturn(1);
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, 7L));
        SdlcStepDO s1 = step(50L, 7L, 1);
        SdlcStepDO s2 = step(51L, 7L, 2);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(s2, s1)); // out of order on purpose
        when(workitemDao.updateSdlcAndStep(eq(100L), eq(7L), eq(9L), eq(50L), eq(2), eq(3L)))
                .thenReturn(1);

        service.assign(100L, "AGENT", 42L, 9L, null, 7L, 3L);

        verify(workitemDao).updateSdlcAndStep(100L, 7L, 9L, 50L, 2, 3L);
        ArgumentCaptor<WorkitemAssignedEvent> cap = ArgumentCaptor.forClass(WorkitemAssignedEvent.class);
        verify(publisher).publishEvent(cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals(50L, cap.getValue().getSdlcStepId());
        org.junit.jupiter.api.Assertions.assertEquals(42L, cap.getValue().getAgentId());
    }

    @Test
    void skipsBindWhenAlreadyBound() {
        WorkitemDO bound = row();
        bound.setSdlcId(9L);
        bound.setCurrentStepId(5L);
        when(workitemDao.findById(100L)).thenReturn(bound);
        when(workitemDao.updateAssignee(eq(100L), eq(7L), eq("AGENT"), eq(42L), eq(1), eq(3L)))
                .thenReturn(1);

        service.assign(100L, "AGENT", 42L, 99L, null, 7L, 3L);

        verify(workitemDao, never()).updateSdlcAndStep(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        ArgumentCaptor<WorkitemAssignedEvent> cap = ArgumentCaptor.forClass(WorkitemAssignedEvent.class);
        verify(publisher).publishEvent(cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals(5L, cap.getValue().getSdlcStepId());
    }

    @Test
    void bindsAssignedAgentsOwnSdlcWhenStartingWithoutRequestSdlc() {
        WorkitemDO before = row();
        before.setCurrentStepId(null);
        before.setSdlcId(null);
        WorkitemDO afterBind = row();
        afterBind.setSdlcId(9L);
        afterBind.setCurrentStepId(50L);
        when(workitemDao.findById(100L)).thenReturn(before, afterBind);
        when(workitemDao.updateAssignee(eq(100L), eq(7L), eq("AGENT"), eq(42L), eq(1), eq(3L)))
                .thenReturn(1);
        when(agentDao.findById(42L)).thenReturn(agent(42L, 7L, 420L));
        when(agentVersionDao.findById(420L)).thenReturn(agentVersion(420L, 7L, 42L, 9L));
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, 7L));
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(step(50L, 7L, 1)));
        when(workitemDao.updateSdlcAndStep(eq(100L), eq(7L), eq(9L), eq(50L), eq(2), eq(3L)))
                .thenReturn(1);

        service.assign(100L, "AGENT", 42L, null, null, 7L, 3L);

        verify(workitemDao).updateSdlcAndStep(100L, 7L, 9L, 50L, 2, 3L);
        ArgumentCaptor<WorkitemAssignedEvent> cap = ArgumentCaptor.forClass(WorkitemAssignedEvent.class);
        verify(publisher).publishEvent(cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals(50L, cap.getValue().getSdlcStepId());
        org.junit.jupiter.api.Assertions.assertEquals(42L, cap.getValue().getAgentId());
    }

    @Test
    void rejectsAgentOutsideSelectedSquad() {
        when(workitemDao.findById(100L)).thenReturn(row());
        when(squadMemberDao.findBySquadAndAgent(88L, 42L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.assign(100L, "AGENT", 42L, null, 88L, 7L, 3L));

        assertEquals(ErrorCode.SQUAD_NOT_FOUND.getCode(), ex.getCode());
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), any(), anyLong(), anyInt(), anyLong());
        verify(publisher, never()).publishEvent(any());
    }

    private static SdlcDO sdlc(long id, long tenantId) {
        SdlcDO s = new SdlcDO();
        s.setId(id);
        s.setTenantId(tenantId);
        return s;
    }

    private static SdlcStepDO step(long id, long tenantId, int order) {
        SdlcStepDO s = new SdlcStepDO();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setStepOrder(order);
        return s;
    }

    private static AgentDO agent(long id, long tenantId, long onlineVersionId) {
        AgentDO a = new AgentDO();
        a.setId(id);
        a.setTenantId(tenantId);
        a.setOnlineVersionId(onlineVersionId);
        return a;
    }

    private static AgentVersionDO agentVersion(long id, long tenantId, long agentId, long sdlcId) {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(id);
        v.setTenantId(tenantId);
        v.setAgentId(agentId);
        v.setSdlcId(sdlcId);
        return v;
    }
}
