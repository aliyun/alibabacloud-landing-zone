package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Web（PUT /api/workitems/{id}/content）与 MCP（autowonder.update_workitem）都走
 * {@link WorkitemService#updateContent}，因此这里的 titleChanged 语义同时覆盖两条入口：
 * 只改正文时不能触发任何下游重复检测。
 */
class WorkitemContentUpdateEventTest {

    private WorkitemDao workitemDao;
    private ApplicationEventPublisher publisher;
    private com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao externalLinkDao;
    private com.aliyun.autowonder.statemachine.StatusTemplateDao templateDao;
    private com.aliyun.autowonder.statemachine.StatusNodeDao nodeDao;
    private WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        publisher = mock(ApplicationEventPublisher.class);
        externalLinkDao = mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class);
        templateDao = mock(com.aliyun.autowonder.statemachine.StatusTemplateDao.class);
        nodeDao = mock(com.aliyun.autowonder.statemachine.StatusNodeDao.class);
        var agentDao = mock(com.aliyun.autowonder.agent.AgentDao.class);
        var agentVersionDao = mock(com.aliyun.autowonder.agent.AgentVersionDao.class);
        var stepDao = mock(com.aliyun.autowonder.sdlc.SdlcStepDao.class);
        service = new WorkitemService(workitemDao, mock(WorkitemCommentDao.class),
                mock(WorkitemEventDao.class), templateDao,
                nodeDao,
                mock(com.aliyun.autowonder.statemachine.StatusTransitionDao.class),
                mock(com.aliyun.autowonder.sdlc.SdlcDao.class), stepDao,
                mock(com.aliyun.autowonder.dispatch.DispatchDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                agentDao, new AgentSdlcResolver(agentDao, agentVersionDao, stepDao),
                mock(com.aliyun.autowonder.squad.SquadMemberDao.class),
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                mock(com.aliyun.autowonder.user.UserDao.class),
                mock(com.aliyun.autowonder.guidance.GuidanceDao.class),
                mock(com.aliyun.autowonder.websocket.PresenceManager.class),
                externalLinkDao, publisher);
        when(externalLinkDao.listByWorkitem(anyLong(), anyLong())).thenReturn(List.of());
        when(workitemDao.updateContent(anyLong(), anyLong(), any(), any(), anyInt(), anyLong())).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            WorkitemDO inserted = invocation.getArgument(0);
            inserted.setId(321L);
            return 1;
        }).when(workitemDao).insert(any(WorkitemDO.class));
    }

    @Test
    void createPublishesTheCreatedEventForOptionalDownstreamChecks() {
        var template = new com.aliyun.autowonder.statemachine.StatusTemplateDO();
        template.setId(11L);
        var init = new com.aliyun.autowonder.statemachine.StatusNodeDO();
        init.setId(22L);
        init.setCode("new");
        when(templateDao.findDefaultByType("REQ")).thenReturn(template);
        when(nodeDao.findInitNode(11L)).thenReturn(init);
        var request = new com.aliyun.autowonder.workitem.dto.CreateWorkitemRequest();
        request.setWorkType("REQ");
        request.setTitle("fresh title");
        request.setContentMd("body");

        service.create(request, 7L, 8L);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        WorkitemCreatedEvent event = (WorkitemCreatedEvent) captor.getValue();
        assertEquals(321L, event.workitemId());
        assertEquals("fresh title", event.title());
        assertEquals(7L, event.tenantId());
        assertEquals(8L, event.userId());
    }

    @Test
    void bodyOnlyEditMarksTheTitleAsUnchanged() {
        when(workitemDao.findById(100L)).thenReturn(row("same title", "old body"));

        service.updateContent(100L, null, "new body", 7L, 8L);

        WorkitemContentUpdatedEvent event = captured();
        assertFalse(event.titleChanged());
        assertEquals("new body", event.contentMd());
    }

    @Test
    void changedTitleMarksTheTitleAsChanged() {
        when(workitemDao.findById(100L)).thenReturn(row("old title", "body"));

        service.updateContent(100L, "new title", "body", 7L, 8L);

        assertTrue(captured().titleChanged());
    }

    @Test
    void noChangePublishesNothing() {
        when(workitemDao.findById(100L)).thenReturn(row("same title", "same body"));

        service.updateContent(100L, "same title", "same body", 7L, 8L);

        verify(publisher, never()).publishEvent(any(WorkitemContentUpdatedEvent.class));
    }

    private WorkitemContentUpdatedEvent captured() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        return (WorkitemContentUpdatedEvent) captor.getValue();
    }

    private WorkitemDO row(String title, String contentMd) {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setVersion(1);
        w.setTitle(title);
        w.setContentMd(contentMd);
        return w;
    }
}
