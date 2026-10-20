package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.dispatch.dto.RuntimeActivityTimelineVO;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The workitem shows every failed round of a multi-round provider failure from the
 * persisted runtime event chain. These tests pin both halves of that path: a frame's
 * nested failureHistory must survive ingestion untouched, and it must be projected
 * back to the comment card and trace drawer without losing rounds or diagnostics.
 */
class FailureHistoryPassthroughTest {

    static final String ROUND_ONE_SESSION = "689186f5-a6bd-4816-9fa1-6bff68e7b896";
    static final String ROUND_TWO_SESSION = "aa2db1e7-4256-49eb-ae7b-6faea387b7e0";
    static final String ROUND_ONE_ERROR = "qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed";
    static final String ROUND_TWO_ERROR = "qoder semantic inactivity timeout after 10m0s";
    static final String PREFLIGHT_ERROR = "workflow routing preflight failed: " + ROUND_TWO_ERROR;

    private static JSONObject incidentFailureHistory(int rounds) {
        JSONObject roundOne = new JSONObject()
                .fluentPut("phase", "routing_preflight")
                .fluentPut("attemptIndex", 1)
                .fluentPut("provider", "qoder")
                .fluentPut("model", "dmodel")
                .fluentPut("sessionId", ROUND_ONE_SESSION)
                .fluentPut("startedAt", "2026-10-14T03:20:44Z")
                .fluentPut("endedAt", "2026-10-14T03:21:08Z")
                .fluentPut("durationMs", 24)
                .fluentPut("status", "failed")
                .fluentPut("error", ROUND_ONE_ERROR)
                .fluentPut("diagnostics", new JSONArray(List.of(new JSONObject()
                        .fluentPut("timestamp", "2026-10-14T03:21:07.331Z")
                        .fluentPut("source", "provider_log")
                        .fluentPut("category", "RESOURCE_LIMIT")
                        .fluentPut("code", "10605")
                        .fluentPut("subCode", "model_queued")
                        .fluentPut("businessCode", "RESOURCE_LIMIT")
                        .fluentPut("httpStatus", 200)
                        .fluentPut("message", "Queuing failed")
                        .fluentPut("requestId", "f203f510-c793-4e82-b64d-18c879abb08c")
                        .fluentPut("serverRequestId", "e0a61fb9-725f-4b21-b9ac-ce708c58c054")
                        .fluentPut("retryable", true)
                        .fluentPut("willRetry", true)
                        .fluentPut("occurrences", 2)
                        .fluentPut("firstSeen", "2026-10-14T03:21:07.331Z")
                        .fluentPut("lastSeen", "2026-10-14T03:21:08.140Z"))));
        JSONObject history = new JSONObject().fluentPut("rounds", new JSONArray(List.of(roundOne)));
        if (rounds > 1) {
            history.getJSONArray("rounds").add(new JSONObject()
                    .fluentPut("phase", "routing_preflight")
                    .fluentPut("attemptIndex", 2)
                    .fluentPut("provider", "qoder")
                    .fluentPut("model", "dmodel")
                    .fluentPut("sessionId", ROUND_TWO_SESSION)
                    .fluentPut("status", "failed")
                    .fluentPut("error", ROUND_TWO_ERROR)
                    .fluentPut("diagnostics", new JSONArray(List.of(new JSONObject()
                            .fluentPut("source", "provider_rpc")
                            .fluentPut("category", "timeout")
                            .fluentPut("message", "first payload never arrived")
                            .fluentPut("requestId", "a3203242-026f-43ab-b92b-1ccc700155c4")
                            .fluentPut("serverRequestId", "345785b7-b5b3-4c9f-b250-a0bd39f770b3")))));
        }
        return history.fluentPut("truncated", false);
    }

    private static DispatchService dispatchService(DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao) {
        return new DispatchService(dispatchDao, runtimeEventDao,
                mock(com.aliyun.autowonder.workitem.WorkitemDao.class),
                mock(com.aliyun.autowonder.agent.AgentDao.class),
                mock(com.aliyun.autowonder.agent.AgentVersionDao.class),
                mock(ExecutorSelector.class),
                mock(PackageContextAssembler.class),
                mock(com.aliyun.autowonder.taskpackage.TaskPackager.class),
                mock(DispatchTransport.class),
                mock(SdlcDriver.class),
                null);
    }

    private static DispatchDO runningDispatch(long id) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(1L);
        dispatch.setWorkitemId(10L);
        dispatch.setAgentId(7L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        return dispatch;
    }

    @Test
    void storesEveryFailedRoundAndDiagnosticOnTheTerminalProgressEvent() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(44L)).thenReturn(runningDispatch(44L));

        JSONObject frame = new JSONObject().fluentPut("runtimeEvent", new JSONObject()
                .fluentPut("eventId", "44:9")
                .fluentPut("seq", 9L)
                .fluentPut("eventType", "dispatch.failed")
                .fluentPut("state", "failed")
                .fluentPut("error", PREFLIGHT_ERROR)
                .fluentPut("failureHistory", incidentFailureHistory(2)));

        dispatchService(dispatchDao, runtimeEventDao).onProgress(1L, 44L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> captor = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(captor.capture());
        DispatchRuntimeEventDO stored = captor.getValue();
        assertEquals("dispatch.failed", stored.getEventType());
        assertEquals(PREFLIGHT_ERROR, stored.getError());
        JSONObject history = JSON.parseObject(stored.getDetailJson()).getJSONObject("failureHistory");
        JSONArray rounds = history.getJSONArray("rounds");
        assertEquals(2, rounds.size());
        JSONObject roundOne = rounds.getJSONObject(0);
        assertEquals(ROUND_ONE_SESSION, roundOne.getString("sessionId"));
        assertEquals(ROUND_ONE_ERROR, roundOne.getString("error"));
        JSONObject diagnostic = roundOne.getJSONArray("diagnostics").getJSONObject(0);
        assertEquals("provider_log", diagnostic.getString("source"));
        assertEquals("10605", diagnostic.getString("code"));
        assertEquals("model_queued", diagnostic.getString("subCode"));
        assertEquals("RESOURCE_LIMIT", diagnostic.getString("businessCode"));
        assertEquals(200, diagnostic.getIntValue("httpStatus"));
        assertEquals("f203f510-c793-4e82-b64d-18c879abb08c", diagnostic.getString("requestId"));
        assertEquals("e0a61fb9-725f-4b21-b9ac-ce708c58c054", diagnostic.getString("serverRequestId"));
        assertEquals(2, diagnostic.getIntValue("occurrences"));
        assertEquals("2026-10-14T03:21:08.140Z", diagnostic.getString("lastSeen"));
        JSONObject roundTwo = rounds.getJSONObject(1);
        assertEquals(2, roundTwo.getIntValue("attemptIndex"));
        assertEquals(ROUND_TWO_SESSION, roundTwo.getString("sessionId"));
        assertEquals(ROUND_TWO_ERROR, roundTwo.getString("error"));
        assertEquals("provider_rpc", roundTwo.getJSONArray("diagnostics").getJSONObject(0).getString("source"));
    }

    @Test
    void storesPartialPerRoundHistoryOnIntermediateTurnFailures() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(45L)).thenReturn(runningDispatch(45L));

        JSONObject frame = new JSONObject().fluentPut("runtimeEvent", new JSONObject()
                .fluentPut("eventId", "45:4")
                .fluentPut("seq", 4L)
                .fluentPut("eventType", "turn.failed")
                .fluentPut("sessionId", ROUND_ONE_SESSION)
                .fluentPut("turnId", "45:4")
                .fluentPut("status", "failed")
                .fluentPut("errorCategory", "failed")
                .fluentPut("failureHistory", incidentFailureHistory(1)));

        dispatchService(dispatchDao, runtimeEventDao).onProgress(1L, 45L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> captor = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(captor.capture());
        JSONObject history = JSON.parseObject(captor.getValue().getDetailJson()).getJSONObject("failureHistory");
        assertEquals(1, history.getJSONArray("rounds").size());
        assertEquals(ROUND_ONE_SESSION, history.getJSONArray("rounds").getJSONObject(0).getString("sessionId"));
        assertEquals(ROUND_ONE_ERROR, history.getJSONArray("rounds").getJSONObject(0).getString("error"));
    }

    @Test
    void recordsTrailingTerminalEventAfterTheResultFrameTerminalizedTheDispatch() {
        // The ws result frame can set the row to FAILED before the runtime's trailing
        // dispatch.failed frame arrives; that frame carries the aggregate history and
        // must still reach the persisted event chain without moving the row again.
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDO terminal = runningDispatch(47L);
        terminal.setStatus(DispatchStatus.FAILED);
        terminal.setVersion(5);
        terminal.setError("terminal result already applied");
        when(dispatchDao.findById(47L)).thenReturn(terminal);

        JSONObject frame = new JSONObject().fluentPut("runtimeEvent", new JSONObject()
                .fluentPut("eventId", "47:25")
                .fluentPut("seq", 25L)
                .fluentPut("eventType", "dispatch.failed")
                .fluentPut("state", "failed")
                .fluentPut("error", PREFLIGHT_ERROR)
                .fluentPut("failureHistory", incidentFailureHistory(2)));

        dispatchService(dispatchDao, runtimeEventDao).onProgress(1L, 47L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> captor = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(captor.capture());
        DispatchRuntimeEventDO stored = captor.getValue();
        assertEquals("dispatch.failed", stored.getEventType());
        assertEquals(PREFLIGHT_ERROR, stored.getError());
        assertEquals(2, JSON.parseObject(stored.getDetailJson())
                .getJSONObject("failureHistory").getJSONArray("rounds").size());
        assertEquals(DispatchStatus.FAILED, terminal.getStatus());
        assertEquals(5, terminal.getVersion());
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void keepsIgnoringNonRuntimeFramesForTerminalDispatches() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDO terminal = runningDispatch(48L);
        terminal.setStatus(DispatchStatus.FAILED);
        when(dispatchDao.findById(48L)).thenReturn(terminal);
        DispatchService service = dispatchService(dispatchDao, runtimeEventDao);

        service.onProgress(1L, 48L, new JSONObject().fluentPut("resultSummary", "TASK_PROGRESS"));
        service.onProgress(1L, 48L, new JSONObject().fluentPut("runtimeEvent",
                new JSONObject().fluentPut("eventType", "guidance.failed").fluentPut("status", "failed")));

        verifyNoInteractions(runtimeEventDao);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void skipsUnknownDispatchesWithoutPersistingOrTransitioning() {
        // An unknown dispatchId is a silent no-op: the frame persists nothing and no
        // row moves, even when it carries a terminal failure history.
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);

        JSONObject frame = new JSONObject().fluentPut("runtimeEvent", new JSONObject()
                .fluentPut("eventId", "999:1")
                .fluentPut("seq", 1L)
                .fluentPut("eventType", "dispatch.failed")
                .fluentPut("state", "failed")
                .fluentPut("error", PREFLIGHT_ERROR)
                .fluentPut("failureHistory", incidentFailureHistory(2)));

        dispatchService(dispatchDao, runtimeEventDao).onProgress(1L, 999L, frame);

        verify(dispatchDao).findById(999L);
        verifyNoInteractions(runtimeEventDao);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void storesLegacyFailuresWithoutHistoryUnchanged() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(46L)).thenReturn(runningDispatch(46L));

        JSONObject frame = new JSONObject().fluentPut("runtimeEvent", new JSONObject()
                .fluentPut("eventId", "46:1")
                .fluentPut("eventType", "dispatch.failed")
                .fluentPut("error", "single round provider failure"));

        dispatchService(dispatchDao, runtimeEventDao).onProgress(1L, 46L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> captor = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(captor.capture());
        DispatchRuntimeEventDO stored = captor.getValue();
        assertEquals("single round provider failure", stored.getError());
        assertNull(JSON.parseObject(stored.getDetailJson()).get("failureHistory"));
    }

    @Test
    void returnsEveryFailedRoundToTheCommentCardAndTraceViews() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(100L);
        dispatch.setTenantId(1L);
        when(dispatchDao.findById(100L)).thenReturn(dispatch);
        DispatchRuntimeEventDO roundEvent = storedEvent(1, "turn.failed", "2026-10-14T03:21:08.500Z",
                new JSONObject().fluentPut("sessionId", ROUND_ONE_SESSION).fluentPut("turnId", "100:1")
                        .fluentPut("status", "failed").fluentPut("failureHistory", incidentFailureHistory(1))
                        .toJSONString());
        DispatchRuntimeEventDO terminal = storedEvent(2, "dispatch.failed", "2026-10-14T03:32:00Z",
                new JSONObject().fluentPut("state", "failed").fluentPut("error", PREFLIGHT_ERROR)
                        .fluentPut("failureHistory", incidentFailureHistory(2)).toJSONString());
        terminal.setError(PREFLIGHT_ERROR);
        when(eventDao.listByDispatch(1L, 100L)).thenReturn(List.of(roundEvent, terminal));
        when(eventDao.listByDispatchInArrivalOrder(1L, 100L)).thenReturn(List.of(roundEvent, terminal));

        RuntimeTraceService service = new RuntimeTraceService(dispatchDao, eventDao);
        RuntimeTraceVO trace = service.get(1L, 100L);

        RuntimeTraceVO.Event roundEventView = trace.getEvents().get(0);
        Map<?, ?> roundHistory = (Map<?, ?>) roundEventView.getDetail().get("failureHistory");
        assertEquals(1, ((List<?>) roundHistory.get("rounds")).size());
        RuntimeTraceVO.Event terminalView = trace.getEvents().get(1);
        assertEquals("dispatch.failed", terminalView.getEventType());
        assertEquals(PREFLIGHT_ERROR, terminalView.getDetail().get("error"));
        // The drawer lists runtime events that carry no session, so the terminal
        // failure must stay dispatch-scoped for the complete history to be visible.
        assertFalse(terminalView.getDetail().containsKey("sessionId"));
        Map<?, ?> history = (Map<?, ?>) terminalView.getDetail().get("failureHistory");
        assertInstanceOf(Map.class, history);
        List<?> rounds = (List<?>) history.get("rounds");
        assertEquals(2, rounds.size());
        Map<?, ?> roundOne = (Map<?, ?>) rounds.get(0);
        assertEquals(ROUND_ONE_SESSION, roundOne.get("sessionId"));
        assertEquals(ROUND_ONE_ERROR, roundOne.get("error"));
        Map<?, ?> diagnostic = (Map<?, ?>) ((List<?>) roundOne.get("diagnostics")).get(0);
        assertEquals("provider_log", diagnostic.get("source"));
        assertEquals("10605", diagnostic.get("code"));
        assertEquals("model_queued", diagnostic.get("subCode"));
        assertEquals("RESOURCE_LIMIT", diagnostic.get("businessCode"));
        assertEquals(2, ((Number) diagnostic.get("occurrences")).intValue());
        assertEquals("f203f510-c793-4e82-b64d-18c879abb08c", diagnostic.get("requestId"));
        assertEquals("e0a61fb9-725f-4b21-b9ac-ce708c58c054", diagnostic.get("serverRequestId"));
        assertEquals("2026-10-14T03:21:07.331Z", diagnostic.get("firstSeen"));
        assertEquals("2026-10-14T03:21:08.140Z", diagnostic.get("lastSeen"));
        Map<?, ?> roundTwo = (Map<?, ?>) rounds.get(1);
        assertEquals("routing_preflight", roundTwo.get("phase"));
        assertEquals(2, ((Number) roundTwo.get("attemptIndex")).intValue());
        assertEquals(ROUND_TWO_SESSION, roundTwo.get("sessionId"));
        assertEquals(ROUND_TWO_ERROR, roundTwo.get("error"));
        assertEquals("provider_rpc",
                ((Map<?, ?>) ((List<?>) roundTwo.get("diagnostics")).get(0)).get("source"));
        assertTrue(JSON.toJSONString(history).contains("10605"));

        RuntimeActivityTimelineVO timeline = service.getActivities(1L, 100L);
        assertEquals(1, timeline.getActivities().size());
        assertEquals("ERROR", timeline.getActivities().get(0).getLevel());
        assertEquals(PREFLIGHT_ERROR, timeline.getActivities().get(0).getContent());
    }

    @Test
    void returnsLegacyFailuresWithoutFabricatingRounds() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(101L);
        dispatch.setTenantId(1L);
        when(dispatchDao.findById(101L)).thenReturn(dispatch);
        DispatchRuntimeEventDO terminal = storedEvent(1, "dispatch.failed", "2026-10-14T03:32:00Z",
                "{\"state\":\"failed\",\"error\":\"legacy provider failure\"}");
        terminal.setError("legacy provider failure");
        when(eventDao.listByDispatch(1L, 101L)).thenReturn(List.of(terminal));
        when(eventDao.listByDispatchInArrivalOrder(1L, 101L)).thenReturn(List.of(terminal));

        RuntimeTraceService service = new RuntimeTraceService(dispatchDao, eventDao);
        RuntimeTraceVO trace = service.get(1L, 101L);

        assertNull(trace.getEvents().get(0).getDetail().get("failureHistory"));
        RuntimeActivityTimelineVO timeline = service.getActivities(1L, 101L);
        assertEquals("legacy provider failure", timeline.getActivities().get(0).getContent());
    }

    private static DispatchRuntimeEventDO storedEvent(long seq, String type, String time, String detailJson) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setEventId("100:" + seq);
        event.setSeq(seq);
        event.setEventType(type);
        event.setEventTime(Date.from(Instant.parse(time)));
        event.setDetailJson(detailJson);
        return event;
    }
}
