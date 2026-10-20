package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.*;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.workitem.dto.DeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.DeliveryStepVO;
import com.aliyun.autowonder.workitem.dto.ProcessGraphNodeVO;
import com.aliyun.autowonder.workitem.dto.StepTimingVO;
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
    private ExecutorDao executorDao;
    private com.aliyun.autowonder.aiusage.DispatchAiUsageDao usageDao;
    private ScheduledTaskRunDeliveryProgressService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        eventDao = mock(DispatchRuntimeEventDao.class);
        stepDao = mock(SdlcStepDao.class);
        agentDao = mock(AgentDao.class);
        executorDao = mock(ExecutorDao.class);
        usageDao = mock(com.aliyun.autowonder.aiusage.DispatchAiUsageDao.class);
        service = new ScheduledTaskRunDeliveryProgressService(dispatchDao, eventDao, stepDao, agentDao, executorDao, usageDao);
    }

    @Test
    void exposesExecutionsWithoutWorkflowStepsAndOnlyTerminalEndTimes() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(100L); run.setStatus("RUNNING");
        DispatchDO failed = new DispatchDO();
        failed.setId(11280L); failed.setAgentId(41L); failed.setStatus("FAILED");
        failed.setResumeMode("CANONICAL_INTERACTION"); failed.setError("original diagnostic");
        failed.setGmtCreate(new Date(1000L)); failed.setGmtModified(new Date(2000L));
        DispatchDO running = new DispatchDO();
        running.setId(11281L); running.setAgentId(41L); running.setStatus("RUNNING");
        running.setGmtCreate(new Date(3000L)); running.setGmtModified(new Date(4000L));
        when(dispatchDao.listBySource(7L, "SCHEDULED_TASK_RUN", 100L)).thenReturn(List.of(failed, running));
        var result = service.getDeliveryProgress(7L, run);
        assertEquals(2, result.getExecutions().size());
        assertTrue(result.getSteps().isEmpty());
        assertEquals(failed.getGmtCreate(), result.getExecutions().get(0).getStartedAt());
        assertEquals(failed.getGmtModified(), result.getExecutions().get(0).getEndedAt());
        assertEquals("original diagnostic", result.getExecutions().get(0).getError());
        assertNull(result.getExecutions().get(1).getEndedAt());
        // 无 SDLC 步骤定义时没有阶段快照可投影
        assertNull(result.getExecutions().get(0).getStepTimings());
    }

    @Test
    void canceledRunConvergesStartedStepWithoutTerminalRuntimeEvent() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(100L); run.setSdlcId(10L); run.setStatus("CANCELED");
        SdlcStepDO step = new SdlcStepDO(); step.setId(1L); step.setName("Audit");
        when(stepDao.listBySdlc(10L)).thenReturn(List.of(step));
        DispatchDO dispatch = new DispatchDO(); dispatch.setId(201L);
        dispatch.setSdlcStepId(1L); dispatch.setStatus("CANCELED");
        when(dispatchDao.listBySource(1L, "SCHEDULED_TASK_RUN", 100L)).thenReturn(List.of(dispatch));
        DispatchRuntimeEventDO started = new DispatchRuntimeEventDO();
        started.setStepId(1L); started.setEventType("step.started");
        when(eventDao.listByDispatch(1L, 201L)).thenReturn(List.of(started));
        var result = service.getDeliveryProgress(1L, run);
        assertEquals("cancelled", result.getSteps().get(0).getStatus());
        assertEquals("cancelled", result.getAgents().get(0).getStatus());
        run.setStatus("FAILED");
        result = service.getDeliveryProgress(1L, run);
        assertEquals("failed", result.getSteps().get(0).getStatus());
        assertEquals("failed", result.getAgents().get(0).getStatus());
        run.setStatus("SUCCEEDED");
        result = service.getDeliveryProgress(1L, run);
        assertEquals("done", result.getSteps().get(0).getStatus());
        assertEquals("finished", result.getAgents().get(0).getStatus());
        started.setEventType("step.completed");
        assertEquals("done", service.getDeliveryProgress(1L, run).getSteps().get(0).getStatus());
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
        dispatch1.setExecutorId(60L);
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

        ExecutorDO executor = new ExecutorDO();
        executor.setId(60L);
        executor.setName("runner-daily-01");
        when(executorDao.findById(60L)).thenReturn(executor);

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
        assertEquals("CodeBot", firstStep.getAttempts().get(0).getAgentName());
        assertEquals(60L, firstStep.getAttempts().get(0).getExecutorId());
        assertEquals("runner-daily-01", firstStep.getAttempts().get(0).getExecutorName());
        // 顶层 executions 与步骤 attempts 复用同一请求内执行器缓存，不重复查询
        assertEquals("runner-daily-01", result.getExecutions().get(0).getExecutorName());
        verify(executorDao, times(1)).findById(60L);

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

    @Test
    void executionStageSnapshotKeepsEveryStageTheDispatchRan() {
        // Run 10910 / Dispatch 12238：同一 dispatch 连续跑完入口阶段与第二阶段，
        // 第二阶段在 gate 失败；快照必须同时给出两个阶段，且重复 started 不双算。
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(510L);
        run.setWorkspaceId(1L);
        run.setSdlcId(10018L);
        run.setInitialAgentId(10033L);
        run.setCurrentAgentId(10033L);
        run.setStatus("FAILED");
        run.setStartedAt(new Date(1000L));
        run.setFinishedAt(new Date(60_000L));

        SdlcStepDO collect = new SdlcStepDO();
        collect.setId(10088L);
        collect.setCode("collect_log");
        collect.setName("收集工单执行日志");
        collect.setStepOrder(1);
        SdlcStepDO analyze = new SdlcStepDO();
        analyze.setId(10131L);
        analyze.setCode("analyze");
        analyze.setName("分析日志、总结报告并提交 CR");
        analyze.setStepOrder(2);
        when(stepDao.listBySdlc(10018L)).thenReturn(List.of(collect, analyze));

        String failureReason = "step \"10131\" gate: checklist not fully checked: \"statistics\" remains pending";
        DispatchDO failed = new DispatchDO();
        failed.setId(12238L);
        failed.setSdlcStepId(10088L);
        failed.setAgentId(10033L);
        failed.setStatus(DispatchStatus.FAILED);
        failed.setError(failureReason);
        failed.setGmtCreate(new Date(1000L));
        failed.setGmtModified(new Date(10_000L));

        DispatchDO retry = new DispatchDO();
        retry.setId(12255L);
        retry.setSdlcStepId(10131L);
        retry.setAgentId(10033L);
        retry.setStatus(DispatchStatus.SUCCEEDED);
        retry.setGmtCreate(new Date(20_000L));
        retry.setGmtModified(new Date(25_000L));

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 510L))
                .thenReturn(List.of(failed, retry));

        DispatchRuntimeEventDO collectStarted = runtimeEvent(12238L, 10088L, "step.started", 1000L, null);
        DispatchRuntimeEventDO collectCompleted = runtimeEvent(12238L, 10088L, "step.completed", 4000L, null);
        DispatchRuntimeEventDO analyzeStarted = runtimeEvent(12238L, 10131L, "step.started", 5000L, null);
        DispatchRuntimeEventDO analyzeRestarted = runtimeEvent(12238L, 10131L, "step.started", 6000L, null);
        DispatchRuntimeEventDO analyzeFailed = runtimeEvent(12238L, 10131L, "step.failed", 9000L, failureReason);
        when(eventDao.listByDispatch(1L, 12238L)).thenReturn(List.of(
                collectStarted, collectCompleted, analyzeStarted, analyzeRestarted, analyzeFailed));

        DispatchRuntimeEventDO retryStarted = runtimeEvent(12255L, 10131L, "step.started", 20_000L, null);
        DispatchRuntimeEventDO retryCompleted = runtimeEvent(12255L, 10131L, "step.completed", 21_000L, null);
        when(eventDao.listByDispatch(1L, 12255L)).thenReturn(List.of(retryStarted, retryCompleted));

        AgentDO agent = new AgentDO();
        agent.setId(10033L);
        agent.setName("任务蒸馏数字员工");
        when(agentDao.findById(10033L)).thenReturn(agent);

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        List<StepTimingVO> snapshot = result.getExecutions().get(0).getStepTimings();
        assertNotNull(snapshot);
        assertEquals(2, snapshot.size());
        assertEquals(10088L, snapshot.get(0).getStepId());
        assertEquals("收集工单执行日志", snapshot.get(0).getName());
        assertEquals("done", snapshot.get(0).getStatus());
        assertEquals(3000L, snapshot.get(0).getDurationMs());
        assertEquals(10131L, snapshot.get(1).getStepId());
        assertEquals("分析日志、总结报告并提交 CR", snapshot.get(1).getName());
        assertEquals("failed", snapshot.get(1).getStatus());
        // 5000 → 9000；6000 的重复 step.started 不产生第二段区间
        assertEquals(4000L, snapshot.get(1).getDurationMs());
        // 真实业务失败信息仍透传在所选 execution 上
        assertEquals(failureReason, result.getExecutions().get(0).getError());

        // 其他 dispatch（12255）的阶段快照只认自己的事件：耗时 1000ms，不串用 12238 的区间
        List<StepTimingVO> retrySnapshot = result.getExecutions().get(1).getStepTimings();
        assertNotNull(retrySnapshot);
        assertEquals("pending", retrySnapshot.get(0).getStatus());
        assertNull(retrySnapshot.get(0).getDurationMs());
        assertEquals("done", retrySnapshot.get(1).getStatus());
        assertEquals(1000L, retrySnapshot.get(1).getDurationMs());
    }

    @Test
    void executionStageSnapshotMarksUnmeasuredAndMissingCompletionExplicitly() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(600L);
        run.setWorkspaceId(1L);
        run.setSdlcId(60L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("SUCCEEDED");

        SdlcStepDO interrupted = new SdlcStepDO();
        interrupted.setId(1L);
        interrupted.setCode("first");
        interrupted.setName("First");
        interrupted.setStepOrder(1);
        SdlcStepDO untouched = new SdlcStepDO();
        untouched.setId(2L);
        untouched.setCode("second");
        untouched.setName("Second");
        untouched.setStepOrder(2);
        SdlcStepDO failed = new SdlcStepDO();
        failed.setId(3L);
        failed.setCode("third");
        failed.setName("Third");
        failed.setStepOrder(3);
        when(stepDao.listBySdlc(60L)).thenReturn(List.of(interrupted, untouched, failed));

        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(601L);
        dispatch.setSdlcStepId(1L);
        dispatch.setAgentId(50L);
        dispatch.setStatus(DispatchStatus.SUCCEEDED);
        dispatch.setGmtCreate(new Date(1000L));
        dispatch.setGmtModified(new Date(6000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 600L))
                .thenReturn(List.of(dispatch));
        when(eventDao.listByDispatch(1L, 601L)).thenReturn(List.of(
                runtimeEvent(601L, 1L, "step.started", 1000L, null),
                runtimeEvent(601L, 1L, "step.completed", 3000L, null),
                runtimeEvent(601L, 3L, "step.started", 4000L, null)));

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        List<StepTimingVO> snapshot = result.getExecutions().get(0).getStepTimings();
        assertEquals(3, snapshot.size());
        assertEquals("done", snapshot.get(0).getStatus());
        assertEquals(2000L, snapshot.get(0).getDurationMs());
        // 未执行阶段不得补造耗时
        assertEquals("pending", snapshot.get(1).getStatus());
        assertNull(snapshot.get(1).getDurationMs());
        // dispatch 已终态但末阶段缺少完成事件：明确标成「已结束，完成记录缺失」，耗时截止到终态时刻
        assertEquals("interrupted", snapshot.get(2).getStatus());
        assertEquals(2000L, snapshot.get(2).getDurationMs());
    }

    @Test
    void interactionDispatchCarriesNoStageSnapshot() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(620L);
        run.setWorkspaceId(1L);
        run.setSdlcId(60L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("FAILED");

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setCode("first");
        step.setName("First");
        step.setStepOrder(1);
        when(stepDao.listBySdlc(60L)).thenReturn(List.of(step));

        DispatchDO interaction = new DispatchDO();
        interaction.setId(621L);
        interaction.setSdlcStepId(1L);
        interaction.setAgentId(50L);
        interaction.setResumeMode("CANONICAL_INTERACTION");
        interaction.setStatus(DispatchStatus.FAILED);
        interaction.setGmtCreate(new Date(1000L));
        interaction.setGmtModified(new Date(2000L));

        DispatchDO formal = new DispatchDO();
        formal.setId(622L);
        formal.setSdlcStepId(1L);
        formal.setAgentId(50L);
        formal.setStatus(DispatchStatus.FAILED);
        formal.setGmtCreate(new Date(3000L));
        formal.setGmtModified(new Date(6000L));

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 620L))
                .thenReturn(List.of(interaction, formal));
        when(eventDao.listByDispatch(1L, 621L)).thenReturn(List.of(runtimeEvent(621L, 1L, "step.started", 1000L, null)));
        when(eventDao.listByDispatch(1L, 622L)).thenReturn(List.of(runtimeEvent(622L, 1L, "step.started", 3000L, null)));

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        assertNull(result.getExecutions().get(0).getStepTimings());
        List<StepTimingVO> formalSnapshot = result.getExecutions().get(1).getStepTimings();
        assertNotNull(formalSnapshot);
        assertEquals("failed", formalSnapshot.get(0).getStatus());
        assertEquals(3000L, formalSnapshot.get(0).getDurationMs());
    }

    @Test
    void sideAndCommentInteractionDispatchesCarryNoStageSnapshot() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(640L);
        run.setWorkspaceId(1L);
        run.setSdlcId(60L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("RUNNING");

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setCode("first");
        step.setName("First");
        step.setStepOrder(1);
        when(stepDao.listBySdlc(60L)).thenReturn(List.of(step));

        DispatchDO sideInteraction = new DispatchDO();
        sideInteraction.setId(641L);
        sideInteraction.setSdlcStepId(1L);
        sideInteraction.setAgentId(50L);
        sideInteraction.setResumeMode("SIDE_INTERACTION");
        sideInteraction.setStatus(DispatchStatus.RUNNING);
        sideInteraction.setGmtCreate(new Date(1000L));

        DispatchDO commentInteraction = new DispatchDO();
        commentInteraction.setId(642L);
        commentInteraction.setSdlcStepId(1L);
        commentInteraction.setAgentId(50L);
        commentInteraction.setResumeMode("COMMENT_INTERACTION");
        commentInteraction.setStatus(DispatchStatus.RUNNING);
        commentInteraction.setGmtCreate(new Date(2000L));

        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 640L))
                .thenReturn(List.of(sideInteraction, commentInteraction));
        when(eventDao.listByDispatch(1L, 641L)).thenReturn(List.of(runtimeEvent(641L, 1L, "step.started", 1000L, null)));
        when(eventDao.listByDispatch(1L, 642L)).thenReturn(List.of(runtimeEvent(642L, 1L, "step.started", 2000L, null)));

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        // SIDE_/COMMENT_ 交互型 dispatch 与 CANONICAL_ 同口径：不承载 SDLC 阶段，不产出快照
        assertEquals(2, result.getExecutions().size());
        assertNull(result.getExecutions().get(0).getStepTimings());
        assertNull(result.getExecutions().get(1).getStepTimings());
    }

    @Test
    void nullRuntimeEventListLeavesStageSnapshotUnmeasured() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(650L);
        run.setWorkspaceId(1L);
        run.setSdlcId(60L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("RUNNING");

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setCode("first");
        step.setName("First");
        step.setStepOrder(1);
        when(stepDao.listBySdlc(60L)).thenReturn(List.of(step));

        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(651L);
        dispatch.setSdlcStepId(1L);
        dispatch.setAgentId(50L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        dispatch.setGmtCreate(new Date(1000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 650L))
                .thenReturn(List.of(dispatch));
        // DAO 返回 null 视为无事件：按「未测量」呈现，不得抛错或补造耗时
        when(eventDao.listByDispatch(1L, 651L)).thenReturn(null);

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        assertEquals("pending", result.getSteps().get(0).getStatus());
        List<StepTimingVO> snapshot = result.getExecutions().get(0).getStepTimings();
        assertNotNull(snapshot);
        assertEquals(1, snapshot.size());
        assertEquals("pending", snapshot.get(0).getStatus());
        assertNull(snapshot.get(0).getDurationMs());
    }

    @Test
    void stepDefinitionWithoutIdYieldsNoAttemptsAndNoGraphStepName() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(660L);
        run.setWorkspaceId(1L);
        run.setSdlcId(61L);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("RUNNING");

        SdlcStepDO anonymous = new SdlcStepDO();
        anonymous.setCode("anonymous");
        anonymous.setName("Anonymous");
        anonymous.setStepOrder(1);
        SdlcStepDO named = new SdlcStepDO();
        named.setId(2L);
        named.setCode("second");
        named.setName("Second");
        named.setStepOrder(2);
        when(stepDao.listBySdlc(61L)).thenReturn(List.of(anonymous, named));

        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(661L);
        dispatch.setSdlcStepId(2L);
        dispatch.setAgentId(50L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        dispatch.setGmtCreate(new Date(1000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 660L))
                .thenReturn(List.of(dispatch));
        when(eventDao.listByDispatch(1L, 661L)).thenReturn(List.of(runtimeEvent(661L, 2L, "step.started", 1000L, null)));

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        // 无 id 的步骤定义不参与 dispatch 归位，也不进入进程图步骤名映射
        assertEquals(2, result.getSteps().size());
        assertEquals("pending", result.getSteps().get(0).getStatus());
        assertTrue(result.getSteps().get(0).getAttempts().isEmpty());
        assertEquals("active", result.getSteps().get(1).getStatus());
        assertEquals(1, result.getSteps().get(1).getAttempts().size());
        assertEquals(1, result.getProcessGraph().getNodes().size());
        assertEquals("Second", result.getProcessGraph().getNodes().get(0).getStepName());
    }

    @Test
    void pausedAndCanceledDispatchesKeepTheirStageSemantics() {
        ScheduledTaskRunDO pausedRun = new ScheduledTaskRunDO();
        pausedRun.setId(630L);
        pausedRun.setWorkspaceId(1L);
        pausedRun.setSdlcId(60L);
        pausedRun.setInitialAgentId(50L);
        pausedRun.setCurrentAgentId(50L);
        pausedRun.setStatus("PAUSED");

        ScheduledTaskRunDO canceledRun = new ScheduledTaskRunDO();
        canceledRun.setId(631L);
        canceledRun.setWorkspaceId(1L);
        canceledRun.setSdlcId(60L);
        canceledRun.setInitialAgentId(50L);
        canceledRun.setCurrentAgentId(50L);
        canceledRun.setStatus("CANCELED");

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setCode("first");
        step.setName("First");
        step.setStepOrder(1);
        when(stepDao.listBySdlc(60L)).thenReturn(List.of(step));

        DispatchDO paused = new DispatchDO();
        paused.setId(632L);
        paused.setSdlcStepId(1L);
        paused.setAgentId(50L);
        paused.setStatus(DispatchStatus.PAUSED);
        paused.setGmtCreate(new Date(1000L));
        paused.setGmtModified(new Date(4000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 630L))
                .thenReturn(List.of(paused));
        when(eventDao.listByDispatch(1L, 632L)).thenReturn(List.of(runtimeEvent(632L, 1L, "step.started", 1000L, null)));

        DeliveryProgressVO pausedResult = service.getDeliveryProgress(1L, pausedRun);
        List<StepTimingVO> pausedSnapshot = pausedResult.getExecutions().get(0).getStepTimings();
        assertEquals("paused", pausedSnapshot.get(0).getStatus());
        assertEquals(3000L, pausedSnapshot.get(0).getDurationMs());

        DispatchDO canceled = new DispatchDO();
        canceled.setId(633L);
        canceled.setSdlcStepId(1L);
        canceled.setAgentId(50L);
        canceled.setStatus(DispatchStatus.CANCELED);
        canceled.setGmtCreate(new Date(1000L));
        canceled.setGmtModified(new Date(6000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 631L))
                .thenReturn(List.of(canceled));
        when(eventDao.listByDispatch(1L, 633L)).thenReturn(List.of(runtimeEvent(633L, 1L, "step.started", 2000L, null)));

        DeliveryProgressVO canceledResult = service.getDeliveryProgress(1L, canceledRun);
        List<StepTimingVO> canceledSnapshot = canceledResult.getExecutions().get(0).getStepTimings();
        assertEquals("cancelled", canceledSnapshot.get(0).getStatus());
        assertEquals(4000L, canceledSnapshot.get(0).getDurationMs());
    }

    @Test
    void fallbackSnapshotStepsCarryOrderForStageSnapshot() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(700L);
        run.setWorkspaceId(1L);
        run.setSdlcId(null);
        run.setInitialAgentId(50L);
        run.setCurrentAgentId(50L);
        run.setStatus("FAILED");
        run.setExecutionSnapshotJson("{\"agentContexts\":[{\"sdlc\":{\"steps\":[" +
                "{\"id\":99,\"code\":\"deploy\",\"name\":\"Deploy\"}," +
                "{\"id\":100,\"code\":\"verify\",\"name\":\"Verify\"}]}}]}");

        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(701L);
        dispatch.setSdlcStepId(99L);
        dispatch.setAgentId(50L);
        dispatch.setStatus(DispatchStatus.FAILED);
        dispatch.setGmtCreate(new Date(1000L));
        dispatch.setGmtModified(new Date(9000L));
        when(dispatchDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), 700L))
                .thenReturn(List.of(dispatch));
        when(eventDao.listByDispatch(1L, 701L)).thenReturn(List.of(
                runtimeEvent(701L, 99L, "step.started", 1000L, null),
                runtimeEvent(701L, 99L, "step.completed", 3000L, null),
                runtimeEvent(701L, 100L, "step.started", 4000L, null),
                runtimeEvent(701L, 100L, "step.failed", 8000L, "verify failed")));

        DeliveryProgressVO result = service.getDeliveryProgress(1L, run);

        List<StepTimingVO> snapshot = result.getExecutions().get(0).getStepTimings();
        assertNotNull(snapshot);
        assertEquals(2, snapshot.size());
        assertEquals(99L, snapshot.get(0).getStepId());
        assertEquals("done", snapshot.get(0).getStatus());
        assertEquals(2000L, snapshot.get(0).getDurationMs());
        assertEquals(100L, snapshot.get(1).getStepId());
        assertEquals("failed", snapshot.get(1).getStatus());
        assertEquals(4000L, snapshot.get(1).getDurationMs());
    }

    private static DispatchRuntimeEventDO runtimeEvent(Long dispatchId, Long stepId, String eventType, long at, String error) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setDispatchId(dispatchId);
        event.setStepId(stepId);
        event.setEventType(eventType);
        event.setEventTime(new Date(at));
        event.setError(error);
        return event;
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
