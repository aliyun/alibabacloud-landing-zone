package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import com.aliyun.autowonder.workitem.dto.EventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkitemTimelineTest {

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

    @Test
    void timelineMapsEventsInOrder() {
        WorkitemEventDO e1 = new WorkitemEventDO();
        e1.setId(1L);
        e1.setEventType("CREATE");
        e1.setToVal("new");
        WorkitemEventDO e2 = new WorkitemEventDO();
        e2.setId(2L);
        e2.setEventType("STATUS_CHANGE");
        e2.setFromVal("new");
        e2.setToVal("developing");
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(e1, e2));

        List<EventVO> vos = service.timeline(5L);

        assertEquals(2, vos.size());
        assertEquals("CREATE", vos.get(0).getEventType());
        assertEquals("STATUS_CHANGE", vos.get(1).getEventType());
        assertEquals("developing", vos.get(1).getToVal());
    }

    @Test
    void timelineEmptyReturnsEmptyList() {
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of());
        assertTrue(service.timeline(5L).isEmpty());
    }
}
