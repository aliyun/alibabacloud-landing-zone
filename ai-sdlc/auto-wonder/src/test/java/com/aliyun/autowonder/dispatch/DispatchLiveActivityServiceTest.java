package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.dto.DispatchLiveActivityVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchLiveActivityServiceTest {

    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao eventDao;
    private DispatchLiveActivityMetrics metrics;
    private DispatchLiveActivityService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        eventDao = mock(DispatchRuntimeEventDao.class);
        metrics = mock(DispatchLiveActivityMetrics.class);
        service = new DispatchLiveActivityService(dispatchDao, eventDao, metrics);
    }

    @Test
    void getReturnsActionsForValidDispatch() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        when(dispatchDao.findById(1L)).thenReturn(dispatch);
        when(eventDao.listByDispatch(100L, 1L)).thenReturn(List.of(
                buildEvent("step.started", 1L, "开始编码"),
                buildEvent("bash.completed", 2L, "npm test passed")
        ));

        DispatchLiveActivityVO result = service.get(100L, 1L);
        assertNotNull(result);
        assertEquals(1L, result.getDispatchId());
        assertEquals(2, result.getTotalActions());
        assertFalse(result.getActions().isEmpty());
        assertTrue(result.isChanged());
        assertFalse(result.isAwaitingRuntime());
    }

    @Test
    void getReturnsAwaitingRuntimeWhenNoEvents() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        when(dispatchDao.findById(1L)).thenReturn(dispatch);
        when(eventDao.listByDispatch(100L, 1L)).thenReturn(List.of());

        DispatchLiveActivityVO result = service.get(100L, 1L);
        assertTrue(result.isAwaitingRuntime());
        assertTrue(result.getActions().isEmpty());
    }

    @Test
    void getFiltersDeniedEventTypes() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        when(dispatchDao.findById(1L)).thenReturn(dispatch);
        when(eventDao.listByDispatch(100L, 1L)).thenReturn(List.of(
                buildEvent("agent.message", 1L, "secret model output"),
                buildEvent("llm.completion", 2L, "raw tokens"),
                buildEvent("step.started", 3L, "visible action")
        ));

        DispatchLiveActivityVO result = service.get(100L, 1L);
        assertEquals(1, result.getTotalActions());
        assertEquals("visible action", result.getActions().get(0).getSummary());
        verify(metrics, times(2)).filteredRead(anyString());
    }

    @Test
    void getWithAfterSeqReturnsOnlyNewerEvents() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        when(dispatchDao.findById(1L)).thenReturn(dispatch);
        when(eventDao.listByDispatchAfterSeq(100L, 1L, 1L)).thenReturn(List.of(
                buildEvent("bash.started", 2L, "new")
        ));

        DispatchLiveActivityVO result = service.get(100L, 1L, 1L, null);
        assertTrue(result.isChanged());
        assertEquals(1, result.getTotalActions());
        verify(metrics).backfill();
        verify(eventDao, never()).listByDispatch(anyLong(), anyLong());
    }

    @Test
    void getWithAfterSeqReturnsChangedFalseWhenNoNew() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        when(dispatchDao.findById(1L)).thenReturn(dispatch);
        when(eventDao.listByDispatchAfterSeq(100L, 1L, 5L)).thenReturn(List.of());

        DispatchLiveActivityVO result = service.get(100L, 1L, 5L, null);
        assertFalse(result.isChanged());
        assertTrue(result.getActions().isEmpty());
    }

    @Test
    void getRespectsLimitAndMarksTruncated() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        when(dispatchDao.findById(1L)).thenReturn(dispatch);
        List<DispatchRuntimeEventDO> events = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            events.add(buildEvent("step.progress", (long) i, "event " + i));
        }
        when(eventDao.listByDispatch(100L, 1L)).thenReturn(events);

        DispatchLiveActivityVO result = service.get(100L, 1L, null, 3);
        assertTrue(result.isTruncated());
        assertEquals(3, result.getActions().size());
        assertEquals(10, result.getTotalActions());
    }

    @Test
    void actionTypeCategorizesEventPrefixes() {
        assertEquals("CONTEXT_PREPARE", DispatchLiveActivityService.actionType("package.loaded", new JSONObject()));
        assertEquals("REPO_PREPARE", DispatchLiveActivityService.actionType("repo.cloned", new JSONObject()));
        assertEquals("SDLC_STEP", DispatchLiveActivityService.actionType("step.started", new JSONObject()));
        assertEquals("COMMAND", DispatchLiveActivityService.actionType("bash.completed", new JSONObject()));
        assertEquals("MCP_CALL", DispatchLiveActivityService.actionType("mcp.tool_called", new JSONObject()));
        assertEquals("ARTIFACT", DispatchLiveActivityService.actionType("artifact.uploaded", new JSONObject()));
        assertEquals("SESSION", DispatchLiveActivityService.actionType("session.started", new JSONObject()));
        assertEquals("MODEL_TURN", DispatchLiveActivityService.actionType("turn.completed", new JSONObject()));
        assertEquals("SUBAGENT", DispatchLiveActivityService.actionType("subagent.spawned", new JSONObject()));
        assertEquals("DISPATCH", DispatchLiveActivityService.actionType("dispatch.started", new JSONObject()));
        assertNull(DispatchLiveActivityService.actionType("agent.message", new JSONObject()));
        assertNull(DispatchLiveActivityService.actionType("llm.raw", new JSONObject()));
        assertNull(DispatchLiveActivityService.actionType("guidance.human", new JSONObject()));
        assertNull(DispatchLiveActivityService.actionType(null, new JSONObject()));
    }

    @Test
    void toolActionTypeCategorizesTools() {
        assertEquals("SEARCH_READ", DispatchLiveActivityService.toolActionType("Read"));
        assertEquals("SEARCH_READ", DispatchLiveActivityService.toolActionType("grep"));
        assertEquals("FILE_EDIT", DispatchLiveActivityService.toolActionType("Edit"));
        assertEquals("FILE_EDIT", DispatchLiveActivityService.toolActionType("Write"));
        assertEquals("COMMAND", DispatchLiveActivityService.toolActionType("Bash"));
        assertEquals("MCP_CALL", DispatchLiveActivityService.toolActionType("mcp__autowonder__get_workitem"));
        assertEquals("SKILL", DispatchLiveActivityService.toolActionType("Skill"));
        assertEquals("SUBAGENT", DispatchLiveActivityService.toolActionType("Agent"));
        assertEquals("TOOL", DispatchLiveActivityService.toolActionType("UnknownTool"));
        assertEquals("TOOL", DispatchLiveActivityService.toolActionType(null));
    }

    @Test
    void actionStatusMapsCorrectly() {
        assertEquals("FAILED", DispatchLiveActivityService.actionStatus("bash.failed", new JSONObject(), "exit 1"));
        assertEquals("FAILED", DispatchLiveActivityService.actionStatus("step.failed", new JSONObject(), null));
        assertEquals("RUNNING", DispatchLiveActivityService.actionStatus("step.started", new JSONObject(), null));
        assertEquals("COMPLETED", DispatchLiveActivityService.actionStatus("bash.completed", new JSONObject(), null));
        assertEquals("PAUSED", DispatchLiveActivityService.actionStatus("session.interrupted", new JSONObject(), null));
        assertEquals("RESUMED", DispatchLiveActivityService.actionStatus("session.resumed", new JSONObject(), null));
        assertEquals("CANCELLED", DispatchLiveActivityService.actionStatus("step.cancelled", new JSONObject(), null));
        assertEquals("RUNNING", DispatchLiveActivityService.actionStatus("step.progress", new JSONObject(), null));
        assertEquals("INFO", DispatchLiveActivityService.actionStatus("repo.status", new JSONObject(), null));
    }

    @Test
    void actionStatusFromDetailField() {
        JSONObject detail = new JSONObject();
        detail.put("status", "failed");
        assertEquals("FAILED", DispatchLiveActivityService.actionStatus("step.progress", detail, null));

        detail.put("status", "paused");
        assertEquals("PAUSED", DispatchLiveActivityService.actionStatus("step.progress", detail, null));
    }

    @Test
    void isDisplayableFiltersCorrectly() {
        assertTrue(DispatchLiveActivityService.isDisplayable("step.started"));
        assertTrue(DispatchLiveActivityService.isDisplayable("bash.completed"));
        assertTrue(DispatchLiveActivityService.isDisplayable("completion_requested"));
        assertFalse(DispatchLiveActivityService.isDisplayable("agent.message"));
        assertFalse(DispatchLiveActivityService.isDisplayable("llm.completion"));
        assertFalse(DispatchLiveActivityService.isDisplayable("guidance.human"));
        assertFalse(DispatchLiveActivityService.isDisplayable("comment.added"));
        assertFalse(DispatchLiveActivityService.isDisplayable(null));
        assertFalse(DispatchLiveActivityService.isDisplayable(""));
    }

    @Test
    void handoffEventsAreDisplayableAndCategorized() {
        assertTrue(DispatchLiveActivityService.isDisplayable("handoff.submitted"));
        assertEquals("HANDOFF", DispatchLiveActivityService.actionType("handoff.submitted", new JSONObject()));
        assertEquals("已提交交接", DispatchLiveActivityService.label("HANDOFF", "handoff.submitted"));
        assertEquals("交接已接收", DispatchLiveActivityService.label("HANDOFF", "handoff.accepted"));
        assertEquals("交接处理", DispatchLiveActivityService.label("HANDOFF", "handoff.unknown"));
    }

    @Test
    void chainOfThoughtEventTypesAreDenied() {
        assertFalse(DispatchLiveActivityService.isDisplayable("agent.thinking"));
        assertFalse(DispatchLiveActivityService.isDisplayable("turn.reasoning"));
        assertFalse(DispatchLiveActivityService.isDisplayable("agent.chain_of_thought"));
        assertNull(DispatchLiveActivityService.actionType("agent.thinking", new JSONObject()));
        assertNull(DispatchLiveActivityService.actionType("turn.reasoning", new JSONObject()));
        // A normal reasoning-turn lifecycle event without a chain-of-thought marker stays visible.
        assertTrue(DispatchLiveActivityService.isDisplayable("turn.completed"));
        assertEquals("MODEL_TURN", DispatchLiveActivityService.actionType("turn.completed", new JSONObject()));
    }

    @Test
    void toActionReturnsNullForDeniedEvents() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        DispatchRuntimeEventDO event = buildEvent("agent.message", 1L, "secret");
        assertNull(service.toAction(dispatch, event));
    }

    @Test
    void toActionSanitizesSensitiveSummary() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        DispatchRuntimeEventDO event = buildEvent("bash.completed", 1L, "token=sk-abcdefghijklmnopqrstuvwxyz123456");
        DispatchLiveActivityVO.Action action = service.toAction(dispatch, event);
        assertNotNull(action);
        assertFalse(action.getSummary().contains("sk-abcdefghijklmnopqrstuvwxyz123456"));
    }

    @Test
    void labelReturnsChineseDescriptions() {
        assertEquals("准备任务包与上下文", DispatchLiveActivityService.label("CONTEXT_PREPARE", "package.loaded"));
        assertEquals("准备工作仓库", DispatchLiveActivityService.label("REPO_PREPARE", "repo.cloned"));
        assertEquals("执行命令", DispatchLiveActivityService.label("COMMAND", "bash.started"));
        assertEquals("开始执行", DispatchLiveActivityService.label("DISPATCH", "dispatch.started"));
        assertEquals("检索或读取代码", DispatchLiveActivityService.label("SEARCH_READ", "agent.tool_use"));
    }

    @Test
    void loadedCapabilitiesAreCompletedPreparationRatherThanRunningCalls() {
        DispatchDO dispatch = buildDispatch(1L, 100L, 1001L);
        String[][] cases = {
                {"skill.loaded", "SKILL_LOAD", "已加载 Skill"},
                {"plugin.loaded", "PLUGIN_LOAD", "已加载 Plugin"},
                {"mcp.loaded", "MCP_LOAD", "已加载 MCP 服务"},
                {"skill.invoked", "SKILL", "调用 Skill"},
                {"mcp.call", "MCP_CALL", "调用 MCP 工具"}
        };
        for (String[] testCase : cases) {
            DispatchRuntimeEventDO event = buildEvent(testCase[0], 1L, null);
            event.setDetailJson("{\"name\":\"example\"}");
            DispatchLiveActivityVO.Action action = service.toAction(dispatch, event);
            assertEquals(testCase[1], action.getActionType(), testCase[0]);
            assertEquals(testCase[2] + " · example", action.getSummary(), testCase[0]);
            assertEquals(testCase[0].endsWith(".loaded") ? "COMPLETED" : "RUNNING", action.getStatus());
        }
        for (String tool : List.of("Skill", "mcp__autowonder__get_workitem")) {
            DispatchRuntimeEventDO event = buildEvent("agent.tool_use", 2L, null);
            JSONObject detail = new JSONObject();
            detail.put("tool", tool);
            event.setDetailJson(detail.toJSONString());
            DispatchLiveActivityVO.Action action = service.toAction(dispatch, event);
            assertEquals(tool.equals("Skill") ? "SKILL" : "MCP_CALL", action.getActionType());
            assertEquals("RUNNING", action.getStatus());
        }
    }

    private DispatchDO buildDispatch(long id, long tenantId, long workitemId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        dispatch.setWorkitemId(workitemId);
        dispatch.setAgentId(5L);
        dispatch.setAttempt(1);
        dispatch.setStatus("RUNNING");
        dispatch.setSourceType("WORKITEM");
        return dispatch;
    }

    private DispatchRuntimeEventDO buildEvent(String eventType, long seq, String message) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setEventId("evt-" + seq);
        event.setSeq(seq);
        event.setEventType(eventType);
        event.setMessage(message);
        event.setEventTime(new Date());
        event.setStepName("编码实现");
        return event;
    }
}
