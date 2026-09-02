package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.*;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.workitem.dto.DeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.DeliveryStepVO;
import com.aliyun.autowonder.workitem.dto.ProcessGraphNodeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledTaskRunDeliveryProgressServiceTest {
    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao eventDao;
    private SdlcStepDao stepDao;
    private AgentDao agentDao;
    private com.aliyun.autowonder.aiusage.DispatchAiUsageDao usageDao;
    private ScheduledTaskRunDeliveryProgressService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        eventDao = mock(DispatchRuntimeEventDao.class);
        stepDao = mock(SdlcStepDao.class);
        agentDao = mock(AgentDao.class);
        usageDao = mock(com.aliyun.autowonder.aiusage.DispatchAiUsageDao.class);
        service = new ScheduledTaskRunDeliveryProgressService(dispatchDao, eventDao, stepDao, agentDao, usageDao);
    }

    @Test
    void succeededRunWithTwoSteps_oneCompletedOnePending() {
        // Setup run
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(100L);
        run.setWorkspaceId(1L);
        run.setSdlcId(10L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("SUCCEEDED");
        run.setStartedAt(new Date(1000L));
        run.setFinishedAt(new Date(5000L));

        // Mock SDLC steps
        SdlcStepDO step1 = new SdlcStepDO();
        step1.setId(1L);
        step1.setCode("coding");
        step1.setName("Coding");
        step1.setStepOrder(1);

        SdlcStepDO step2 = new SdlcStepDO();
        step2.setId(2L);
        step2.setCode("testing");
        step2.setName("Testing");
        step2.setStepOrder(2);

        when(stepDao.listBySdlc(10L)).thenReturn(List.of(step1, step2));

        // Mock dispatch for step1
        DispatchDO dispatch1 = new DispatchDO();
        dispatch1.setId(201L);
        dispatch1.setSdlcStepId(1L);
        dispatch1.setAgentId(50L);
        dispatch1.setStatus(DispatchStatus.SUCCEEDED);
        dispatch1.setGmtCreate(new Date(1000L));
        dispatch1.setGmtModified(new Date(3000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 100L))
                .thenReturn(List.of(dispatch1));

        // Mock events: step1 started and completed
        DispatchRuntimeEventDO startEvent = new DispatchRuntimeEventDO();
        startEvent.setDispatchId(201L);
        startEvent.setStepId(1L);
        startEvent.setEventType("step.started");
        startEvent.setEventTime(new Date(1000L));

        DispatchRuntimeEventDO completedEvent = new DispatchRuntimeEventDO();
        completedEvent.setDispatchId(201L);
        completedEvent.setStepId(1L);
        completedEvent.setEventType("step.completed");
        completedEvent.setEventTime(new Date(3000L));

        when(eventDao.listByDispatch(1L, 201L)).thenReturn(List.of(startEvent, completedEvent));

        // Mock agent
        AgentDO agent = new AgentDO();
        agent.setId(50L);
        agent.setName("CodeBot");
        when(agentDao.findById(50L)).thenReturn(agent);

        // Execute
        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        // Verify
        assertNotNull(result);
        // 总耗时 = dispatch 执行时长(2000ms)，而不是 run wall-clock(4000ms)
        assertEquals(2000L, result.getTotalDurationMs());
        assertEquals(2, result.getSteps().size());

        DeliveryStepVO firstStep = result.getSteps().get(0);
        assertEquals("Coding", firstStep.getName());
        assertEquals("done", firstStep.getStatus());
        assertEquals(2000L, firstStep.getDurationMs());
        assertEquals(1, firstStep.getAttempts().size());
        assertEquals(201L, firstStep.getAttempts().get(0).getDispatchId());
        assertEquals(2000L, firstStep.getAttempts().get(0).getDurationMs());

        DeliveryStepVO secondStep = result.getSteps().get(1);
        assertEquals("Testing", secondStep.getName());
        assertEquals("pending", secondStep.getStatus());
        assertTrue(secondStep.getAttempts().isEmpty());

        // Verify agents
        assertEquals(1, result.getAgents().size());
        assertEquals("CodeBot", result.getAgents().get(0).getAgentName());
        // Agent 面板耗时与总耗时同口径
        assertEquals(2000L, result.getAgents().get(0).getDurationMs());
        // 已完成的数字人必须展示为「已完成」，而不是透传 run 状态回退成「未执行」
        assertEquals("finished", result.getAgents().get(0).getStatus());
    }

    @Test
    void processGraphHasCorrectNodes() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(200L);
        run.setWorkspaceId(1L);
        run.setSdlcId(20L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("RUNNING");
        run.setStartedAt(new Date(1000L));

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setCode("build");
        step.setName("Build");
        step.setStepOrder(1);
        when(stepDao.listBySdlc(20L)).thenReturn(List.of(step));

        // Two dispatches: second resumes from first
        DispatchDO dispatch1 = new DispatchDO();
        dispatch1.setId(301L);
        dispatch1.setSdlcStepId(1L);
        dispatch1.setAgentId(50L);
        dispatch1.setStatus(DispatchStatus.PAUSED);
        dispatch1.setGmtCreate(new Date(1000L));

        DispatchDO dispatch2 = new DispatchDO();
        dispatch2.setId(302L);
        dispatch2.setSdlcStepId(1L);
        dispatch2.setAgentId(50L);
        dispatch2.setStatus(DispatchStatus.RUNNING);
        dispatch2.setResumeFromDispatchId(301L);
        dispatch2.setGmtCreate(new Date(2000L));

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 200L))
                .thenReturn(List.of(dispatch1, dispatch2));
        when(eventDao.listByDispatch(1L, 301L)).thenReturn(List.of());
        when(eventDao.listByDispatch(1L, 302L)).thenReturn(List.of());

        AgentDO agent = new AgentDO();
        agent.setId(50L);
        agent.setName("BuildBot");
        when(agentDao.findById(50L)).thenReturn(agent);

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        assertNotNull(result.getProcessGraph());
        assertEquals(2, result.getProcessGraph().getNodes().size());
        assertEquals(1, result.getProcessGraph().getEdges().size());

        ProcessGraphNodeVO node1 = result.getProcessGraph().getNodes().get(0);
        assertEquals("dispatch-301", node1.getKey());
        assertEquals(301L, node1.getDispatchId());
        assertEquals("BuildBot", node1.getAgentName());

        ProcessGraphNodeVO node2 = result.getProcessGraph().getNodes().get(1);
        assertEquals("dispatch-302", node2.getKey());
        assertEquals(302L, node2.getDispatchId());

        // CONTINUE edge from 301 to 302
        assertEquals("dispatch-301", result.getProcessGraph().getEdges().get(0).getSourceKey());
        assertEquals("dispatch-302", result.getProcessGraph().getEdges().get(0).getTargetKey());
        assertEquals("CONTINUE", result.getProcessGraph().getEdges().get(0).getType());
    }

    @Test
    void fallbackToSnapshotWhenSdlcIdIsNull() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(300L);
        run.setWorkspaceId(1L);
        run.setSdlcId(null);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("SUCCEEDED");
        run.setStartedAt(new Date(1000L));
        run.setFinishedAt(new Date(4000L));
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"sdlc\":{\"steps\":[" +
                "{\"id\":99,\"code\":\"deploy\",\"name\":\"Deploy\"}]}}]}");

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 300L))
                .thenReturn(List.of());

        AgentDO agent = new AgentDO();
        agent.setId(50L);
        agent.setName("DeployBot");
        when(agentDao.findById(50L)).thenReturn(agent);

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        assertNotNull(result);
        assertEquals(1, result.getSteps().size());
        assertEquals("Deploy", result.getSteps().get(0).getName());
        assertEquals(99L, result.getSteps().get(0).getStepId());
        assertEquals("deploy", result.getSteps().get(0).getStepKey());
        assertEquals("pending", result.getSteps().get(0).getStatus());
        // 无 dispatch 时总耗时为 null，不得回退到 run wall-clock(3000ms)
        assertNull(result.getTotalDurationMs());
    }

    @Test
    void totalDurationExcludesWallClockGapsBetweenDispatches() {
        // run wall-clock 长达 99s，但两段 Agent 执行合计仅 12s，
        // 步骤间排队/交接间隔不得计入总耗时。
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(400L);
        run.setWorkspaceId(1L);
        run.setSdlcId(10L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("SUCCEEDED");
        run.setStartedAt(new Date(1000L));
        run.setFinishedAt(new Date(100_000L));

        SdlcStepDO step1 = new SdlcStepDO();
        step1.setId(1L);
        step1.setCode("coding");
        step1.setName("Coding");
        step1.setStepOrder(1);
        when(stepDao.listBySdlc(10L)).thenReturn(List.of(step1));

        DispatchDO first = new DispatchDO();
        first.setId(501L);
        first.setSdlcStepId(1L);
        first.setAgentId(50L);
        first.setStatus(DispatchStatus.SUCCEEDED);
        first.setGmtCreate(new Date(1000L));
        first.setGmtModified(new Date(3000L));

        DispatchDO second = new DispatchDO();
        second.setId(502L);
        second.setSdlcStepId(1L);
        second.setAgentId(50L);
        second.setStatus(DispatchStatus.SUCCEEDED);
        second.setGmtCreate(new Date(50_000L));
        second.setGmtModified(new Date(60_000L));

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 400L))
                .thenReturn(List.of(first, second));
        when(eventDao.listByDispatch(1L, 501L)).thenReturn(List.of());
        when(eventDao.listByDispatch(1L, 502L)).thenReturn(List.of());

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        // 2s + 10s = 12s，而不是 wall-clock 的 99s
        assertEquals(12_000L, result.getTotalDurationMs());
        assertEquals(12_000L, result.getAgents().get(0).getDurationMs());
    }

    @Test
    void agentStatusDerivedFromStepStatuses_notRunStatusEnum() {
        assertEquals("finished", agentStatusFor("SUCCEEDED", List.of("step.started", "step.completed")));
        assertEquals("active", agentStatusFor("RUNNING", List.of("step.started")));
        assertEquals("paused", agentStatusFor("PAUSED", List.of("step.started")));
        assertEquals("failed", agentStatusFor("FAILED", List.of("step.started", "step.failed")));
        assertEquals("pending", agentStatusFor("QUEUED", List.of()));
    }

    private String agentStatusFor(String runStatus, List<String> eventTypes) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(900L);
        run.setWorkspaceId(1L);
        run.setSdlcId(90L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus(runStatus);

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setCode("coding");
        step.setName("Coding");
        step.setStepOrder(1);
        when(stepDao.listBySdlc(90L)).thenReturn(List.of(step));

        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(901L);
        dispatch.setSdlcStepId(1L);
        dispatch.setAgentId(50L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        dispatch.setGmtCreate(new Date(1000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 900L))
                .thenReturn(List.of(dispatch));

        List<DispatchRuntimeEventDO> events = new java.util.ArrayList<>();
        long eventTime = 1000L;
        for (String eventType : eventTypes) {
            DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
            event.setDispatchId(901L);
            event.setStepId(1L);
            event.setEventType(eventType);
            event.setEventTime(new Date(eventTime));
            eventTime += 1000L;
            events.add(event);
        }
        when(eventDao.listByDispatch(1L, 901L)).thenReturn(events);

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);
        assertEquals(1, result.getAgents().size());
        return result.getAgents().get(0).getStatus();
    }
}
