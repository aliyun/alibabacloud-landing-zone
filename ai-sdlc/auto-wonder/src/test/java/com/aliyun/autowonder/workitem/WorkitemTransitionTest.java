package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDO;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkitemTransitionTest {

    WorkitemDao workitemDao;
    WorkitemCommentDao commentDao;
    WorkitemEventDao eventDao;
    StatusTemplateDao templateDao;
    StatusNodeDao nodeDao;
    StatusTransitionDao transitionDao;
    WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        eventDao = mock(WorkitemEventDao.class);
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        transitionDao = mock(StatusTransitionDao.class);
        service = new WorkitemService(workitemDao, commentDao, eventDao,
                templateDao, nodeDao, transitionDao,
                mock(com.aliyun.autowonder.sdlc.SdlcDao.class), mock(com.aliyun.autowonder.sdlc.SdlcStepDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                mock(com.aliyun.autowonder.agent.AgentDao.class),
                mock(com.aliyun.autowonder.dispatch.AgentSdlcResolver.class),
                mock(com.aliyun.autowonder.squad.SquadMemberDao.class),
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                mock(com.aliyun.autowonder.user.UserDao.class),
                mock(com.aliyun.autowonder.guidance.GuidanceDao.class),
                mock(com.aliyun.autowonder.websocket.PresenceManager.class),
                mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private WorkitemDO workitem(long id, long templateId, long nodeId, int version) {
        WorkitemDO w = new WorkitemDO();
        w.setId(id);
        w.setTenantId(100L);
        w.setWorkType("REQ");
        w.setTemplateId(templateId);
        w.setStatusNodeId(nodeId);
        w.setVersion(version);
        return w;
    }

    private StatusNodeDO node(long id, String code) {
        StatusNodeDO n = new StatusNodeDO();
        n.setId(id);
        n.setCode(code);
        return n;
    }

    @Test
    void transitionLegalUpdatesStatusAndWritesEvent() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        StatusTransitionDO tr = new StatusTransitionDO();
        tr.setTemplateId(10L);
        tr.setFromNodeId(20L);
        tr.setToNodeId(21L);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(tr);
        when(nodeDao.findById(20L)).thenReturn(node(20L, "developing"));
        when(nodeDao.findById(21L)).thenReturn(node(21L, "verifying"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(0), eq(9L))).thenReturn(1);

        service.transition(5L, 21L, 100L, 9L);

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, 9L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "STATUS_CHANGE".equals(e.getEventType())
                        && "developing".equals(e.getFromVal())
                        && "verifying".equals(e.getToVal())));
    }

    @Test
    void transitionIllegalThrows13004() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.transition(5L, 99L, 100L, 9L));
        assertEquals("13004", ex.getCode());
        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void transitionVersionConflictThrows13005() {
        WorkitemDO w = workitem(5L, 10L, 20L, 3);
        when(workitemDao.findById(5L)).thenReturn(w);
        StatusTransitionDO tr = new StatusTransitionDO();
        tr.setToNodeId(21L);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(tr);
        when(nodeDao.findById(anyLong())).thenReturn(node(20L, "developing"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(3), eq(9L))).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.transition(5L, 21L, 100L, 9L));
        assertEquals("13005", ex.getCode());
    }

    @Test
    void transitionWorkitemNotFoundThrows13003() {
        when(workitemDao.findById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.transition(404L, 21L, 100L, 9L));
        assertEquals("13003", ex.getCode());
    }

    @Test
    void agentTransitionResolvesCodeAndWritesAgentEvent() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findByTemplateAndCode(10L, "verifying")).thenReturn(node(21L, "verifying"));
        StatusTransitionDO tr = new StatusTransitionDO();
        tr.setToNodeId(21L);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(tr);
        when(nodeDao.findById(20L)).thenReturn(node(20L, "developing"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(0), eq(555L))).thenReturn(1);

        service.agentTransition(5L, "verifying", 100L, 555L);

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, 555L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "STATUS_CHANGE".equals(e.getEventType())
                        && "AGENT".equals(e.getActorType())
                        && e.getActorRef() == 555L
                        && "verifying".equals(e.getToVal())));
    }

    @Test
    void agentTransitionUnknownCodeThrows() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findByTemplateAndCode(10L, "nope")).thenReturn(null);
        assertThrows(BizException.class, () -> service.agentTransition(5L, "nope", 100L, 555L));
        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }
}
