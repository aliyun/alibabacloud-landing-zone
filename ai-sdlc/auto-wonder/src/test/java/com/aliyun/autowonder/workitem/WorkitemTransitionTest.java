package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDO;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        n.setTenantId(100L);
        n.setTemplateId(10L);
        n.setCode(code);
        return n;
    }

    private StatusNodeDO nodeWithCategory(long id, String code, String category) {
        StatusNodeDO n = node(id, code);
        n.setCategory(category);
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

        WorkitemVO vo = service.transition(5L, 21L, 100L, 9L);

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, 9L);
        assertNull(vo.getTransitionWarning());
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "STATUS_CHANGE".equals(e.getEventType())
                        && "developing".equals(e.getFromVal())
                        && "verifying".equals(e.getToVal())
                        && e.getDetailJson() != null
                        && e.getDetailJson().contains("\"source\":\"HUMAN\"")
                        && !e.getDetailJson().contains("outOfTemplate")));
    }

    @Test
    void humanTransitionCanPublishIntoFinalDoneState() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        StatusTransitionDO tr = new StatusTransitionDO();
        tr.setTemplateId(10L);
        tr.setFromNodeId(20L);
        tr.setToNodeId(21L);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(tr);
        when(nodeDao.findById(20L)).thenReturn(node(20L, "pending-decision"));
        StatusNodeDO published = node(21L, "released");
        published.setName("已发布");
        published.setCategory("DONE");
        when(nodeDao.findById(21L)).thenReturn(published);
        when(workitemDao.updateStatus(5L, 100L, 21L, 0, 9L)).thenReturn(1);

        service.transition(5L, 21L, 100L, 9L);

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, 9L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "HUMAN".equals(e.getActorType()) && "released".equals(e.getToVal())));
    }

    @Test
    void transitionOutOfTemplateIsAllowedWithWarningAndAudit() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 99L)).thenReturn(null);
        when(nodeDao.findById(20L)).thenReturn(node(20L, "new"));
        when(nodeDao.findById(99L)).thenReturn(node(99L, "released"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(99L), eq(0), eq(9L))).thenReturn(1);

        WorkitemVO vo = service.transition(5L, 99L, 100L, 9L);

        verify(workitemDao).updateStatus(5L, 100L, 99L, 0, 9L);
        assertEquals(WorkitemService.OUT_OF_TEMPLATE_TRANSITION_WARNING, vo.getTransitionWarning());
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "STATUS_CHANGE".equals(e.getEventType())
                        && "HUMAN".equals(e.getActorType())
                        && e.getDetailJson() != null
                        && e.getDetailJson().contains("\"source\":\"HUMAN\"")
                        && e.getDetailJson().contains("outOfTemplate")));
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
    void checkedTransitionRejectsStaleSource() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 22L, 1));
        BizException ex = assertThrows(BizException.class,
                () -> service.transition(5L, 21L, 100L, 9L, 20L, 1));
        assertEquals("13005", ex.getCode());
        verifyNoInteractions(transitionDao, eventDao);
        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void checkedTransitionRejectsStaleVersion() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 2));
        BizException ex = assertThrows(BizException.class,
                () -> service.transition(5L, 21L, 100L, 9L, 20L, 1));
        assertEquals("13005", ex.getCode());
        verifyNoInteractions(transitionDao, eventDao);
    }

    @Test
    void checkedTransitionOutOfTemplateStillAppliesWithWarning() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 1));
        when(transitionDao.findByTemplateFromTo(10L, 20L, 99L)).thenReturn(null);
        when(nodeDao.findById(anyLong())).thenReturn(node(20L, "new"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(99L), eq(1), eq(9L))).thenReturn(1);

        WorkitemVO vo = service.transition(5L, 99L, 100L, 9L, 20L, 1);

        verify(workitemDao).updateStatus(5L, 100L, 99L, 1, 9L);
        assertEquals(WorkitemService.OUT_OF_TEMPLATE_TRANSITION_WARNING, vo.getTransitionWarning());
    }

    @Test
    void checkedTransitionUpdatesMatchingSnapshot() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 1));
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(new StatusTransitionDO());
        when(nodeDao.findById(21L)).thenReturn(node(21L, "verifying"));
        when(workitemDao.updateStatus(5L, 100L, 21L, 1, 9L)).thenReturn(1);
        service.transition(5L, 21L, 100L, 9L, 20L, 1);
        verify(workitemDao).updateStatus(5L, 100L, 21L, 1, 9L);
        verify(eventDao).insert(any(WorkitemEventDO.class));
    }

    @Test
    void transitionRejectsOtherWorkspace() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 1));
        BizException ex = assertThrows(BizException.class,
                () -> service.transition(5L, 21L, 200L, 9L));
        assertEquals("13003", ex.getCode());
        verifyNoInteractions(transitionDao, eventDao);
    }

    @Test
    void transitionRejectsMissingStateMachine() {
        WorkitemDO w = workitem(5L, 10L, 20L, 1);
        w.setStatusNodeId(null);
        when(workitemDao.findById(5L)).thenReturn(w);
        BizException ex = assertThrows(BizException.class,
                () -> service.transition(5L, 21L, 100L, 9L));
        assertEquals("13004", ex.getCode());
        verifyNoInteractions(transitionDao, eventDao);
    }

    @Test
    void transitionRejectsMissingTargetNode() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 1));
        when(nodeDao.findById(21L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.transition(5L, 21L, 100L, 9L));
        assertEquals("13004", ex.getCode());
        verifyNoInteractions(transitionDao, eventDao);
        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void transitionRejectsTargetNodeFromOtherTenantOrTemplate() {
        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 1));
        StatusNodeDO foreignTenant = node(21L, "developing");
        foreignTenant.setTenantId(200L);
        when(nodeDao.findById(21L)).thenReturn(foreignTenant);
        BizException ex = assertThrows(BizException.class,
                () -> service.transition(5L, 21L, 100L, 9L));
        assertEquals("13004", ex.getCode());

        StatusNodeDO foreignTemplate = node(22L, "developing");
        foreignTemplate.setTemplateId(999L);
        when(nodeDao.findById(22L)).thenReturn(foreignTemplate);
        ex = assertThrows(BizException.class,
                () -> service.transition(5L, 22L, 100L, 9L));
        assertEquals("13004", ex.getCode());

        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(eventDao, never()).insert(any(WorkitemEventDO.class));
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
        when(nodeDao.findById(21L)).thenReturn(node(21L, "verifying"));
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

    @Test
    void agentCanAdvanceWorkitemIntoFinalDoneStateWhenTransitionExists() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        StatusNodeDO released = node(21L, "released");
        released.setCategory("DONE");
        when(nodeDao.findByTemplateAndCode(10L, "released")).thenReturn(released);
        StatusTransitionDO transition = new StatusTransitionDO();
        transition.setToNodeId(21L);
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(transition);
        when(nodeDao.findById(21L)).thenReturn(released);
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(0), eq(555L))).thenReturn(1);

        when(nodeDao.findById(20L)).thenReturn(node(20L, "pending-decision"));

        service.agentTransition(5L, "released", 100L, 555L);

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, 555L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "STATUS_CHANGE".equals(e.getEventType())
                        && "AGENT".equals(e.getActorType())
                        && e.getActorRef() == 555L
                        && "released".equals(e.getToVal())));
    }

    @Test
    void agentTransitionOutOfTemplateIsAllowedWithWarningAndAudit() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findByTemplateAndCode(10L, "released")).thenReturn(node(21L, "released"));
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(null);
        when(nodeDao.findById(20L)).thenReturn(node(20L, "developing"));
        when(nodeDao.findById(21L)).thenReturn(node(21L, "released"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(0), eq(555L))).thenReturn(1);

        WorkitemVO vo = service.agentTransition(5L, "released", 100L, 555L);

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, 555L);
        assertEquals(WorkitemService.OUT_OF_TEMPLATE_TRANSITION_WARNING, vo.getTransitionWarning());
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "AGENT".equals(e.getActorType())
                        && e.getDetailJson() != null
                        && e.getDetailJson().contains("\"source\":\"AGENT\"")
                        && e.getDetailJson().contains("outOfTemplate")));
    }

    @Test
    void autoAdvanceOnDeliveryStartMovesInitWorkitemToFirstInProgressNode() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findById(20L)).thenReturn(nodeWithCategory(20L, "new", "INIT"));
        when(nodeDao.listByTemplateId(10L)).thenReturn(List.of(
                nodeWithCategory(20L, "new", "INIT"),
                nodeWithCategory(21L, "developing", "IN_PROGRESS"),
                nodeWithCategory(22L, "verifying", "IN_PROGRESS")));
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(new StatusTransitionDO());
        when(nodeDao.findById(21L)).thenReturn(nodeWithCategory(21L, "developing", "IN_PROGRESS"));
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(0), isNull())).thenReturn(1);

        assertTrue(service.autoAdvanceOnDeliveryStart(100L, 5L));

        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, null);
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "STATUS_CHANGE".equals(e.getEventType())
                        && "SYSTEM".equals(e.getActorType())
                        && e.getActorRef() == null
                        && "new".equals(e.getFromVal())
                        && "developing".equals(e.getToVal())
                        && e.getDetailJson() != null
                        && e.getDetailJson().contains("SYSTEM_DELIVERY_START")));
    }

    @Test
    void autoAdvanceOnDeliveryStartIsCaseInsensitiveOnNodeCategory() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findById(20L)).thenReturn(nodeWithCategory(20L, "new", "init"));
        when(nodeDao.listByTemplateId(10L)).thenReturn(List.of(
                nodeWithCategory(20L, "new", "init"),
                nodeWithCategory(21L, "developing", "in_progress")));
        when(nodeDao.findById(21L)).thenReturn(nodeWithCategory(21L, "developing", "in_progress"));
        when(transitionDao.findByTemplateFromTo(10L, 20L, 21L)).thenReturn(new StatusTransitionDO());
        when(workitemDao.updateStatus(eq(5L), eq(100L), eq(21L), eq(0), isNull())).thenReturn(1);

        assertTrue(service.autoAdvanceOnDeliveryStart(100L, 5L));
        verify(workitemDao).updateStatus(5L, 100L, 21L, 0, null);
    }

    @Test
    void autoAdvanceOnDeliveryStartSkipsNonInitWorkitem() {
        WorkitemDO w = workitem(5L, 10L, 21L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findById(21L)).thenReturn(nodeWithCategory(21L, "developing", "IN_PROGRESS"));

        assertFalse(service.autoAdvanceOnDeliveryStart(100L, 5L));

        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), any());
        verify(eventDao, never()).insert(any(WorkitemEventDO.class));
    }

    @Test
    void autoAdvanceOnDeliveryStartDoesNothingWithoutInProgressNode() {
        WorkitemDO w = workitem(5L, 10L, 20L, 0);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(nodeDao.findById(20L)).thenReturn(nodeWithCategory(20L, "new", "INIT"));
        when(nodeDao.listByTemplateId(10L)).thenReturn(List.of(
                nodeWithCategory(20L, "new", "INIT"),
                nodeWithCategory(23L, "released", "DONE")));

        assertFalse(service.autoAdvanceOnDeliveryStart(100L, 5L));

        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void autoAdvanceOnDeliveryStartIgnoresMissingOrForeignWorkitem() {
        when(workitemDao.findById(404L)).thenReturn(null);
        assertFalse(service.autoAdvanceOnDeliveryStart(100L, 404L));

        when(workitemDao.findById(5L)).thenReturn(workitem(5L, 10L, 20L, 0));
        assertFalse(service.autoAdvanceOnDeliveryStart(200L, 5L));

        verify(workitemDao, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void transitionDetailJsonRecordsSourceReasonAndOut_ofTemplateFlag() {
        assertEquals("{\"source\":\"HUMAN\"}", WorkitemService.transitionDetailJson("HUMAN", null, false));
        assertEquals("{\"source\":\"HUMAN\",\"reason\":\"r\",\"outOfTemplate\":true}",
                WorkitemService.transitionDetailJson("HUMAN", "r", true));
        assertNull(WorkitemService.transitionDetailJson(null, null, false));
    }
}
