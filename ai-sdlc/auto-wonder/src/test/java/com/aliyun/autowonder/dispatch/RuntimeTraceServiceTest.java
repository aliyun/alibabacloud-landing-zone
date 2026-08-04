package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeTraceServiceTest {

    @Test
    void projectsStoredEventsWithoutAnotherTraceTable() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(44L);
        dispatch.setTenantId(1L);
        when(dispatchDao.findById(44L)).thenReturn(dispatch);
        DispatchRuntimeEventDO source = new DispatchRuntimeEventDO();
        source.setEventId("44:7");
        source.setSeq(7L);
        source.setEventType("bash.call");
        source.setDetailJson("{\"runtimeId\":\"rt1\",\"provider\":\"codex\",\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"span1\"}");
        when(eventDao.listByDispatch(1L, 44L)).thenReturn(List.of(source));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 44L);

        assertEquals(44L, trace.getDispatchId());
        assertEquals("44:7", trace.getEvents().get(0).getEventId());
        assertEquals("t1", trace.getEvents().get(0).getDetail().get("turnId"));
        assertEquals("rt1", trace.getRuntimeId());
        assertEquals("s1", trace.getSessions().get(0).getSessionId());
        assertEquals("t1", trace.getSessions().get(0).getTurns().get(0).getTurnId());
        assertEquals("span1", trace.getSessions().get(0).getTurns().get(0).getSpans().get(0).getSpanId());
    }

    @Test
    void foldsSessionTurnsToolsAndUsageWithoutDoubleCounting() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        DispatchDO dispatch = dispatch(45L, 1L);
        when(dispatchDao.findById(45L)).thenReturn(dispatch);
        when(eventDao.listByDispatch(1L, 45L)).thenReturn(List.of(
                event(1, "step.started", "2026-07-30T10:00:00Z", "{\"stepId\":\"implementation\",\"stepName\":\"Implementation\"}"),
                event(2, "session.started", "2026-07-30T10:00:01Z", "{\"runtimeId\":\"rt1\",\"provider\":\"codex\",\"sessionId\":\"s1\"}"),
                event(3, "turn.started", "2026-07-30T10:00:02Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"t1:llm\"}"),
                event(4, "llm.started", "2026-07-30T10:00:02Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"t1:llm\",\"model\":\"gpt-5\"}"),
                event(5, "bash.call", "2026-07-30T10:00:03Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"call-1\",\"callId\":\"call-1\",\"tool\":\"bash\",\"inputSummary\":\"pnpm test\"}"),
                event(6, "bash.result", "2026-07-30T10:00:05Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"call-1\",\"callId\":\"call-1\",\"tool\":\"bash\",\"status\":\"ok\",\"durationMs\":1500,\"outputSummary\":\"26 passed\"}"),
                event(7, "llm.usage", "2026-07-30T10:00:06Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"t1:llm\",\"model\":\"gpt-5\",\"inputTokens\":1200,\"outputTokens\":300,\"reasoningTokens\":80,\"cacheReadTokens\":400}"),
                event(8, "llm.completed", "2026-07-30T10:00:07Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"t1:llm\",\"durationMs\":5000}"),
                event(9, "turn.completed", "2026-07-30T10:00:08Z", "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"t1:llm\",\"durationMs\":6000}"),
                event(10, "session.interrupted", "2026-07-30T10:00:09Z", "{\"sessionId\":\"s1\",\"reason\":\"paused\",\"checkpointSeq\":18}"),
                event(11, "session.resumed", "2026-07-30T10:01:00Z", "{\"sessionId\":\"s1\",\"mode\":\"resume\"}"),
                event(12, "turn.started", "2026-07-30T10:01:01Z", "{\"sessionId\":\"s1\",\"turnId\":\"t2\",\"spanId\":\"t2:llm\"}"),
                event(13, "llm.usage", "2026-07-30T10:01:04Z", "{\"sessionId\":\"s1\",\"turnId\":\"t2\",\"spanId\":\"t2:llm\",\"inputTokens\":500,\"outputTokens\":100}"),
                event(14, "turn.interrupted", "2026-07-30T10:01:05Z", "{\"sessionId\":\"s1\",\"turnId\":\"t2\",\"spanId\":\"t2:llm\",\"durationMs\":4000}"),
                event(15, "session.interrupted", "2026-07-30T10:01:06Z", "{\"sessionId\":\"s1\",\"reason\":\"paused\"}")));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 45L, null);

        assertTrue(trace.isChanged());
        assertEquals(15L, trace.getLastSeq());
        assertEquals(2100L, trace.getTokenUsage().getTotalTokens());
        assertTrue(trace.getTokenUsage().isAvailable());
        assertEquals(1700L, trace.getTokenUsage().getInputTokens());
        assertEquals(400L, trace.getTokenUsage().getOutputTokens());
        assertEquals(80L, trace.getTokenUsage().getReasoningTokens());
        RuntimeTraceVO.Session session = trace.getSessions().get(0);
        assertEquals("INTERRUPTED", session.getStatus());
        assertEquals(2, session.getTurns().size());
        assertEquals(65_000L, session.getDurationMs());
        assertEquals(2100L, session.getTokenUsage().getTotalTokens());
        RuntimeTraceVO.Turn first = session.getTurns().get(0);
        assertEquals("implementation", first.getStepId());
        assertEquals("Implementation", first.getStepName());
        assertEquals("COMPLETED", first.getStatus());
        assertEquals(6000L, first.getDurationMs());
        assertEquals(1500L, first.getTokenUsage().getTotalTokens());
        RuntimeTraceVO.Span bash = first.getSpans().stream()
                .filter(span -> "BASH".equals(span.getKind())).findFirst().orElseThrow();
        assertEquals("COMPLETED", bash.getStatus());
        assertEquals(1500L, bash.getDurationMs());
        assertEquals("pnpm test", bash.getInputSummary());
        assertEquals("26 passed", bash.getOutputSummary());
        assertEquals(2, session.getBoundaries().stream()
                .filter(boundary -> "INTERRUPTED".equals(boundary.getKind())).count());
        assertEquals(1, session.getBoundaries().stream()
                .filter(boundary -> "RESUMED".equals(boundary.getKind())).count());
    }

    @Test
    void preservesFullPromptsAndToolPayloadAndMarksMissingQoderUsageUnavailable() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(48L)).thenReturn(dispatch(48L, 1L));
        when(eventDao.listByDispatch(1L, 48L)).thenReturn(List.of(
                event(1, "session.started", "2026-07-30T10:00:00Z", "{\"sessionId\":\"qoder-session\",\"provider\":\"qoder\"}"),
                event(2, "turn.started", "2026-07-30T10:00:01Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"prompt\":\"full user prompt\",\"systemPrompt\":\"full system prompt\"}"),
                event(3, "bash.call", "2026-07-30T10:00:02Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"call-1\",\"tool\":\"Bash\",\"input\":{\"command\":\"pwd\",\"Authorization\":\"Bearer raw\"}}"),
                event(4, "bash.result", "2026-07-30T10:00:03Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"call-1\",\"tool\":\"Bash\",\"status\":\"completed\",\"output\":\"/workspace\\n\",\"durationMs\":1000}"),
                event(5, "llm.started", "2026-07-30T10:00:01Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"turn-1:llm\",\"model\":\"qmodel_latest\"}"),
                event(6, "agent.message", "2026-07-30T10:00:03Z", "{\"providerEvent\":true,\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"turn-1:llm\",\"content\":\"hello \"}"),
                event(7, "agent.message", "2026-07-30T10:00:03Z", "{\"providerEvent\":true,\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"turn-1:llm\",\"content\":\"world\"}"),
                event(8, "agent.tool_use", "2026-07-30T10:00:03Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"skill-1\",\"tool\":\"Skill\",\"input\":{\"skill\":\"verify\",\"args\":\"run tests\"}}"),
                event(9, "agent.tool_result", "2026-07-30T10:00:04Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"skill-1\",\"tool\":\"Skill\",\"status\":\"completed\",\"output\":\"done\",\"durationMs\":900}"),
                event(10, "agent.message", "2026-07-30T10:00:04Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"spanId\":\"internal\",\"content\":\"runtime diagnostic\"}"),
                event(11, "turn.completed", "2026-07-30T10:00:04Z", "{\"sessionId\":\"qoder-session\",\"turnId\":\"turn-1\",\"durationMs\":3000}")));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 48L);

        RuntimeTraceVO.Turn turn = trace.getSessions().get(0).getTurns().get(0);
        assertEquals(4_000L, trace.getSessions().get(0).getDurationMs());
        assertEquals("full user prompt", turn.getPrompt());
        assertEquals("full system prompt", turn.getSystemPrompt());
        assertFalse(turn.getTokenUsage().isAvailable());
        assertFalse(trace.getTokenUsage().isAvailable());
        RuntimeTraceVO.Span bash = turn.getSpans().stream().filter(span -> "BASH".equals(span.getKind())).findFirst().orElseThrow();
        assertEquals("pwd", ((java.util.Map<?, ?>) bash.getInput()).get("command"));
        assertEquals("Bearer raw", ((java.util.Map<?, ?>) bash.getInput()).get("Authorization"));
        assertEquals("/workspace\n", bash.getOutput());
        RuntimeTraceVO.Span provider = turn.getSpans().stream().filter(span -> "PROVIDER".equals(span.getKind())).findFirst().orElseThrow();
        assertEquals("hello world", provider.getContent());
        assertEquals(1, turn.getSpans().stream().filter(span -> "PROVIDER".equals(span.getKind())).count());
        RuntimeTraceVO.Span skill = turn.getSpans().stream().filter(span -> "SKILL".equals(span.getKind())).findFirst().orElseThrow();
        assertEquals("verify", skill.getName());
        assertEquals(900L, skill.getDurationMs());
        assertEquals("done", skill.getOutput());
    }

    @Test
    void returnsLightweightUnchangedResponseAfterKnownSequence() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(46L)).thenReturn(dispatch(46L, 1L));
        when(eventDao.listByDispatch(1L, 46L)).thenReturn(List.of(
                event(4, "session.started", "2026-07-30T10:00:00Z", "{\"sessionId\":\"s1\"}")));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 46L, 4L);

        assertFalse(trace.isChanged());
        assertEquals(4L, trace.getLastSeq());
        assertTrue(trace.getEvents().isEmpty());
        assertTrue(trace.getSessions().isEmpty());
    }

    @Test
    void treatsResumeOfTheSameProviderSessionAsContinuityNotFork() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(47L)).thenReturn(dispatch(47L, 1L));
        when(eventDao.listByDispatch(1L, 47L)).thenReturn(List.of(
                event(1, "session.resumed", "2026-07-30T10:00:00Z",
                        "{\"sessionId\":\"s1\",\"parentSessionId\":\"s1\"}")));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 47L);

        assertEquals(1, trace.getSessions().size());
        assertNull(trace.getSessions().get(0).getParentSessionId());
        assertEquals("RUNNING", trace.getSessions().get(0).getStatus());
        assertEquals("RESUMED", trace.getSessions().get(0).getBoundaries().get(0).getKind());
    }

    private static DispatchDO dispatch(long id, long tenantId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        return dispatch;
    }

    private static DispatchRuntimeEventDO event(long seq, String type, String time, String detailJson) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setEventId("45:" + seq);
        event.setSeq(seq);
        event.setEventType(type);
        event.setEventTime(Date.from(Instant.parse(time)));
        event.setDetailJson(detailJson);
        return event;
    }
}
