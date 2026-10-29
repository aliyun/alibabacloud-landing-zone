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
    com.aliyun.autowonder.user.UserDao userDao;
    com.aliyun.autowonder.agent.AgentDao agentDao;
    WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        eventDao = mock(WorkitemEventDao.class);
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        transitionDao = mock(StatusTransitionDao.class);
        userDao = mock(com.aliyun.autowonder.user.UserDao.class);
        agentDao = mock(com.aliyun.autowonder.agent.AgentDao.class);
        service = new WorkitemService(workitemDao, commentDao, eventDao,
                templateDao, nodeDao, transitionDao,
                mock(com.aliyun.autowonder.sdlc.SdlcDao.class), mock(com.aliyun.autowonder.sdlc.SdlcStepDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                agentDao,
                mock(com.aliyun.autowonder.dispatch.AgentSdlcResolver.class),
                mock(com.aliyun.autowonder.squad.SquadMemberDao.class),
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                userDao,
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

    @Test
    void tagChangeUsesDetailJsonForAddedRemovedAndOverflow() {
        WorkitemDO owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(owner);
        when(commentDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        WorkitemEventDO both = tagEvent(1L, "{\"added\":[\"新标签\"],\"removed\":[\"旧标签\"]}");
        WorkitemEventDO added = tagEvent(2L, "{\"added\":[\"t0\",\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\"],\"removed\":[]}");
        WorkitemEventDO removed = tagEvent(3L, "{\"added\":[],\"removed\":[\"旧标签\"]}");
        WorkitemEventDO broken = tagEvent(4L, "not-json");
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(both, added, removed, broken));
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var rows = service.getUnifiedTimeline(5L);
            assertEquals("工单标签已更新: 添加 新标签；移除 旧标签", contentOf(rows, 1L));
            assertEquals("工单标签已更新: 添加 t0、t1、t2、t3、t4 等 2 个", contentOf(rows, 2L));
            assertEquals("工单标签已更新: 移除 旧标签", contentOf(rows, 3L));
            assertEquals("工单标签已更新", contentOf(rows, 4L));
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous);
        }
    }

    private static String contentOf(List<com.aliyun.autowonder.workitem.dto.TimelineItemVO> rows, Long id) {
        return rows.stream().filter(row -> id.equals(row.getId())).findFirst().orElseThrow().getContent();
    }

    @Test
    void tagChangeTimelineRendersHumanAndAgentOperatorsForEveryDirection() {
        WorkitemDO owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(owner);
        when(commentDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        var human = new com.aliyun.autowonder.user.UserDO();
        human.setId(7L); human.setUsername("caihe"); human.setNickname("蔡何");
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(human);
        var agent = new com.aliyun.autowonder.agent.AgentDO();
        agent.setId(12L); agent.setName("AW全栈开发");
        when(agentDao.findById(12L)).thenReturn(agent);
        WorkitemEventDO both = tagEvent(1L, "{\"added\":[\"新标签\"],\"removed\":[\"旧标签\"]}");
        both.setActorType("HUMAN"); both.setActorRef(7L);
        WorkitemEventDO added = tagEvent(2L, "{\"added\":[\"新标签\"],\"removed\":[]}");
        added.setActorType("AGENT"); added.setActorRef(12L);
        WorkitemEventDO removed = tagEvent(3L, "{\"added\":[],\"removed\":[\"旧标签\"]}");
        removed.setActorType("HUMAN"); removed.setActorRef(7L);
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(both, added, removed));
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var rows = service.getUnifiedTimeline(5L);
            assertEquals("工单标签已更新: 添加 新标签；移除 旧标签 （操作人：蔡何(7)）", contentOf(rows, 1L));
            assertEquals("工单标签已更新: 添加 新标签 （操作人：AW全栈开发(12)）", contentOf(rows, 2L));
            assertEquals("工单标签已更新: 移除 旧标签 （操作人：蔡何(7)）", contentOf(rows, 3L));
            assertEquals("蔡何(7)", rows.stream().filter(row -> Long.valueOf(1L).equals(row.getId())).findFirst().orElseThrow().getOperatorDisplayName());
            assertEquals("AW全栈开发(12)", rows.stream().filter(row -> Long.valueOf(2L).equals(row.getId())).findFirst().orElseThrow().getOperatorDisplayName());
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous);
        }
    }

    private static WorkitemEventDO tagEvent(long id, String detailJson) {
        WorkitemEventDO event = new WorkitemEventDO();
        event.setId(id);
        event.setEventType("TAG_CHANGE");
        event.setActorType("SYSTEM");
        event.setActorRef(0L);
        event.setDetailJson(detailJson);
        return event;
    }

    @Test
    void freshSystemTimelineTranslatesFiniteEventAndKeepsHistoryAndUnknownDataRaw() {
        WorkitemDO owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(owner);
        when(commentDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        WorkitemEventDO event = new WorkitemEventDO(); event.setId(1L); event.setEventType("CREATE"); event.setToVal("工单已创建 <b>raw</b>");
        WorkitemEventDO unknown = new WorkitemEventDO(); unknown.setId(2L); unknown.setEventType("RAW_EVENT"); unknown.setToVal("工单已创建");
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(event, unknown));
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var rows = service.getUnifiedTimeline(5L);
            assertEquals("Work item created: 工单已创建 <b>raw</b>", rows.stream().filter(r -> r.getId().equals(1L)).findFirst().orElseThrow().getContent());
            assertEquals("RAW_EVENT: 工单已创建", rows.stream().filter(r -> r.getId().equals(2L)).findFirst().orElseThrow().getContent());
            assertEquals("工单已创建 <b>raw</b>", event.getToVal()); assertEquals("CREATE", event.getEventType());
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
    }

    @Test
    void assignmentCreationControlRendersBothLanguagesAndKeepsRawNamesAndHistory() {
        WorkitemDO owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(owner);
        WorkitemEventDO event = new WorkitemEventDO();
        event.setId(1L); event.setEventType("EXECUTION_CONTROL");
        event.setActorType("HUMAN"); event.setActorRef(7L);
        String detail = "{\"action\":\"START\",\"phase\":\"CONFIRMED\",\"origin\":\"ASSIGNMENT\","
                + "\"dispatchId\":14001,\"requestedById\":7,\"requestedByName\":\"操作者 <b>raw</b>\","
                + "\"agentId\":12,\"agentName\":\"员工原名\"}";
        event.setDetailJson(detail);
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(event));
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            for (var locale : List.of(java.util.Locale.US, java.util.Locale.SIMPLIFIED_CHINESE)) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                var row = service.getUnifiedTimeline(5L).get(0);
                assertEquals(locale.equals(java.util.Locale.US)
                        ? "Assigned and created execution: Dispatch #14001" : "已指派并创建执行：Dispatch #14001", row.getContent());
                assertEquals(14001L, row.getExecutionControl().getDispatchId());
                assertEquals("操作者 <b>raw</b>", row.getExecutionControl().getRequestedByName());
                assertEquals("员工原名", row.getExecutionControl().getAgentName());
                assertEquals(detail, event.getDetailJson());
            }
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous);
        }
    }

    @Test
    void systemTimelineExposesStructuredExecutionControlAndOperator() {
        WorkitemDO owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(owner);
        when(commentDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        WorkitemEventDO event = new WorkitemEventDO();
        event.setId(1L);
        event.setTenantId(100L);
        event.setWorkitemId(5L);
        event.setEventType("EXECUTION_CONTROL");
        event.setActorType("HUMAN");
        event.setActorRef(7L);
        event.setDetailJson("{\"action\":\"CANCEL\",\"phase\":\"REQUESTED\",\"origin\":\"USER\","
                + "\"dispatchId\":12674,\"agentId\":12,\"agentName\":\"Terraform RD\","
                + "\"requestedById\":7,\"requestedByName\":\"过载\",\"stopPending\":true,"
                + "\"supersedesRequestEventId\":9001}");
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(event));
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var row = service.getUnifiedTimeline(5L).get(0);
            assertNotNull(row.getExecutionControl());
            assertEquals("CANCEL", row.getExecutionControl().getAction());
            assertEquals("REQUESTED", row.getExecutionControl().getPhase());
            assertEquals(12674L, row.getExecutionControl().getDispatchId());
            assertEquals(7L, row.getExecutionControl().getRequestedById());
            assertEquals("过载", row.getExecutionControl().getRequestedByName());
            assertEquals("Terraform RD", row.getExecutionControl().getAgentName());
            assertEquals(9001L, row.getExecutionControl().getSupersedesRequestEventId());
            assertTrue(row.getExecutionControl().getStopPending());
            assertTrue(row.getContent().contains("Execution end requested"));
            assertEquals("EXECUTION_CONTROL", event.getEventType());
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous);
        }
    }

    @Test
    void externalNameFallbackHasSourceIdentityWhileRawNamesAndHistoryRemainUntouched() {
        var links = mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class);
        var principals = mock(com.aliyun.autowonder.integration.common.ExternalPrincipalDao.class);
        var view = new com.aliyun.autowonder.integration.ExternalWorkitemViewService(links, principals);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "externalWorkitemViewService", view);
        var principal = new com.aliyun.autowonder.integration.common.ExternalPrincipalDO();
        principal.setId(101L); principal.setProvider("AONE"); principal.setSubjectId("<b>123456789012345</b>"); principal.setDisplayName(null);
        when(principals.findById(101L)).thenReturn(principal);
        var owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(owner);
        var comment = new WorkitemCommentDO(); comment.setId(1L); comment.setAuthorType("EXTERNAL"); comment.setAuthorRef(101L); comment.setContentMd("raw authored content");
        when(commentDao.listByWorkitem(100L, 5L)).thenReturn(List.of(comment));
        var event = new WorkitemEventDO(); event.setId(2L); event.setEventType("EXTERNAL_BUSINESS_OWNER_CHANGE"); event.setActorType("EXTERNAL"); event.setActorRef(101L); event.setFromVal("101"); event.setToVal("101");
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(event));
        var rows = service.getUnifiedTimeline(5L);
        var row = rows.stream().filter(r -> r.getId().equals(1L)).findFirst().orElseThrow();
        assertEquals("Aone 用户（staffId: <b>123456789012345</b>）", row.getAuthorName());
        assertEquals("raw authored content", row.getContent()); assertEquals("101", event.getFromVal());
        var node = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(row);
        assertEquals("AONE", node.path("authorNameFallback").path("provider").asText());
        assertEquals(principal.getSubjectId(), node.path("authorNameFallback").path("subjectId").asText());
        assertFalse(node.path("authorNameFallback").hasNonNull("displayName"));
        verify(principals, atMost(4)).findById(101L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"generated", "authored", "blankProvider", "missing"})
    void currentNameSlotsKeepRawPredicatesAndOmitUnknownIdentity(String branch) {
        var principals = mock(com.aliyun.autowonder.integration.common.ExternalPrincipalDao.class);
        var view = new com.aliyun.autowonder.integration.ExternalWorkitemViewService(mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class), principals);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "externalWorkitemViewService", view);
        var principal = new com.aliyun.autowonder.integration.common.ExternalPrincipalDO(); principal.setId(101L);
        principal.setProvider(branch.equals("blankProvider") ? " " : "JIRA"); principal.setSubjectId(null);
        String authored = "JIRA 用户（ID: null） <b>raw</b>";
        principal.setDisplayName(branch.equals("authored") ? authored : null);
        when(principals.findById(101L)).thenReturn(branch.equals("missing") ? null : principal);
        var owner = new WorkitemDO(); owner.setId(5L); owner.setTenantId(100L); owner.setAssigneeType("EXTERNAL"); owner.setAssigneeRef(101L);
        when(workitemDao.findById(5L)).thenReturn(owner); when(commentDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        var event = new WorkitemEventDO(); event.setId(2L); event.setEventType("STATUS_CHANGE"); event.setActorType("EXTERNAL"); event.setActorRef(101L); event.setFromVal("raw"); event.setToVal("raw2");
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(event));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var workitem = service.toVO(owner); var timeline = service.timeline(5L).get(0); var unified = service.getUnifiedTimeline(5L).get(0);
        String name = branch.equals("authored") ? authored : branch.equals("generated") ? "JIRA 用户（ID: null）" : null;
        assertEquals(name, workitem.getAssigneeName()); assertEquals(name, timeline.getActorName());
        if (branch.equals("generated")) {
            assertEquals("JIRA", json.valueToTree(workitem).path("assigneeNameFallback").path("provider").asText());
            assertEquals("JIRA", json.valueToTree(timeline).path("actorNameFallback").path("provider").asText());
            assertEquals("JIRA", json.valueToTree(unified).path("operatorNameFallback").path("provider").asText());
        } else {
            assertFalse(json.valueToTree(workitem).has("assigneeNameFallback")); assertFalse(json.valueToTree(timeline).has("actorNameFallback"));
            assertFalse(json.valueToTree(unified).has("operatorNameFallback"));
        }
        assertEquals("raw", event.getFromVal()); assertEquals("raw2", unified.getToValDisplay());
        assertNull(principal.getSubjectId()); assertEquals(branch.equals("authored") ? authored : null, principal.getDisplayName());
    }

}
