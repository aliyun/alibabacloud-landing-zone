package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.guidance.GuidanceDao;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDO;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.dto.AgentDeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.CreateWorkitemRequest;
import com.aliyun.autowonder.workitem.dto.DeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.DeliveryStepVO;
import com.aliyun.autowonder.workitem.dto.DispatchAttemptVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkitemServiceTest {

    WorkitemDao workitemDao;
    WorkitemCommentDao commentDao;
    WorkitemEventDao eventDao;
    StatusTemplateDao templateDao;
    StatusNodeDao nodeDao;
    StatusTransitionDao transitionDao;
    SdlcStepDao sdlcStepDao;
    com.aliyun.autowonder.sdlc.SdlcDao sdlcDao;
    DispatchDao dispatchDao;
    DispatchRuntimeEventDao runtimeEventDao;
    com.aliyun.autowonder.agent.AgentDao agentDao;
    UserDao userDao;
    GuidanceDao guidanceDao;
    AgentSdlcResolver sdlcResolver;
    SquadMemberDao squadMemberDao;
    ExecutorDao executorDao;
    PresenceManager presenceManager;
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
        sdlcStepDao = mock(SdlcStepDao.class);
        dispatchDao = mock(DispatchDao.class);
        runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        agentDao = mock(com.aliyun.autowonder.agent.AgentDao.class);
        userDao = mock(UserDao.class);
        guidanceDao = mock(GuidanceDao.class);
        sdlcResolver = mock(AgentSdlcResolver.class);
        squadMemberDao = mock(SquadMemberDao.class);
        executorDao = mock(ExecutorDao.class);
        presenceManager = mock(PresenceManager.class);
        externalWorkitemLinkDao = mock(ExternalWorkitemLinkDao.class);
        sdlcDao = mock(com.aliyun.autowonder.sdlc.SdlcDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new WorkitemService(workitemDao, commentDao, eventDao,
                templateDao, nodeDao, transitionDao,
                sdlcDao, sdlcStepDao,
                dispatchDao, runtimeEventDao, agentDao,
                sdlcResolver,
                squadMemberDao,
                executorDao,
                userDao,
                guidanceDao,
                presenceManager,
                externalWorkitemLinkDao,
                eventPublisher);
    }

    private StatusTemplateDO template(long id) {
        StatusTemplateDO t = new StatusTemplateDO();
        t.setId(id);
        t.setWorkType("REQ");
        return t;
    }

    private StatusNodeDO node(long id, String code) {
        StatusNodeDO n = new StatusNodeDO();
        n.setId(id);
        n.setCode(code);
        n.setCategory("INIT");
        return n;
    }

    private UserDO human(long id, String nickname, String username) {
        UserDO u = new UserDO();
        u.setId(id);
        u.setNickname(nickname);
        u.setUsername(username);
        return u;
    }

    @Test
    void production_constructor_is_explicitly_marked_for_spring_injection() {
        boolean foundAutowiredProductionConstructor = false;
        for (java.lang.reflect.Constructor<?> constructor : WorkitemService.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                if (parameterType.equals(WorkitemCommentMentionDao.class)
                        && constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                    foundAutowiredProductionConstructor = true;
                }
            }
        }

        assertTrue(foundAutowiredProductionConstructor);
    }

    @Test
    void create_binds_default_template_and_init_node_and_writes_event() {
        when(templateDao.findDefaultByType("REQ")).thenReturn(template(200L));
        when(nodeDao.findInitNode(200L)).thenReturn(node(300L, "new"));
        UserDO user = new UserDO();
        user.setId(7L);
        user.setNickname("张三");
        user.setUsername("zhangsan");
        when(userDao.findById(7L)).thenReturn(user);
        doAnswer(inv -> { inv.<WorkitemDO>getArgument(0).setId(10001L); return null; })
                .when(workitemDao).insert(any());
        CreateWorkitemRequest req = new CreateWorkitemRequest();
        req.setWorkType("REQ");
        req.setTitle("登录需求");
        req.setContentMd("正文");

        WorkitemVO vo = service.create(req, 100L, 7L);

        assertNotNull(vo.getId());
        assertEquals(200L, vo.getTemplateId());
        assertEquals(300L, vo.getStatusNodeId());
        assertEquals("HUMAN", vo.getAssigneeType());
        assertEquals("张三", vo.getCreatorName());
        assertEquals("张三(7)", vo.getCreatorDisplayName());
        assertEquals("张三(7)", vo.getAssigneeDisplayName());
        assertEquals(Integer.valueOf(0), vo.getVersion());
        verify(workitemDao).insert(argThat((WorkitemDO w) ->
                w.getTenantId() == 100L && "REQ".equals(w.getWorkType())
                        && w.getTemplateId() == 200L && w.getStatusNodeId() == 300L
                        && "HUMAN".equals(w.getAssigneeType()) && w.getAssigneeRef() == 7L));
        verify(eventDao).insert(argThat((WorkitemEventDO e) -> "CREATE".equals(e.getEventType())));
    }

    @Test
    void create_invalid_work_type_throws() {
        CreateWorkitemRequest req = new CreateWorkitemRequest();
        req.setWorkType("EPIC");
        req.setTitle("x");
        BizException ex = assertThrows(BizException.class, () -> service.create(req, 100L, 7L));
        assertEquals("13001", ex.getCode());
    }

    @Test
    void create_missing_template_throws() {
        when(templateDao.findDefaultByType("REQ")).thenReturn(null);
        CreateWorkitemRequest req = new CreateWorkitemRequest();
        req.setWorkType("REQ");
        req.setTitle("x");
        BizException ex = assertThrows(BizException.class, () -> service.create(req, 100L, 7L));
        assertEquals("13002", ex.getCode());
    }

    @Test
    void create_with_explicit_human_assignee_delegates_to_assign() {
        when(templateDao.findDefaultByType("REQ")).thenReturn(template(200L));
        when(nodeDao.findInitNode(200L)).thenReturn(node(300L, "new"));
        when(userDao.findById(7L)).thenReturn(human(7L, "张三", "zhangsan"));
        when(userDao.findById(99L)).thenReturn(human(99L, "李四", "lisi"));
        doAnswer(inv -> { inv.<WorkitemDO>getArgument(0).setId(10001L); return null; })
                .when(workitemDao).insert(any());
        WorkitemDO inserted = new WorkitemDO();
        inserted.setId(10001L);
        inserted.setTenantId(100L);
        inserted.setVersion(0);
        inserted.setAssigneeType("HUMAN");
        inserted.setAssigneeRef(7L);
        inserted.setCreatorId(7L);
        inserted.setStatusNodeId(300L);
        WorkitemDO reloaded = new WorkitemDO();
        reloaded.setId(10001L);
        reloaded.setTenantId(100L);
        reloaded.setVersion(1);
        reloaded.setAssigneeType("HUMAN");
        reloaded.setAssigneeRef(99L);
        reloaded.setCreatorId(7L);
        reloaded.setStatusNodeId(300L);
        when(workitemDao.findById(10001L)).thenReturn(inserted, reloaded);
        when(workitemDao.updateAssignee(eq(10001L), eq(100L), eq("HUMAN"), eq(99L), anyInt(), eq(7L)))
                .thenReturn(1);

        CreateWorkitemRequest req = new CreateWorkitemRequest();
        req.setWorkType("REQ");
        req.setTitle("指派给李四");
        req.setAssigneeType("HUMAN");
        req.setAssigneeRef(99L);

        WorkitemVO vo = service.create(req, 100L, 7L);

        assertEquals("HUMAN", vo.getAssigneeType());
        assertEquals(Long.valueOf(99L), vo.getAssigneeRef());
        InOrder order = inOrder(workitemDao, eventDao);
        order.verify(workitemDao).insert(argThat((WorkitemDO w) ->
                "HUMAN".equals(w.getAssigneeType()) && w.getAssigneeRef() == 7L));
        order.verify(eventDao).insert(argThat((WorkitemEventDO e) -> "CREATE".equals(e.getEventType())));
        order.verify(workitemDao).updateAssignee(10001L, 100L, "HUMAN", 99L, 0, 7L);
        order.verify(eventDao).insert(argThat((WorkitemEventDO e) -> "ASSIGN".equals(e.getEventType())
                && "7".equals(e.getFromVal()) && "99".equals(e.getToVal())));
    }

    @Test
    void create_with_explicit_agent_assignee_binds_sdlc_and_writes_assign() {
        when(templateDao.findDefaultByType("REQ")).thenReturn(template(200L));
        when(nodeDao.findInitNode(200L)).thenReturn(node(300L, "new"));
        when(userDao.findById(7L)).thenReturn(human(7L, "张三", "zhangsan"));
        AgentDO dba = new AgentDO();
        dba.setId(12L);
        dba.setTenantId(100L);
        dba.setName("DB运维数字人");
        when(agentDao.findById(12L)).thenReturn(dba);
        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(8L);
        sdlc.setTenantId(100L);
        sdlc.setName("DB运维流程");
        when(sdlcDao.findById(8L)).thenReturn(sdlc);
        SdlcStepDO firstStep = new SdlcStepDO();
        firstStep.setId(901L);
        when(sdlcResolver.firstStep(100L, 8L)).thenReturn(firstStep);
        doAnswer(inv -> { inv.<WorkitemDO>getArgument(0).setId(10001L); return null; })
                .when(workitemDao).insert(any());
        WorkitemDO inserted = new WorkitemDO();
        inserted.setId(10001L);
        inserted.setTenantId(100L);
        inserted.setVersion(0);
        inserted.setAssigneeType("HUMAN");
        inserted.setAssigneeRef(7L);
        inserted.setCreatorId(7L);
        inserted.setStatusNodeId(300L);
        WorkitemDO bound = new WorkitemDO();
        bound.setId(10001L);
        bound.setTenantId(100L);
        bound.setVersion(1);
        bound.setSdlcId(8L);
        bound.setCurrentStepId(901L);
        bound.setAssigneeType("AGENT");
        bound.setAssigneeRef(12L);
        bound.setCreatorId(7L);
        bound.setStatusNodeId(300L);
        when(workitemDao.findById(10001L)).thenReturn(inserted, bound);
        when(workitemDao.updateAssignee(eq(10001L), eq(100L), eq("AGENT"), eq(12L), anyInt(), eq(7L)))
                .thenReturn(1);
        when(workitemDao.updateSdlcAndStep(eq(10001L), eq(100L), eq(8L), eq(901L), anyInt(), eq(7L)))
                .thenReturn(1);

        CreateWorkitemRequest req = new CreateWorkitemRequest();
        req.setWorkType("REQ");
        req.setTitle("DB运维");
        req.setAssigneeType("AGENT");
        req.setAssigneeRef(12L);
        req.setSdlcId(8L);

        WorkitemVO vo = service.create(req, 100L, 7L);

        assertEquals("AGENT", vo.getAssigneeType());
        assertEquals(Long.valueOf(12L), vo.getAssigneeRef());
        assertEquals(Long.valueOf(8L), vo.getSdlcId());
        InOrder order = inOrder(workitemDao, eventDao);
        order.verify(workitemDao).insert(argThat((WorkitemDO w) ->
                "HUMAN".equals(w.getAssigneeType()) && w.getAssigneeRef() == 7L));
        order.verify(eventDao).insert(argThat((WorkitemEventDO e) -> "CREATE".equals(e.getEventType())));
        order.verify(workitemDao).updateAssignee(10001L, 100L, "AGENT", 12L, 0, 7L);
        order.verify(eventDao).insert(argThat((WorkitemEventDO e) -> "ASSIGN".equals(e.getEventType())
                && "7".equals(e.getFromVal()) && "12".equals(e.getToVal())));
        order.verify(workitemDao).updateSdlcAndStep(10001L, 100L, 8L, 901L, 1, 7L);
    }

    @Test
    void get_missing_throws() {
        when(workitemDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(9L));
        assertEquals("13003", ex.getCode());
    }

    @Test
    void get_returns_vo() {
        WorkitemDO w = new WorkitemDO();
        w.setId(9L);
        w.setTenantId(100L);
        w.setTitle("t");
        w.setWorkType("REQ");
        when(workitemDao.findById(9L)).thenReturn(w);
        WorkitemVO vo = service.get(9L);
        assertEquals(9L, vo.getId());
        assertEquals("t", vo.getTitle());
        assertEquals("NATIVE", vo.getSourceType());
        assertTrue(vo.getDeletable());
        assertNull(vo.getDeletableReason());
    }

    @Test
    void get_marks_external_workitem_not_deletable() {
        WorkitemDO w = new WorkitemDO();
        w.setId(9L);
        w.setTenantId(100L);
        w.setTitle("t");
        w.setWorkType("REQ");
        when(workitemDao.findById(9L)).thenReturn(w);
        when(externalWorkitemLinkDao.listByWorkitem(100L, 9L)).thenReturn(List.of(new ExternalWorkitemLinkDO()));

        WorkitemVO vo = service.get(9L);

        assertEquals("EXTERNAL", vo.getSourceType());
        assertFalse(vo.getDeletable());
        assertEquals("外部平台集成工单不可删除", vo.getDeletableReason());
    }

    @Test
    void get_marks_running_workitem_not_deletable() {
        WorkitemDO w = new WorkitemDO();
        w.setId(9L);
        w.setTenantId(100L);
        w.setTitle("t");
        w.setWorkType("REQ");
        DispatchDO dispatch = new DispatchDO();
        dispatch.setStatus(DispatchStatus.RUNNING);
        when(workitemDao.findById(9L)).thenReturn(w);
        when(externalWorkitemLinkDao.listByWorkitem(100L, 9L)).thenReturn(List.of());
        when(dispatchDao.listByWorkitem(100L, 9L)).thenReturn(List.of(dispatch));

        WorkitemVO vo = service.get(9L);

        assertEquals("NATIVE", vo.getSourceType());
        assertFalse(vo.getDeletable());
        assertEquals("工单正在执行中，请等待完成或结束后再删除", vo.getDeletableReason());
    }

    @Test
    void list_maps_to_vos_with_page_metadata() {
        WorkitemDO w = new WorkitemDO();
        w.setId(1L);
        when(workitemDao.count(100L, "REQ", null, null, null, false, null, 7L, null, null)).thenReturn(101L);
        when(workitemDao.list(100L, "REQ", null, null, null, false, null, 7L, null, null, 20, 20)).thenReturn(List.of(w));
        PageResult<WorkitemVO> page = service.list("REQ", null, null, null, false, null, 100L, 7L, null, 2, 20);
        assertEquals(101L, page.getTotal());
        assertEquals(2, page.getPageNum());
        assertEquals(20, page.getPageSize());
        assertEquals(1, page.getList().size());
        assertEquals(1L, page.getList().get(0).getId());
    }

    @Test
    void list_marks_human_assigned_successful_delivery_as_pending_decision() {
        WorkitemDO w = new WorkitemDO();
        w.setId(1L);
        w.setTenantId(100L);
        w.setAssigneeType("HUMAN");
        DispatchDO latest = new DispatchDO();
        latest.setWorkitemId(1L);
        latest.setStatus(DispatchStatus.SUCCEEDED);
        when(workitemDao.list(100L, null, null, null, null, false, null, 7L, null, null, 0, 20)).thenReturn(List.of(w));
        when(dispatchDao.listLatestByWorkitemIds(List.of(1L))).thenReturn(List.of(latest));

        PageResult<WorkitemVO> page = service.list(null, null, null, null, false, null, 100L, 7L, null, 1, 20);

        assertEquals(1, page.getList().size());
        assertTrue(page.getList().get(0).getPendingDecision());
    }

    @Test
    void list_keeps_done_workitem_out_of_pending_decision_after_successful_human_handoff() {
        WorkitemDO w = new WorkitemDO();
        w.setId(1L);
        w.setTenantId(100L);
        w.setStatusNodeId(10L);
        w.setAssigneeType("HUMAN");
        StatusNodeDO released = new StatusNodeDO();
        released.setId(10L);
        released.setName("已发布");
        released.setCategory("DONE");
        DispatchDO latest = new DispatchDO();
        latest.setWorkitemId(1L);
        latest.setStatus(DispatchStatus.SUCCEEDED);
when(workitemDao.list(100L, null, null, null, null, false, null, 7L, null, null, 0, 20)).thenReturn(List.of(w));
        when(nodeDao.listByIds(any())).thenReturn(List.of(released));
        when(dispatchDao.listLatestByWorkitemIds(List.of(1L))).thenReturn(List.of(latest));

        PageResult<WorkitemVO> page = service.list(null, null, null, null, false, null, 100L, 7L, null, 1, 20);

        assertEquals(1, page.getList().size());
        assertEquals("已发布", page.getList().get(0).getStatusName());
        assertFalse(page.getList().get(0).getPendingDecision());
    }

    @Test
    void list_does_not_mark_pending_decision_without_successful_human_handoff_signal() {
        WorkitemDO human = new WorkitemDO();
        human.setId(1L);
        human.setTenantId(100L);
        human.setAssigneeType("HUMAN");
        WorkitemDO agent = new WorkitemDO();
        agent.setId(2L);
        agent.setTenantId(100L);
        agent.setAssigneeType("AGENT");
        DispatchDO failed = new DispatchDO();
        failed.setWorkitemId(1L);
        failed.setStatus(DispatchStatus.FAILED);
        DispatchDO succeededForAgent = new DispatchDO();
        succeededForAgent.setWorkitemId(2L);
        succeededForAgent.setStatus(DispatchStatus.SUCCEEDED);
        when(workitemDao.list(100L, null, null, null, null, false, null, 7L, null, null, 0, 20))
                .thenReturn(List.of(human, agent));
        when(dispatchDao.listLatestByWorkitemIds(List.of(1L, 2L)))
                .thenReturn(List.of(failed, succeededForAgent));

        PageResult<WorkitemVO> page = service.list(null, null, null, null, false, null, 100L, 7L, null, 1, 20);

        assertFalse(page.getList().get(0).getPendingDecision());
        assertFalse(page.getList().get(1).getPendingDecision());
    }

    @Test
    void list_passes_filters_and_pending_decision_to_dao() {
        when(workitemDao.list(100L, "REQ", null, "AGENT", 42L, true, null, 7L, null, null, 0, 100))
                .thenReturn(java.util.List.of());

        service.list("REQ", null, "AGENT", 42L, true, null, 100L, 7L, null, 1, 100);

        verify(workitemDao).count(100L, "REQ", null, "AGENT", 42L, true, null, 7L, null, null);
        verify(workitemDao).list(100L, "REQ", null, "AGENT", 42L, true, null, 7L, null, null, 0, 100);
    }

    @Test
    void list_clamps_page_size_to_two_hundred() {
        service.list(null, null, null, null, false, null, 100L, 7L, null, 1, 1000);

        verify(workitemDao).count(100L, null, null, null, null, false, null, 7L, null, null);
        verify(workitemDao).list(100L, null, null, null, null, false, null, 7L, null, null, 0, 200);
    }

    @Test
    void delivery_progress_exposes_pending_dispatch_substeps() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(20L);
        when(workitemDao.findById(100L)).thenReturn(w);

        SdlcStepDO step = new SdlcStepDO();
        step.setId(20L);
        step.setName("编码实现");
        step.setStepOrder(1);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(step)));

        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(30L);
        dispatch.setSdlcStepId(20L);
        dispatch.setAgentId(40L);
        dispatch.setStatus(DispatchStatus.PENDING);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(dispatch));

        AgentDO agent = new AgentDO();
        agent.setId(40L);
        agent.setName("worker");
        when(agentDao.findById(40L)).thenReturn(agent);

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals("active", progress.getSteps().get(0).getStatus());
        assertEquals("worker", progress.getSteps().get(0).getExecutorName());
        assertEquals("启动交付", progress.getSteps().get(0).getSubSteps().get(0).getName());
        assertEquals("done", progress.getSteps().get(0).getSubSteps().get(0).getStatus());
        assertEquals("等待调度执行", progress.getSteps().get(0).getSubSteps().get(1).getName());
        assertEquals("active", progress.getSteps().get(0).getSubSteps().get(1).getStatus());
    }

    @Test
    void delivery_progress_derives_duration_from_dispatch_timestamps() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(20L);
        when(workitemDao.findById(100L)).thenReturn(w);

        SdlcStepDO step = new SdlcStepDO();
        step.setId(20L);
        step.setName("编码实现");
        step.setStepOrder(1);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(step)));

        DispatchDO first = new DispatchDO();
        first.setId(30L);
        first.setSdlcStepId(20L);
        first.setAgentId(40L);
        first.setStatus(DispatchStatus.FAILED);
        first.setGmtCreate(new Date(1_000_000L));
        first.setGmtModified(new Date(1_161_000L)); // 161s

        DispatchDO second = new DispatchDO();
        second.setId(31L);
        second.setSdlcStepId(20L);
        second.setAgentId(40L);
        second.setStatus(DispatchStatus.PENDING);
        second.setGmtCreate(new Date(2_000_000L));
        second.setGmtModified(new Date(2_030_000L)); // 30s
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(first, second));

        AgentDO agent = new AgentDO();
        agent.setId(40L);
        agent.setName("worker");
        when(agentDao.findById(40L)).thenReturn(agent);

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);
        DeliveryStepVO stepVO = progress.getSteps().get(0);

        assertEquals(30_000L, stepVO.getDurationMs());
        assertEquals(2, stepVO.getAttempts().size());
        assertEquals(161_000L, stepVO.getAttempts().get(0).getDurationMs());
        assertEquals("worker", stepVO.getAttempts().get(0).getExecutorName());
        assertEquals(30_000L, stepVO.getAttempts().get(1).getDurationMs());
    }

    @Test
    void delivery_progress_groups_by_agent_and_preserves_handoff_history() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(20L);
        w.setCurrentStepId(202L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(42L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcResolver.resolveSdlcId(7L, 42L)).thenReturn(20L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与建分支"),
                step(102L, 10L, 2, "编码实现")
        )));
        when(sdlcStepDao.listBySdlc(20L)).thenReturn(new ArrayList<>(List.of(
                step(201L, 20L, 1, "Code Review"),
                step(202L, 20L, 2, "修复意见")
        )));

        DispatchDO devDone = dispatch(301L, 101L, 41L, DispatchStatus.SUCCEEDED,
                1_000L, 121_000L, null);
        DispatchDO crFailed = dispatch(302L, 201L, 42L, DispatchStatus.FAILED,
                200_000L, 240_000L, "execute dispatch: load skills: skill name is required.");
        DispatchDO crRunning = dispatch(303L, 202L, 42L, DispatchStatus.RUNNING,
                250_000L, 550_000L, null);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(devDone, crFailed, crRunning));

        when(agentDao.findById(41L)).thenReturn(agent(41L, "Agent Dev"));
        when(agentDao.findById(42L)).thenReturn(agent(42L, "Agent CR"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals(2, progress.getAgents().size());
        AgentDeliveryProgressVO dev = progress.getAgents().get(0);
        assertEquals(41L, dev.getAgentId());
        assertEquals("Agent Dev", dev.getAgentName());
        assertEquals("finished", dev.getStatus());
        assertEquals("done", dev.getSteps().get(0).getStatus());
        assertEquals(120_000L, dev.getSteps().get(0).getDurationMs());

        AgentDeliveryProgressVO cr = progress.getAgents().get(1);
        assertEquals(42L, cr.getAgentId());
        assertEquals("Agent CR", cr.getAgentName());
        assertEquals("active", cr.getStatus());
        assertEquals("failed", cr.getSteps().get(0).getStatus());
        assertEquals("execute dispatch: load skills: skill name is required.", cr.getSteps().get(0).getError());
        assertEquals("active", cr.getSteps().get(1).getStatus());
        assertEquals("Agent CR", cr.getSteps().get(1).getExecutorName());
    }

    @Test
    void delivery_progress_builds_graph_from_authoritative_handoff_lineage() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(201L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(42L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcResolver.resolveSdlcId(7L, 42L)).thenReturn(20L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));
        when(sdlcStepDao.listBySdlc(20L)).thenReturn(new ArrayList<>(List.of(
                step(201L, 20L, 1, "代码评审")
        )));

        DispatchDO dev = dispatch(301L, 101L, 41L, DispatchStatus.SUCCEEDED,
                1_000L, 121_000L, null);
        DispatchDO review = dispatch(302L, 201L, 42L, DispatchStatus.RUNNING,
                130_000L, 160_000L, null);
        review.setIdempotencyKey("handoff:301");
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(dev, review));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "开发"));
        when(agentDao.findById(42L)).thenReturn(agent(42L, "评审"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals(2, progress.getProcessGraph().getNodes().size());
        assertEquals(1, progress.getProcessGraph().getEdges().size());
        assertEquals("HANDOFF", progress.getProcessGraph().getEdges().get(0).getType());
        assertEquals(301L, progress.getProcessGraph().getEdges().get(0).getSourceDispatchId());
        assertEquals(302L, progress.getProcessGraph().getEdges().get(0).getTargetDispatchId());
        assertEquals("交接", progress.getProcessGraph().getEdges().get(0).getLabel());
    }

    @Test
    void delivery_progress_builds_comment_rework_self_loop_and_excludes_side_interaction() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));

        DispatchDO original = dispatch(301L, 101L, 41L, DispatchStatus.PAUSED,
                1_000L, 61_000L, null);
        DispatchDO side = dispatch(302L, 101L, 41L, DispatchStatus.SUCCEEDED,
                70_000L, 80_000L, null);
        side.setResumeMode("SIDE_INTERACTION");
        side.setIdempotencyKey("guidance:900");
        DispatchDO rework = dispatch(303L, 101L, 41L, DispatchStatus.RUNNING,
                90_000L, 120_000L, null);
        rework.setResumeMode("COMMENT_REWORK");
        rework.setIdempotencyKey("interaction-rework:302");
        rework.setResumeFromDispatchId(301L);
        rework.setResultSummary("waitForDispatchId=301");
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(original, side, rework));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "开发"));
        GuidanceDO guidance = new GuidanceDO();
        guidance.setId(900L);
        guidance.setDispatchId(302L);
        guidance.setCommentId(11599L);
        when(guidanceDao.listByWorkitem(7L, 100L)).thenReturn(List.of(guidance));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals(List.of(301L, 303L), progress.getProcessGraph().getNodes().stream()
                .map(node -> node.getDispatchId()).toList());
        assertEquals(11599L, progress.getProcessGraph().getNodes().get(1).getTriggerCommentId());
        var edge = progress.getProcessGraph().getEdges().get(0);
        assertEquals("COMMENT_REWORK", edge.getType());
        assertEquals("dispatch:301", edge.getSourceKey());
        assertEquals("dispatch:303", edge.getTargetKey());
        assertEquals(11599L, edge.getCommentId());
        assertEquals("用户返工（评论 #11599）", edge.getLabel());
    }

    @Test
    void delivery_progress_uses_resume_lineage_without_inventing_edges_for_isolated_history() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));

        DispatchDO isolated = dispatch(300L, 101L, 41L, DispatchStatus.SUCCEEDED,
                1_000L, 31_000L, null);
        DispatchDO paused = dispatch(301L, 101L, 41L, DispatchStatus.PAUSED,
                40_000L, 60_000L, null);
        DispatchDO resumed = dispatch(302L, 101L, 41L, DispatchStatus.RUNNING,
                70_000L, 90_000L, null);
        resumed.setResumeFromDispatchId(301L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(isolated, paused, resumed));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals(3, progress.getProcessGraph().getNodes().size());
        assertEquals(1, progress.getProcessGraph().getEdges().size());
        assertEquals("CONTINUE", progress.getProcessGraph().getEdges().get(0).getType());
        assertEquals(301L, progress.getProcessGraph().getEdges().get(0).getSourceDispatchId());
        assertEquals(302L, progress.getProcessGraph().getEdges().get(0).getTargetDispatchId());
    }

    @Test
    void delivery_progress_marks_agent_internal_sdlc_done_when_dispatch_succeeds() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与建分支"),
                step(102L, 10L, 2, "编码实现"),
                step(103L, 10L, 3, "自测"),
                step(104L, 10L, 4, "推送分支并交接")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.SUCCEEDED, 1_000L, 121_000L, null)
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AutoWonder前后端1号开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        AgentDeliveryProgressVO agent = progress.getAgents().get(0);
        assertEquals("finished", agent.getStatus());
        assertEquals(List.of("done", "done", "done", "done"),
                agent.getSteps().stream().map(DeliveryStepVO::getStatus).toList());
    }

    @Test
    void delivery_progress_keeps_runtime_step_details_after_single_dispatch_succeeds() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与评论"),
                step(102L, 10L, 2, "编码实现"),
                step(103L, 10L, 3, "自测")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.SUCCEEDED, 1_000L, 301_000L, null)
        ));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 301L, 41L, "step.started", 1, "需求分析与评论", 1_000L, "读取工单"),
                runtimeEvent(2L, 301L, 41L, "step.completed", 1, "需求分析与评论", 61_000L, "发布分析评论"),
                runtimeEvent(3L, 301L, 41L, "step.started", 2, "编码实现", 62_000L, "开始编码"),
                runtimeEvent(4L, 301L, 41L, "agent.progress", 2, "编码实现", 120_000L, "提交修复代码"),
                runtimeEvent(5L, 301L, 41L, "step.completed", 2, "编码实现", 181_000L, "编码完成"),
                runtimeEvent(6L, 301L, 41L, "step.started", 3, "自测", 182_000L, "运行测试"),
                runtimeEvent(7L, 301L, 41L, "step.completed", 3, "自测", 240_000L, "测试通过")
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        AgentDeliveryProgressVO agent = progress.getAgents().get(0);
        assertEquals("finished", agent.getStatus());
        assertEquals(List.of("done", "done", "done"),
                agent.getSteps().stream().map(DeliveryStepVO::getStatus).toList());
        DeliveryStepVO coding = agent.getSteps().get(1);
        assertEquals("AW全栈开发", coding.getExecutorName());
        assertNotNull(coding.getSubSteps());
        assertTrue(coding.getSubSteps().stream().anyMatch(sub -> "提交修复代码".equals(sub.getName())));
        assertTrue(coding.getSubSteps().stream().allMatch(sub -> "done".equals(sub.getStatus())));
    }

    @Test
    void delivery_progress_uses_runtime_step_events_within_single_agent_dispatch() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与建分支"),
                step(102L, 10L, 2, "编码实现"),
                step(103L, 10L, 3, "自测"),
                step(104L, 10L, 4, "推送分支并交接")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.RUNNING, 1_000L, 301_000L, null)
        ));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 301L, 41L, "step.started", 1, "需求分析与建分支", 1_000L, null),
                runtimeEvent(2L, 301L, 41L, "step.completed", 1, "需求分析与建分支", 61_000L, null),
                runtimeEvent(3L, 301L, 41L, "step.started", 2, "编码实现", 62_000L, null),
                runtimeEvent(4L, 301L, 41L, "step.completed", 2, "编码实现", 181_000L, null),
                runtimeEvent(5L, 301L, 41L, "step.started", 3, "自测", 182_000L, null),
                runtimeEvent(6L, 301L, 41L, "agent.progress", 3, "自测", 240_000L, "运行测试")
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AutoWonder前后端1号开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        AgentDeliveryProgressVO agent = progress.getAgents().get(0);
        assertEquals("active", agent.getStatus());
        assertEquals("运行测试", agent.getCurrentActivity());
        assertEquals(List.of("done", "done", "active", "pending"),
                agent.getSteps().stream().map(DeliveryStepVO::getStatus).toList());
        assertTrue(agent.getSteps().get(2).getSubSteps().stream()
                .anyMatch(sub -> "运行测试".equals(sub.getName())));
        assertEquals("active", agent.getSteps().get(2).getSubSteps().get(
                agent.getSteps().get(2).getSubSteps().size() - 1).getStatus());
    }

    @Test
    void delivery_progress_hides_mojibake_runtime_messages_and_labels_gate_events() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "分析与分支准备")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.RUNNING, 1_000L, 301_000L, null)
        ));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 301L, 41L, "step.started", 1, "分析与分支准备",
                        1_000L, "�Ķ�������ǰ��������׼�������ͷ�֧��"),
                runtimeEvent(2L, 301L, 41L, "step.completion_requested", 1, "分析与分支准备",
                        2_000L, null),
                runtimeEvent(3L, 301L, 41L, "step.gate_started", 1, "分析与分支准备",
                        3_000L, null),
                runtimeEvent(4L, 301L, 41L, "step.gate_finished", 1, "分析与分支准备",
                        4_000L, null),
                runtimeEvent(5L, 301L, 41L, "agent.progress", 1, "分析与分支准备",
                        5_000L, "正常进度")
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        var subStepNames = progress.getAgents().get(0).getSteps().get(0).getSubSteps().stream()
                .map(com.aliyun.autowonder.workitem.dto.SubStepVO::getName)
                .toList();
        assertEquals(List.of("开始执行", "请求完成", "开始校验", "校验完成", "正常进度"), subStepNames);
        assertTrue(subStepNames.stream().noneMatch(name -> name.contains("�") || name.startsWith("step.")));
    }

    @Test
    void delivery_progress_marks_rerun_worker_active_even_when_not_current_assignee() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(102L);
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(9L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与评论"),
                step(102L, 10L, 2, "编码实现")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 102L, 41L, DispatchStatus.RUNNING, 1_000L, 61_000L, null)
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        AgentDeliveryProgressVO agent = progress.getAgents().get(0);
        assertEquals("AW全栈开发", agent.getAgentName());
        assertEquals("active", agent.getStatus());
        assertEquals("active", agent.getSteps().get(1).getStatus());
    }

    @Test
    void delivery_progress_exposes_latest_workflow_plan_without_overwriting_execution_status() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(102L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);

        SdlcStepDO analysis = step(101L, 10L, 1, "需求分析");
        analysis.setCode("analysis");
        SdlcStepDO coding = step(102L, 10L, 2, "编码实现");
        coding.setCode("coding");
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(analysis, coding)));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 102L, 41L, DispatchStatus.RUNNING, 1_000L, 30_000L, null)
        ));
        DispatchRuntimeEventDO oldPlan = runtimeEvent(1L, 301L, 41L,
                "workflow.plan_applied", null, null, 1_000L, null);
        oldPlan.setDetailJson("{\"revision\":1,\"targetStepId\":\"analysis\"," +
                "\"steps\":[{\"stepKey\":\"analysis\",\"name\":\"需求分析\",\"planStatus\":\"RUN\"}]}");
        DispatchRuntimeEventDO latestPlan = runtimeEvent(2L, 301L, 41L,
                "workflow.plan_applied", null, null, 2_000L, null);
        latestPlan.setDetailJson("{\"revision\":2,\"targetStepId\":\"coding\",\"reason\":\"实现方式变化\"," +
                "\"sourceGuidanceIds\":[184],\"steps\":[" +
                "{\"stepKey\":\"analysis\",\"name\":\"需求分析\",\"planStatus\":\"REUSED\",\"sourceAttempt\":1}," +
                "{\"stepKey\":\"coding\",\"name\":\"编码实现\",\"planStatus\":\"RUN\"}]}");
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(oldPlan, latestPlan,
                runtimeEvent(3L, 301L, 41L, "step.started", 2, "编码实现", 3_000L, null)));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "开发 Dev"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertNotNull(progress.getWorkflowPlan());
        assertEquals(2, progress.getWorkflowPlan().getRevision());
        assertEquals("实现方式变化", progress.getWorkflowPlan().getReason());
        assertEquals(List.of(184L), progress.getWorkflowPlan().getSourceGuidanceIds());
        assertEquals(List.of("REUSED", "RUN"), progress.getWorkflowPlan().getSteps().stream()
                .map(com.aliyun.autowonder.workitem.dto.WorkflowPlanStepVO::getPlanStatus).toList());
        AgentDeliveryProgressVO agent = progress.getAgents().get(0);
        assertEquals("REUSED", agent.getSteps().get(0).getPlanStatus());
        assertEquals(Integer.valueOf(1), agent.getSteps().get(0).getSourceAttempt());
        assertEquals("RUN", agent.getSteps().get(1).getPlanStatus());
        assertEquals("active", agent.getSteps().get(1).getStatus());
    }

    @Test
    void delivery_progress_marks_agent_internal_sdlc_done_after_retry_success() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);

        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与建分支"),
                step(102L, 10L, 2, "编码实现")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.FAILED, 1_000L, 61_000L, "transient"),
                dispatch(302L, 101L, 41L, DispatchStatus.SUCCEEDED, 70_000L, 121_000L, null)
        ));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 301L, 41L, "step.started", 1, "需求分析与建分支", 1_000L, "旧 attempt 开始"),
                runtimeEvent(2L, 301L, 41L, "step.failed", 1, "需求分析与建分支", 61_000L, "旧 attempt 失败"),
                runtimeEvent(3L, 302L, 41L, "step.started", 1, "需求分析与建分支", 70_000L, "重试开始"),
                runtimeEvent(4L, 302L, 41L, "step.completed", 1, "需求分析与建分支", 91_000L, "重试分析完成"),
                runtimeEvent(5L, 302L, 41L, "step.started", 2, "编码实现", 92_000L, "重试编码开始"),
                runtimeEvent(6L, 302L, 41L, "step.completed", 2, "编码实现", 121_000L, "重试编码完成")
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AutoWonder前后端1号开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        AgentDeliveryProgressVO agent = progress.getAgents().get(0);
        assertEquals("finished", agent.getStatus());
        assertEquals(List.of("done", "done"),
                agent.getSteps().stream().map(DeliveryStepVO::getStatus).toList());
        assertEquals(2, agent.getSteps().get(0).getAttempts().size());
        assertTrue(agent.getSteps().get(0).getSubSteps().stream()
                .noneMatch(sub -> "旧 attempt 失败".equals(sub.getName()) || "failed".equals(sub.getStatus())));
        assertTrue(agent.getSteps().get(1).getSubSteps().stream()
                .anyMatch(sub -> "重试编码完成".equals(sub.getName())));
        assertTrue(agent.getSteps().get(1).getSubSteps().stream()
                .allMatch(sub -> "done".equals(sub.getStatus())));
    }

    @Test
    void delivery_progress_uses_latest_running_attempt_instead_of_previous_failure() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与评论"),
                step(102L, 10L, 2, "编码实现")
        )));
        DispatchDO latest = dispatch(302L, 101L, 41L, DispatchStatus.RUNNING,
                70_000L, 90_000L, null);
        latest.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.FAILED, 1_000L, 61_000L, "transient"),
                latest
        ));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 301L, 41L, "step.started", 1, "需求分析与评论", 1_000L, "旧 attempt 开始"),
                runtimeEvent(2L, 301L, 41L, "step.failed", 1, "需求分析与评论", 61_000L, "旧 attempt 失败"),
                runtimeEvent(3L, 302L, 41L, "step.started", 1, "需求分析与评论", 70_000L, "本轮重跑")
        ));
        when(presenceManager.isExecutorOnline(51L)).thenReturn(true);
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        AgentDeliveryProgressVO agent = service.getDeliveryProgress(100L, 7L).getAgents().get(0);

        assertEquals("active", agent.getStatus());
        assertEquals("active", agent.getSteps().get(0).getStatus());
        assertTrue(agent.getSteps().get(0).getSubSteps().stream()
                .noneMatch(sub -> "旧 attempt 失败".equals(sub.getName()) || "failed".equals(sub.getStatus())));
        var attempts = agent.getSteps().get(0).getAttempts();
        assertFalse(attempts.get(0).getCanPause());
        assertTrue(attempts.get(1).getCanPause());
        assertFalse(attempts.get(1).getCanContinue());
    }

    @Test
    void delivery_progress_keeps_requeued_pending_attempt_authoritative_after_runtime_failed_event() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与评论"),
                step(102L, 10L, 2, "编码实现")
        )));
        DispatchDO pending = dispatch(302L, 101L, 41L, DispatchStatus.PENDING,
                70_000L, 90_000L, null);
        pending.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(pending));
        DispatchRuntimeEventDO failover = runtimeEvent(3L, 302L, 41L,
                "dispatch.executor_failover", 1, "需求分析与评论", 90_000L,
                "Runtime 51 失败，正在切换其他 Runtime");
        failover.setError("Runtime 51 · agent_error.provider_quota_limit · quota exhausted");
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 302L, 41L, "step.started", 1, "需求分析与评论", 70_000L, "开始"),
                runtimeEvent(2L, 302L, 41L, "agent.message", 1, "需求分析与评论", 80_000L, "准备上下文"),
                failover
        ));
        when(presenceManager.isExecutorOnline(51L)).thenReturn(true);
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        AgentDeliveryProgressVO agent = service.getDeliveryProgress(100L, 7L).getAgents().get(0);

        assertEquals("active", agent.getStatus());
        assertEquals("active", agent.getSteps().get(0).getStatus());
        DispatchAttemptVO attempt = agent.getSteps().get(0).getAttempts().get(0);
        assertEquals(DispatchStatus.PENDING, attempt.getStatus());
        assertEquals("Runtime 51 · agent_error.provider_quota_limit · quota exhausted", attempt.getError());
        assertFalse(attempt.getCanPause());
        assertFalse(attempt.getCanContinue());
    }

    @Test
    void delivery_progress_marks_stuck_running_attempt_failed_when_latest_runtime_event_failed() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与评论")
        )));
        DispatchDO running = dispatch(302L, 101L, 41L, DispatchStatus.RUNNING,
                70_000L, 90_000L, null);
        running.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(running));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 302L, 41L, "step.started", 1, "需求分析与评论", 70_000L, "开始"),
                runtimeEvent(2L, 302L, 41L, "step.failed", 1, "需求分析与评论", 90_000L, "missing completion request")
        ));
        when(presenceManager.isExecutorOnline(51L)).thenReturn(true);
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        AgentDeliveryProgressVO agent = service.getDeliveryProgress(100L, 7L).getAgents().get(0);

        assertEquals("failed", agent.getStatus());
        assertEquals("failed", agent.getSteps().get(0).getStatus());
        DispatchAttemptVO attempt = agent.getSteps().get(0).getAttempts().get(0);
        assertEquals(DispatchStatus.FAILED, attempt.getStatus());
        assertEquals("missing completion request", attempt.getError());
        assertFalse(attempt.getCanPause());
    }

    @Test
    void delivery_progress_uses_latest_started_event_after_same_dispatch_restarts() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "获取上下文与评论"),
                step(102L, 10L, 2, "测试验证")
        )));
        DispatchDO running = dispatch(302L, 101L, 41L, DispatchStatus.RUNNING,
                70_000L, 90_000L, null);
        running.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(running));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(1L, 302L, 41L, "step.started", 1, "获取上下文与评论", 70_000L, "第一次启动"),
                runtimeEvent(2L, 302L, 41L, "step.failed", 1, "获取上下文与评论", 75_000L, "旧执行失败"),
                runtimeEvent(3L, 302L, 41L, "step.started", 1, "获取上下文与评论", 80_000L, "恢复后重新启动")
        ));
        when(presenceManager.isExecutorOnline(51L)).thenReturn(true);
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW测试工程师"));

        AgentDeliveryProgressVO agent = service.getDeliveryProgress(100L, 7L).getAgents().get(0);

        assertEquals("active", agent.getStatus());
        assertEquals("active", agent.getSteps().get(0).getStatus());
        var subSteps = agent.getSteps().get(0).getSubSteps();
        assertEquals("恢复后重新启动", subSteps.get(subSteps.size() - 1).getName());
        assertEquals("active", subSteps.get(subSteps.size() - 1).getStatus());
    }

    @Test
    void delivery_progress_prefers_latest_paused_dispatch_over_started_runtime_event() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "需求分析与评论"),
                step(102L, 10L, 2, "编码实现")
        )));
        DispatchDO paused = dispatch(302L, 101L, 41L, DispatchStatus.PAUSED,
                70_000L, 90_000L, null);
        paused.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(paused));
        when(runtimeEventDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                runtimeEvent(3L, 302L, 41L, "step.started", 2, "编码实现", 70_000L, "正在编码")
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AW全栈开发"));

        AgentDeliveryProgressVO agent = service.getDeliveryProgress(100L, 7L).getAgents().get(0);

        assertEquals("paused", agent.getStatus());
        assertEquals("paused", agent.getSteps().get(1).getStatus());
        var attempt = agent.getSteps().get(0).getAttempts().get(0);
        assertFalse(attempt.getCanPause());
        assertTrue(attempt.getCanContinue());

        paused.setStatus(DispatchStatus.FAILED);
        paused.setError("runtime failed after last progress event");
        agent = service.getDeliveryProgress(100L, 7L).getAgents().get(0);

        assertEquals("failed", agent.getStatus());
        assertEquals("failed", agent.getSteps().get(1).getStatus());
        attempt = agent.getSteps().get(0).getAttempts().get(0);
        assertFalse(attempt.getCanPause());
        assertTrue(attempt.getCanContinue());
    }

    @Test
    void delivery_progress_offers_continue_only_for_latest_failed_worker_attempt() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.FAILED, 1_000L, 61_000L, "quota"),
                dispatch(302L, 101L, 41L, DispatchStatus.FAILED, 70_000L, 121_000L, "quota")
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "AutoWonder前后端1号开发"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        List<com.aliyun.autowonder.workitem.dto.DispatchAttemptVO> attempts = progress.getAgents()
                .get(0).getSteps().get(0).getAttempts();
        assertFalse(attempts.get(0).getCanContinue());
        assertTrue(attempts.get(1).getCanContinue());
    }

    @Test
    void delivery_progress_exposes_dispatch_resume_mode_on_attempts() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));
        DispatchDO interaction = dispatch(303L, 101L, 41L, DispatchStatus.RUNNING,
                1_000L, 61_000L, null);
        interaction.setAgentId(null);
        interaction.setResumeMode("SIDE_INTERACTION");
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(interaction));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals("SIDE_INTERACTION", progress.getSteps().get(0)
                .getAttempts().get(0).getResumeMode());
    }

    @Test
    void delivery_progress_compat_steps_prefer_formal_active_sdlc_over_interaction_active_worker() {
        assertFormalStepWinsOverInteractionActiveWorker(DispatchStatus.RUNNING, "active");
    }

    @Test
    void delivery_progress_compat_steps_prefer_formal_paused_sdlc_over_interaction_active_worker() {
        assertFormalStepWinsOverInteractionActiveWorker(DispatchStatus.PAUSED, "paused");
    }

    @Test
    void delivery_progress_compat_steps_prefer_formal_failed_sdlc_over_interaction_active_worker() {
        assertFormalStepWinsOverInteractionActiveWorker(DispatchStatus.FAILED, "failed");
    }

    private void assertFormalStepWinsOverInteractionActiveWorker(String formalStatus, String expectedStepStatus) {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcResolver.resolveSdlcId(7L, 42L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));
        DispatchDO interaction = dispatch(303L, 101L, 42L, DispatchStatus.RUNNING,
                70_000L, 121_000L, null);
        interaction.setResumeMode("SIDE_INTERACTION");
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                interaction,
                dispatch(302L, 101L, 41L, formalStatus, 1_000L, 61_000L, null)
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "Formal Worker"));
        when(agentDao.findById(42L)).thenReturn(agent(42L, "Mentioned Worker"));

        DeliveryProgressVO progress = service.getDeliveryProgress(100L, 7L);

        assertEquals("Formal Worker", progress.getSteps().get(0).getExecutorName());
        assertEquals(expectedStepStatus, progress.getSteps().get(0).getStatus());
    }

    @Test
    void delivery_progress_keeps_retry_pause_available_while_online() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));
        DispatchDO pausing = dispatch(301L, 101L, 41L, DispatchStatus.PAUSING,
                1_000L, 61_000L, null);
        pausing.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(pausing));
        when(presenceManager.isExecutorOnline(51L)).thenReturn(true);
        when(agentDao.findById(41L)).thenReturn(agent(41L, "worker"));

        var attempt = service.getDeliveryProgress(100L, 7L).getAgents()
                .get(0).getSteps().get(0).getAttempts().get(0);

        assertTrue(attempt.getCanPause());
        assertFalse(attempt.getCanContinue());

        pausing.setStatus(DispatchStatus.PAUSE_FAILED);
        attempt = service.getDeliveryProgress(100L, 7L).getAgents()
                .get(0).getSteps().get(0).getAttempts().get(0);
        assertTrue(attempt.getCanPause());
        assertFalse(attempt.getCanContinue());
    }

    @Test
    void delivery_progress_offers_continue_when_pause_is_stuck_and_executor_is_offline() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(10L);
        w.setCurrentStepId(101L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(41L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcResolver.resolveSdlcId(7L, 41L)).thenReturn(10L);
        when(sdlcStepDao.listBySdlc(10L)).thenReturn(new ArrayList<>(List.of(
                step(101L, 10L, 1, "编码实现")
        )));
        DispatchDO pausing = dispatch(301L, 101L, 41L, DispatchStatus.PAUSING,
                1_000L, 61_000L, null);
        pausing.setExecutorId(51L);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(pausing));
        when(presenceManager.isExecutorOnline(51L)).thenReturn(false);
        when(agentDao.findById(41L)).thenReturn(agent(41L, "worker"));

        var attempt = service.getDeliveryProgress(100L, 7L).getAgents()
                .get(0).getSteps().get(0).getAttempts().get(0);

        assertFalse(attempt.getCanPause());
        assertTrue(attempt.getCanContinue());

        pausing.setStatus(DispatchStatus.PAUSE_FAILED);
        attempt = service.getDeliveryProgress(100L, 7L).getAgents()
                .get(0).getSteps().get(0).getAttempts().get(0);
        assertFalse(attempt.getCanPause());
        assertTrue(attempt.getCanContinue());
    }

    @Test
    void participants_include_full_squad_members_and_executor_online_state() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(20L);
        w.setCurrentStepId(202L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(42L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(sdlcStepDao.listBySdlc(20L)).thenReturn(new ArrayList<>(List.of(step(202L, 20L, 1, "修复意见"))));
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(301L, 101L, 41L, DispatchStatus.SUCCEEDED, 1_000L, 121_000L, null),
                dispatch(302L, 202L, 42L, DispatchStatus.RUNNING, 200_000L, 240_000L, null)
        ));

        SquadMemberDO linked = squadMember(501L, 900L, 42L);
        when(squadMemberDao.listByAgent(42L)).thenReturn(List.of(linked));
        when(squadMemberDao.listByAgent(41L)).thenReturn(List.of(linked));
        when(squadMemberDao.listBySquad(900L)).thenReturn(List.of(
                squadMember(501L, 900L, 41L),
                squadMember(502L, 900L, 42L),
                squadMember(503L, 900L, 43L)
        ));
        when(agentDao.findById(41L)).thenReturn(agent(41L, "Agent Dev"));
        when(agentDao.findById(42L)).thenReturn(agent(42L, "Agent CR"));
        when(agentDao.findById(43L)).thenReturn(agent(43L, "Agent Testing"));
        when(executorDao.listByAgent(7L, 41L)).thenReturn(List.of(executor(701L, 41L, "OFFLINE")));
        when(executorDao.listByAgent(7L, 42L)).thenReturn(List.of(executor(702L, 42L, "OFFLINE")));
        when(executorDao.listByAgent(7L, 43L)).thenReturn(List.of(executor(703L, 43L, "BUSY")));
        when(presenceManager.isExecutorOnline(701L)).thenReturn(false);
        when(presenceManager.isExecutorOnline(702L)).thenReturn(true);
        when(presenceManager.isExecutorOnline(703L)).thenReturn(true);

        List<ParticipantVO> participants = service.getParticipants(100L, 7L);

        assertEquals(3, participants.size());
        assertEquals("Agent Dev", participants.get(0).getName());
        assertEquals("41", participants.get(0).getDisplayId());
        assertFalse(participants.get(0).isOnline());
        assertEquals("OFFLINE", participants.get(0).getExecutorStatus());
        assertEquals("Agent CR", participants.get(1).getName());
        assertTrue(participants.get(1).isOnline());
        assertEquals("ONLINE", participants.get(1).getExecutorStatus());
        assertEquals("Agent Testing", participants.get(2).getName());
        assertTrue(participants.get(2).isOnline());
        assertEquals("BUSY", participants.get(2).getExecutorStatus());
    }

    @Test
    void mention_candidates_include_tenant_agents_outside_current_participants() {
        WorkitemDO w = new WorkitemDO();
        w.setId(100L);
        w.setTenantId(7L);
        w.setSdlcId(20L);
        w.setCurrentStepId(202L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(42L);
        when(workitemDao.findById(100L)).thenReturn(w);
        when(dispatchDao.listByWorkitem(7L, 100L)).thenReturn(List.of(
                dispatch(302L, 202L, 42L, DispatchStatus.RUNNING, 200_000L, 240_000L, null)
        ));

        SquadMemberDO linked = squadMember(501L, 900L, 42L);
        when(squadMemberDao.listByAgent(42L)).thenReturn(List.of(linked));
        when(squadMemberDao.listBySquad(900L)).thenReturn(List.of(
                squadMember(502L, 900L, 42L),
                squadMember(503L, 900L, 43L)
        ));
        AgentDO agentCr = agent(42L, "Agent CR");
        agentCr.setOnlineVersionId(401L);
        AgentDO conflictResolver = agent(44L, "AW代码冲突解决工程师");
        conflictResolver.setOnlineVersionId(404L);
        AgentDO draftAgent = agent(45L, "AW未发布员工");
        when(agentDao.listByTenant(7L)).thenReturn(List.of(agentCr, conflictResolver, draftAgent));
        when(agentDao.findById(42L)).thenReturn(agent(42L, "Agent CR"));
        when(agentDao.findById(43L)).thenReturn(agent(43L, "Agent Testing"));
        when(agentDao.findById(44L)).thenReturn(agent(44L, "AW代码冲突解决工程师"));

        List<ParticipantVO> candidates = service.getMentionCandidates(100L, 7L);

        assertEquals(List.of("Agent CR", "Agent Testing", "AW代码冲突解决工程师"),
                candidates.stream().map(ParticipantVO::getName).toList());
    }

    private SdlcStepDO step(long id, long sdlcId, int order, String name) {
        SdlcStepDO step = new SdlcStepDO();
        step.setId(id);
        step.setSdlcId(sdlcId);
        step.setStepOrder(order);
        step.setName(name);
        step.setHandlerType("AGENT");
        return step;
    }

    private DispatchDO dispatch(long id, long stepId, long agentId, String status,
                                long createMs, long modifiedMs, String error) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(7L);
        dispatch.setWorkitemId(100L);
        dispatch.setSdlcStepId(stepId);
        dispatch.setAgentId(agentId);
        dispatch.setStatus(status);
        dispatch.setGmtCreate(new Date(createMs));
        dispatch.setGmtModified(new Date(modifiedMs));
        dispatch.setError(error);
        return dispatch;
    }

    private DispatchRuntimeEventDO runtimeEvent(long id, long dispatchId, long agentId, String eventType,
                                                Integer stepOrder, String stepName, long createMs, String message) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setId(id);
        event.setTenantId(7L);
        event.setWorkitemId(100L);
        event.setDispatchId(dispatchId);
        event.setAgentId(agentId);
        event.setEventType(eventType);
        event.setStepOrder(stepOrder);
        event.setStepName(stepName);
        event.setMessage(message);
        event.setGmtCreate(new Date(createMs));
        return event;
    }

    private AgentDO agent(long id, String name) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(7L);
        agent.setName(name);
        return agent;
    }

    private SquadMemberDO squadMember(long id, long squadId, long agentId) {
        SquadMemberDO member = new SquadMemberDO();
        member.setId(id);
        member.setTenantId(7L);
        member.setSquadId(squadId);
        member.setAgentId(agentId);
        return member;
    }

    private ExecutorDO executor(long id, long agentId, String status) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        executor.setTenantId(7L);
        executor.setAgentId(agentId);
        executor.setStatus(status);
        return executor;
    }

    private WorkitemDO assignableWorkitem() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(100L);
        w.setVersion(3);
        w.setSdlcId(30003L);        // non-null so assign() skips SDLC binding
        w.setCurrentStepId(300031L);
        return w;
    }

    @Test
    void realUserAssignSetsAssignOperator() {
        when(workitemDao.findById(500L)).thenReturn(assignableWorkitem());
        when(workitemDao.updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong()))
                .thenReturn(1);

        service.assign(500L, "AGENT", 10002L, 30003L, null, 100L, 42L);

        verify(workitemDao).updateAssignOperator(eq(500L), eq(100L), eq(42L), anyInt(), eq(42L));
    }

    @Test
    void systemAssignDoesNotSetAssignOperator() {
        when(workitemDao.findById(500L)).thenReturn(assignableWorkitem());
        when(workitemDao.updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong()))
                .thenReturn(1);

        service.assign(500L, "AGENT", 10002L, 30003L, null, 100L, 0L);

        verify(workitemDao, never()).updateAssignOperator(anyLong(), anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void interactionReworkAtomicallyRebindsSdlcStepAndOwnerWithoutAssignmentDispatchEvent() {
        WorkitemDO reviewerOwned = assignableWorkitem();
        reviewerOwned.setAssigneeType("AGENT");
        reviewerOwned.setAssigneeRef(40014L);
        WorkitemDO stepRebound = assignableWorkitem();
        stepRebound.setVersion(4);
        stepRebound.setSdlcId(810L);
        stepRebound.setCurrentStepId(812L);
        stepRebound.setAssigneeType("AGENT");
        stepRebound.setAssigneeRef(40014L);
        when(workitemDao.findById(500L)).thenReturn(reviewerOwned, stepRebound);
        when(workitemDao.updateSdlcAndStep(500L, 100L, 810L, 812L, 3, 0L)).thenReturn(1);
        when(workitemDao.updateAssignee(500L, 100L, "AGENT", 40013L, 4, 0L)).thenReturn(1);

        service.rebindForInteractionRework(100L, 500L, 40013L, 810L, 812L, 0L);

        InOrder order = inOrder(workitemDao, eventDao);
        order.verify(workitemDao).updateSdlcAndStep(500L, 100L, 810L, 812L, 3, 0L);
        order.verify(workitemDao).updateAssignee(500L, 100L, "AGENT", 40013L, 4, 0L);
        order.verify(eventDao).insert(argThat((WorkitemEventDO event) -> "ASSIGN".equals(event.getEventType())
                && "40014".equals(event.getFromVal()) && "40013".equals(event.getToVal())
                && "SYSTEM".equals(event.getActorType())));
    }

    @Test
    void assigningDifferentHumanPublishesActorAwareHumanNotificationWithEventIdAndRequestId() {
        org.slf4j.MDC.put("requestId", "rid-mdc");
        com.aliyun.autowonder.context.AutoWonderContext.get().setRequestId("rid-context");
        WorkitemDO before = assignableWorkitem();
        before.setTitle("修复线上缺陷");
        before.setAssigneeType("AGENT");
        before.setAssigneeRef(40014L);
        WorkitemDO after = assignableWorkitem();
        after.setTitle("修复线上缺陷");
        after.setVersion(4);
        after.setAssigneeType("HUMAN");
        after.setAssigneeRef(99L);
        when(workitemDao.findById(500L)).thenReturn(before, after);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 99L, 3, 7L)).thenReturn(1);
        doAnswer(inv -> {
            WorkitemEventDO event = inv.getArgument(0);
            event.setId(9001L);
            return null;
        }).when(eventDao).insert(any());

        service.assignAs(500L, "HUMAN", 99L, null, null, 100L, 7L,
                AssignmentActor.agent(40014L, "AW开发数字人"));

        assertEquals("rid-mdc", MDC.get("requestId"));
        ArgumentCaptor<WorkitemEventDO> assignEvent = ArgumentCaptor.forClass(WorkitemEventDO.class);
        verify(eventDao).insert(assignEvent.capture());
        assertEquals("ASSIGN", assignEvent.getValue().getEventType());
        assertEquals("AGENT", assignEvent.getValue().getActorType());
        assertEquals(Long.valueOf(40014L), assignEvent.getValue().getActorRef());
        ArgumentCaptor<WorkitemHumanAssignedEvent> published =
                ArgumentCaptor.forClass(WorkitemHumanAssignedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        WorkitemHumanAssignedEvent event = published.getValue();
        assertEquals(100L, event.tenantId());
        assertEquals(500L, event.workitemId());
        assertEquals("修复线上缺陷", event.workitemTitle());
        assertEquals(9001L, event.workitemEventId());
        assertEquals(99L, event.recipientUserId());
        assertEquals("AGENT", event.actorType());
        assertEquals(40014L, event.actorRef());
        assertEquals("AW开发数字人", event.actorDisplayName());
        assertEquals("rid-mdc", event.requestId());
        com.aliyun.autowonder.context.AutoWonderContext.destroy();
        MDC.clear();
    }

    @Test
    void repeatedHumanAssignmentDoesNotUpdateOrPublishNotification() {
        WorkitemDO before = assignableWorkitem();
        before.setAssigneeType("HUMAN");
        before.setAssigneeRef(99L);
        when(workitemDao.findById(500L)).thenReturn(before);

        service.assignAs(500L, "HUMAN", 99L, null, null, 100L, 7L,
                AssignmentActor.human(7L, "张三"));

        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
        verify(eventDao, never()).insert(any());
        verify(eventPublisher, never()).publishEvent(isA(WorkitemHumanAssignedEvent.class));
    }

    @Test
    void sameAssigneeFromDifferentTenantIsRejectedBeforeNoOpReturn() {
        WorkitemDO foreign = assignableWorkitem();
        foreign.setTenantId(200L);
        foreign.setAssigneeType("HUMAN");
        foreign.setAssigneeRef(99L);
        when(workitemDao.findById(500L)).thenReturn(foreign);

        BizException ex = assertThrows(BizException.class, () ->
                service.assignAs(500L, "HUMAN", 99L, null, null, 100L, 7L,
                        AssignmentActor.human(7L, "张三")));

        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), ex.getCode());
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
        verify(eventDao, never()).insert(any());
        verify(eventPublisher, never()).publishEvent(isA(WorkitemHumanAssignedEvent.class));
    }

    @Test
    void selfAssignmentAndAgentAssignmentDoNotPublishHumanNotification() {
        WorkitemDO selfBefore = assignableWorkitem();
        selfBefore.setAssigneeType("AGENT");
        selfBefore.setAssigneeRef(40014L);
        WorkitemDO selfAfter = assignableWorkitem();
        selfAfter.setVersion(4);
        selfAfter.setAssigneeType("HUMAN");
        selfAfter.setAssigneeRef(7L);
        when(workitemDao.findById(500L)).thenReturn(selfBefore, selfAfter);
        when(workitemDao.updateAssignee(500L, 100L, "HUMAN", 7L, 3, 7L)).thenReturn(1);

        service.assignAs(500L, "HUMAN", 7L, null, null, 100L, 7L,
                AssignmentActor.human(7L, "张三"));

        verify(eventPublisher, never()).publishEvent(isA(WorkitemHumanAssignedEvent.class));
        reset(workitemDao, eventDao, eventPublisher);

        WorkitemDO agentBefore = assignableWorkitem();
        agentBefore.setAssigneeType("HUMAN");
        agentBefore.setAssigneeRef(7L);
        WorkitemDO agentAfter = assignableWorkitem();
        agentAfter.setVersion(4);
        agentAfter.setAssigneeType("AGENT");
        agentAfter.setAssigneeRef(40014L);
        when(workitemDao.findById(500L)).thenReturn(agentBefore, agentAfter);
        when(workitemDao.updateAssignee(500L, 100L, "AGENT", 40014L, 3, 7L)).thenReturn(1);

        service.assignAs(500L, "AGENT", 40014L, null, null, 100L, 7L,
                AssignmentActor.human(7L, "张三"));

        verify(eventPublisher, never()).publishEvent(isA(WorkitemHumanAssignedEvent.class));
    }

    @Test
    void list_uses_batch_queries_instead_of_per_row_N_plus_1() {
        long tenantId = 100L;
        WorkitemDO w1 = new WorkitemDO();
        w1.setId(1L);
        w1.setTenantId(tenantId);
        w1.setWorkType("BUG");
        w1.setTitle("bug-1");
        w1.setCreatorId(10L);
        w1.setAssigneeType("HUMAN");
        w1.setAssigneeRef(20L);
        w1.setStatusNodeId(100L);
        w1.setSdlcId(200L);
        w1.setPriority(96);
        w1.setVersion(1);
        w1.setGmtCreate(new Date());

        WorkitemDO w2 = new WorkitemDO();
        w2.setId(2L);
        w2.setTenantId(tenantId);
        w2.setWorkType("BUG");
        w2.setTitle("bug-2");
        w2.setCreatorId(10L);
        w2.setAssigneeType("AGENT");
        w2.setAssigneeRef(300L);
        w2.setStatusNodeId(100L);
        w2.setSdlcId(200L);
        w2.setPriority(95);
        w2.setVersion(0);
        w2.setGmtCreate(new Date());

        when(workitemDao.count(tenantId, "BUG", null, null, null, false, null, 10L, null, null)).thenReturn(2L);
        when(workitemDao.list(tenantId, "BUG", null, null, null, false, null, 10L, null, null, 0, 20))
                .thenReturn(List.of(w1, w2));

        DispatchDO d1 = new DispatchDO();
        d1.setWorkitemId(1L);
        d1.setStatus(DispatchStatus.SUCCEEDED);
        when(dispatchDao.listLatestByWorkitemIds(List.of(1L, 2L))).thenReturn(List.of(d1));

        UserDO creator = human(10L, "Alice", "alice");
        UserDO assignee = human(20L, "Bob", "bob");
        when(userDao.listByIds(any())).thenReturn(List.of(creator, assignee));

        AgentDO agent = new AgentDO();
        agent.setId(300L);
        agent.setName("Reviewer");
        when(agentDao.listByIds(eq(tenantId), any())).thenReturn(List.of(agent));

        StatusNodeDO statusNode = node(100L, "open");
        statusNode.setCategory("INIT");
        statusNode.setName("待修复");
        when(nodeDao.listByIds(any())).thenReturn(List.of(statusNode));

        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(200L);
        sdlc.setName("研发SDLC");
        when(sdlcDao.listByIds(any())).thenReturn(List.of(sdlc));

        when(externalWorkitemLinkDao.listByWorkitemIds(eq(tenantId), any())).thenReturn(List.of());
        when(dispatchDao.listByWorkitemIds(any())).thenReturn(List.of());

        PageResult<WorkitemVO> result = service.list("BUG", null, null, null, false, null,
                tenantId, 10L, null, 1, 20);

        assertEquals(2, result.getList().size());

        WorkitemVO vo1 = result.getList().get(0);
        assertEquals("Alice", vo1.getCreatorName());
        assertEquals("Alice(10)", vo1.getCreatorDisplayName());
        assertEquals("Bob", vo1.getAssigneeName());
        assertEquals("Bob(20)", vo1.getAssigneeDisplayName());
        assertEquals("待修复", vo1.getStatusName());
        assertEquals("研发SDLC", vo1.getSdlcName());
        assertTrue(vo1.getDeletable());

        WorkitemVO vo2 = result.getList().get(1);
        assertEquals("Reviewer", vo2.getAssigneeName());
        assertEquals("Reviewer(300)", vo2.getAssigneeDisplayName());

        verify(userDao).listByIds(any());
        verify(agentDao).listByIds(eq(tenantId), any());
        verify(nodeDao).listByIds(any());
        verify(sdlcDao).listByIds(any());
        verify(externalWorkitemLinkDao).listByWorkitemIds(eq(tenantId), any());
        verify(dispatchDao).listByWorkitemIds(any());

        verify(userDao, never()).findById(anyLong());
        verify(nodeDao, never()).findById(anyLong());
        verify(sdlcDao, never()).findById(anyLong());
        verify(agentDao, never()).findById(anyLong());
        verify(externalWorkitemLinkDao, never()).listByWorkitem(anyLong(), anyLong());
        verify(dispatchDao, never()).listByWorkitem(anyLong(), anyLong());
    }

    @Test
    void list_prefetch_produces_same_fields_as_single_row_enrichment() {
        long tenantId = 100L;
        WorkitemDO w = new WorkitemDO();
        w.setId(10L);
        w.setTenantId(tenantId);
        w.setWorkType("REQ");
        w.setTitle("需求X");
        w.setCreatorId(50L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(400L);
        w.setStatusNodeId(100L);
        w.setSdlcId(200L);
        w.setPriority(94);
        w.setVersion(2);
        w.setGmtCreate(new Date());
        w.setGmtModified(new Date());

        when(workitemDao.count(tenantId, "REQ", null, null, null, false, null, 50L, null, null)).thenReturn(1L);
        when(workitemDao.list(tenantId, "REQ", null, null, null, false, null, 50L, null, null, 0, 20))
                .thenReturn(List.of(w));
        when(dispatchDao.listLatestByWorkitemIds(List.of(10L))).thenReturn(List.of());

        UserDO creator = human(50L, "Creator", "creator");
        when(userDao.listByIds(any())).thenReturn(List.of(creator));

        AgentDO agent = new AgentDO();
        agent.setId(400L);
        agent.setName("DevAgent");
        when(agentDao.listByIds(eq(tenantId), any())).thenReturn(List.of(agent));

        StatusNodeDO statusNode = node(100L, "in_progress");
        statusNode.setCategory("IN_PROGRESS");
        statusNode.setName("进行中");
        when(nodeDao.listByIds(any())).thenReturn(List.of(statusNode));

        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(200L);
        sdlc.setName("SDLC-A");
        when(sdlcDao.listByIds(any())).thenReturn(List.of(sdlc));

        when(externalWorkitemLinkDao.listByWorkitemIds(eq(tenantId), any())).thenReturn(List.of());
        when(dispatchDao.listByWorkitemIds(any())).thenReturn(List.of());

        PageResult<WorkitemVO> result = service.list("REQ", null, null, null, false, null,
                tenantId, 50L, null, 1, 20);

        WorkitemVO vo = result.getList().get(0);
        assertEquals(10L, vo.getId());
        assertEquals("REQ", vo.getWorkType());
        assertEquals("需求X", vo.getTitle());
        assertEquals("Creator", vo.getCreatorName());
        assertEquals("Creator(50)", vo.getCreatorDisplayName());
        assertEquals("DevAgent", vo.getAssigneeName());
        assertEquals("DevAgent(400)", vo.getAssigneeDisplayName());
        assertEquals("进行中", vo.getStatusName());
        assertEquals("SDLC-A", vo.getSdlcName());
        assertEquals(Integer.valueOf(94), vo.getPriority());
        assertEquals(Integer.valueOf(2), vo.getVersion());
        assertEquals("NATIVE", vo.getSourceType());
        assertTrue(vo.getDeletable());
        assertNull(vo.getDeletableReason());
        assertFalse(vo.getPendingDecision());
        assertEquals("OK", vo.getHealth());
    }

    @Test
    void list_marks_workitem_not_deletable_when_any_dispatch_is_active_not_just_latest() {
        long tenantId = 100L;
        WorkitemDO w = new WorkitemDO();
        w.setId(1L);
        w.setTenantId(tenantId);
        w.setWorkType("BUG");
        w.setTitle("stuck-dispatch");
        w.setCreatorId(10L);
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(10L);
        w.setStatusNodeId(100L);
        w.setSdlcId(200L);
        w.setPriority(96);
        w.setVersion(1);
        w.setGmtCreate(new Date());

        when(workitemDao.count(tenantId, "BUG", null, null, null, false, null, 10L, null, null)).thenReturn(1L);
        when(workitemDao.list(tenantId, "BUG", null, null, null, false, null, 10L, null, null, 0, 20))
                .thenReturn(List.of(w));

        DispatchDO latestSucceeded = new DispatchDO();
        latestSucceeded.setWorkitemId(1L);
        latestSucceeded.setStatus(DispatchStatus.SUCCEEDED);
        when(dispatchDao.listLatestByWorkitemIds(List.of(1L))).thenReturn(List.of(latestSucceeded));

        DispatchDO oldStuck = new DispatchDO();
        oldStuck.setWorkitemId(1L);
        oldStuck.setStatus(DispatchStatus.RUNNING);
        when(dispatchDao.listByWorkitemIds(any())).thenReturn(List.of(oldStuck, latestSucceeded));

        UserDO creator = human(10L, "Alice", "alice");
        when(userDao.listByIds(any())).thenReturn(List.of(creator));
        StatusNodeDO statusNode = node(100L, "open");
        statusNode.setCategory("INIT");
        statusNode.setName("待修复");
        when(nodeDao.listByIds(any())).thenReturn(List.of(statusNode));
        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(200L);
        sdlc.setName("SDLC");
        when(sdlcDao.listByIds(any())).thenReturn(List.of(sdlc));
        when(externalWorkitemLinkDao.listByWorkitemIds(eq(tenantId), any())).thenReturn(List.of());

        PageResult<WorkitemVO> result = service.list("BUG", null, null, null, false, null,
                tenantId, 10L, null, 1, 20);

        WorkitemVO vo = result.getList().get(0);
        assertFalse(vo.getDeletable(), "Workitem with an older RUNNING dispatch must not be deletable");
        assertNotNull(vo.getDeletableReason());
    }
}
