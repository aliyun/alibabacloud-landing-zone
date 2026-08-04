package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkitemMutationTest {

    WorkitemDao workitemDao;
    WorkitemCommentDao commentDao;
    WorkitemEventDao eventDao;
    StatusTemplateDao templateDao;
    StatusNodeDao nodeDao;
    StatusTransitionDao transitionDao;
    com.aliyun.autowonder.dispatch.DispatchDao dispatchDao;
    ExternalWorkitemLinkDao externalWorkitemLinkDao;
    ApplicationEventPublisher eventPublisher;
    WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        eventDao = mock(WorkitemEventDao.class);
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        transitionDao = mock(StatusTransitionDao.class);
        dispatchDao = mock(com.aliyun.autowonder.dispatch.DispatchDao.class);
        externalWorkitemLinkDao = mock(ExternalWorkitemLinkDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new WorkitemService(workitemDao, commentDao, eventDao,
                templateDao, nodeDao, transitionDao,
                mock(com.aliyun.autowonder.sdlc.SdlcDao.class), mock(com.aliyun.autowonder.sdlc.SdlcStepDao.class),
                dispatchDao,
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                mock(com.aliyun.autowonder.agent.AgentDao.class),
                mock(com.aliyun.autowonder.dispatch.AgentSdlcResolver.class),
                mock(com.aliyun.autowonder.squad.SquadMemberDao.class),
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                mock(com.aliyun.autowonder.user.UserDao.class),
                mock(com.aliyun.autowonder.guidance.GuidanceDao.class),
                mock(com.aliyun.autowonder.websocket.PresenceManager.class),
                externalWorkitemLinkDao,
                eventPublisher);
    }

    private WorkitemDO workitem(long id, int version, long assigneeRef) {
        WorkitemDO w = new WorkitemDO();
        w.setId(id);
        w.setTenantId(100L);
        w.setTitle("old title");
        w.setContentMd("old body");
        w.setVersion(version);
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(assigneeRef);
        return w;
    }

    @Test
    void assignUpdatesAndWritesAssignEvent() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 0, 9L));
        when(workitemDao.updateAssignee(eq(5L), eq(100L), eq("HUMAN"), eq(22L), eq(0), eq(7L))).thenReturn(1);

        service.assign(5L, "HUMAN", 22L, null, null, 100L, 7L);

        verify(workitemDao).updateAssignee(5L, 100L, "HUMAN", 22L, 0, 7L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "ASSIGN".equals(e.getEventType())
                        && "9".equals(e.getFromVal())
                        && "22".equals(e.getToVal())));
    }

    @Test
    void assignVersionConflictThrows13005() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 2, 9L));
        when(workitemDao.updateAssignee(eq(5L), eq(100L), eq("HUMAN"), eq(22L), eq(2), eq(7L))).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.assign(5L, "HUMAN", 22L, null, null, 100L, 7L));
        assertEquals("13005", ex.getCode());
    }

    @Test
    void assignWorkitemNotFoundThrows13003() {
        when(workitemDao.findById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.assign(404L, "HUMAN", 22L, null, null, 100L, 7L));
        assertEquals("13003", ex.getCode());
    }

    @Test
    void updateContentUpdatesAndWritesEditEvent() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 0, 9L));
        when(workitemDao.updateContent(eq(5L), eq(100L), eq("new title"), eq("new body"), eq(0), eq(7L))).thenReturn(1);

        service.updateContent(5L, "new title", "new body", 100L, 7L);

        verify(workitemDao).updateContent(5L, 100L, "new title", "new body", 0, 7L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) -> "EDIT".equals(e.getEventType())));
        verify(eventPublisher).publishEvent(new WorkitemContentUpdatedEvent(100L, 5L, "new title", "new body", 7L));
    }

    @Test
    void updateContentKeepsExistingFieldWhenRequestOmitsIt() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 0, 9L));
        when(workitemDao.updateContent(eq(5L), eq(100L), eq("old title"), eq("new body"), eq(0), eq(7L))).thenReturn(1);

        service.updateContent(5L, null, "new body", 100L, 7L);

        verify(workitemDao).updateContent(5L, 100L, "old title", "new body", 0, 7L);
        verify(eventPublisher).publishEvent(new WorkitemContentUpdatedEvent(100L, 5L, "old title", "new body", 7L));
    }

    @Test
    void updateContentReturnsWithoutEventWhenNothingChanged() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 0, 9L));

        service.updateContent(5L, null, null, 100L, 7L);

        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).insert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateContentVersionConflictThrows13005() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 4, 9L));
        when(workitemDao.updateContent(eq(5L), eq(100L), any(), any(), eq(4), eq(7L))).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.updateContent(5L, "t", "b", 100L, 7L));
        assertEquals("13005", ex.getCode());
    }

    @Test
    void deleteSoftDeletesNativeWorkitemAndWritesEvent() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 3, 9L));
        when(externalWorkitemLinkDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of());
        when(dispatchDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of());
        when(workitemDao.softDelete(5L, 100L, 3, 7L)).thenReturn(1);

        service.delete(5L, 100L, 7L);

        verify(workitemDao).softDelete(5L, 100L, 3, 7L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) -> "DELETE".equals(e.getEventType())));
    }

    @Test
    void deleteRejectsExternalLinkedWorkitem() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 3, 9L));
        when(externalWorkitemLinkDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of(new ExternalWorkitemLinkDO()));

        BizException ex = assertThrows(BizException.class, () -> service.delete(5L, 100L, 7L));

        assertEquals("13006", ex.getCode());
        verify(workitemDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void deleteRejectsRunningDispatch() {
        WorkitemDO w = workitem(5L, 3, 9L);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setStatus(DispatchStatus.RUNNING);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(externalWorkitemLinkDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of());
        when(dispatchDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of(dispatch));

        BizException ex = assertThrows(BizException.class, () -> service.delete(5L, 100L, 7L));

        assertEquals("13007", ex.getCode());
        verify(workitemDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void deleteVersionConflictThrows13005() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 4, 9L));
        when(externalWorkitemLinkDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of());
        when(dispatchDao.listByWorkitem(100L, 5L)).thenReturn(java.util.List.of());
        when(workitemDao.softDelete(5L, 100L, 4, 7L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.delete(5L, 100L, 7L));

        assertEquals("13005", ex.getCode());
        verify(eventDao, never()).insert(any());
    }
}
