package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.dispatch.dto.RuntimeActivityTimelineVO;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        when(eventDao.listByDispatchInArrivalOrder(1L, 44L)).thenReturn(List.of(source));

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
        when(eventDao.listByDispatchInArrivalOrder(1L, 45L)).thenReturn(List.of(
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
        when(eventDao.listByDispatchInArrivalOrder(1L, 48L)).thenReturn(List.of(
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
        when(eventDao.listByDispatchInArrivalOrder(1L, 46L)).thenReturn(List.of(
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
        when(eventDao.listByDispatchInArrivalOrder(1L, 47L)).thenReturn(List.of(
                event(1, "session.resumed", "2026-07-30T10:00:00Z",
                        "{\"sessionId\":\"s1\",\"parentSessionId\":\"s1\"}")));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 47L);

        assertEquals(1, trace.getSessions().size());
        assertNull(trace.getSessions().get(0).getParentSessionId());
        assertEquals("RUNNING", trace.getSessions().get(0).getStatus());
        assertEquals("RESUMED", trace.getSessions().get(0).getBoundaries().get(0).getKind());
    }

    @Test
    void mergesConsecutiveMessagesFromOneStreamAndExcludesToolInputs() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(49L)).thenReturn(dispatch(49L, 1L));
        DispatchRuntimeEventDO firstMessage = event(1, "agent.message", "2026-07-30T10:00:00Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"hello \"}");
        firstMessage.setGmtCreate(Date.from(Instant.parse("2026-07-30T10:10:00Z")));
        DispatchRuntimeEventDO secondMessage = event(2, "agent.message", "2026-07-30T10:00:01Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\"}");
        secondMessage.setMessage("world");
        DispatchRuntimeEventDO toolCall = event(3, "bash.call", "2026-07-30T10:00:02Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"tool\",\"input\":{\"command\":\"cat /secrets\",\"token\":\"Bearer raw\"}}");
        DispatchRuntimeEventDO messageAfterTool = event(4, "agent.message", "2026-07-30T10:00:03Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"after tool\"}");
        messageAfterTool.setEventTime(null);
        messageAfterTool.setGmtCreate(Date.from(Instant.parse("2026-07-30T10:00:04Z")));
        when(eventDao.listByDispatchInArrivalOrder(1L, 49L)).thenReturn(
                List.of(firstMessage, secondMessage, toolCall, messageAfterTool));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 49L);

        assertEquals(2, timeline.getActivities().size());
        RuntimeActivityTimelineVO.Activity merged = timeline.getActivities().get(0);
        assertEquals(1L, merged.getSeq());
        assertEquals("2026-07-30T10:00:00Z", merged.getEventTime());
        assertEquals("agent.message", merged.getEventType());
        assertEquals("INFO", merged.getLevel());
        assertEquals("hello world", merged.getContent());
        RuntimeActivityTimelineVO.Activity afterTool = timeline.getActivities().get(1);
        assertEquals("2026-07-30T10:00:04Z", afterTool.getEventTime());
        assertEquals("after tool", afterTool.getContent());
        assertTrue(timeline.getActivities().stream().noneMatch(activity -> activity.getContent().contains("Bearer raw")));
        assertTrue(Arrays.stream(RuntimeActivityTimelineVO.class.getDeclaredFields())
                .map(field -> field.getName())
                .noneMatch(name -> "changed".equals(name) || "lastSeq".equals(name)));
        assertTrue(Arrays.stream(RuntimeActivityTimelineVO.Activity.class.getDeclaredFields())
                .map(field -> field.getName())
                .noneMatch(name -> "detailJson".equals(name) || "input".equals(name)));
    }

    @Test
    void prefersStoredErrorOverFailureDetailReason() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(50L)).thenReturn(dispatch(50L, 1L));
        DispatchRuntimeEventDO failed = event(1, "step.failed", "2026-07-30T10:00:00Z",
                "{\"reason\":\"detail failure reason\",\"error\":\"detail error\",\"message\":\"detail message\"}");
        failed.setError("stored error");
        failed.setMessage("stored message");
        when(eventDao.listByDispatchInArrivalOrder(1L, 50L)).thenReturn(List.of(failed));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 50L);

        assertEquals(1, timeline.getActivities().size());
        RuntimeActivityTimelineVO.Activity activity = timeline.getActivities().get(0);
        assertEquals("ERROR", activity.getLevel());
        assertEquals("step.failed", activity.getEventType());
        assertEquals("stored error", activity.getContent());
    }

    @Test
    void includesFailureFallbacksButSkipsTextlessAndNonFailureReasons() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(51L)).thenReturn(dispatch(51L, 1L));
        DispatchRuntimeEventDO reasonFallback = event(1, "step.failed", "2026-07-30T10:00:00Z", "{\"reason\":\"reason fallback\"}");
        DispatchRuntimeEventDO errorFallback = event(2, "session.failed", "2026-07-30T10:00:01Z", "{\"error\":\"error fallback\"}");
        DispatchRuntimeEventDO messageFallback = event(3, "dispatch.failed", "2026-07-30T10:00:02Z", "{}");
        messageFallback.setMessage("stored message fallback");
        DispatchRuntimeEventDO textlessFailure = event(4, "session.failed", "2026-07-30T10:00:03Z", "{}");
        DispatchRuntimeEventDO paused = event(5, "session.interrupted", "2026-07-30T10:00:04Z", "{\"reason\":\"paused\"}");
        when(eventDao.listByDispatchInArrivalOrder(1L, 51L)).thenReturn(List.of(
                reasonFallback, errorFallback, messageFallback, textlessFailure, paused));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 51L);

        assertEquals(3, timeline.getActivities().size());
        assertEquals("reason fallback", timeline.getActivities().get(0).getContent());
        assertEquals("error fallback", timeline.getActivities().get(1).getContent());
        assertEquals("stored message fallback", timeline.getActivities().get(2).getContent());
        assertTrue(timeline.getActivities().stream().allMatch(activity -> "ERROR".equals(activity.getLevel())));
    }

    @Test
    void returnsArrivalOrderedCompleteSnapshotsAroundNullSequenceFailover() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(52L)).thenReturn(dispatch(52L, 1L));
        DispatchRuntimeEventDO firstMessage = event(10, "agent.message", "2026-07-30T10:00:00Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"first message\"}");
        DispatchRuntimeEventDO failover = event(0, "dispatch.executor_failover", "2026-07-30T10:00:01Z", "{}");
        failover.setSeq(null);
        failover.setError("Runtime switching failed");
        DispatchRuntimeEventDO secondMessage = event(11, "agent.message", "2026-07-30T10:00:02Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"second message\"}");
        when(eventDao.listByDispatchInArrivalOrder(1L, 52L)).thenReturn(
                List.of(firstMessage), List.of(firstMessage, failover, secondMessage));

        RuntimeTraceService service = new RuntimeTraceService(dispatchDao, eventDao);
        RuntimeActivityTimelineVO firstSnapshot = service.getActivities(1L, 52L);
        RuntimeActivityTimelineVO secondSnapshot = service.getActivities(1L, 52L);

        assertEquals(1, firstSnapshot.getActivities().size());
        assertEquals("first message", firstSnapshot.getActivities().get(0).getContent());
        assertEquals(3, secondSnapshot.getActivities().size());
        assertEquals("first message", secondSnapshot.getActivities().get(0).getContent());
        assertEquals("ERROR", secondSnapshot.getActivities().get(1).getLevel());
        assertEquals("Runtime switching failed", secondSnapshot.getActivities().get(1).getContent());
        assertEquals("second message", secondSnapshot.getActivities().get(2).getContent());
        org.mockito.Mockito.verify(eventDao, org.mockito.Mockito.times(2))
                .listByDispatchInArrivalOrder(1L, 52L);
    }

    @Test
    void skipsStructuredDetailValuesToAvoidRawPayloadLeaks() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(53L)).thenReturn(dispatch(53L, 1L));
        when(eventDao.listByDispatchInArrivalOrder(1L, 53L)).thenReturn(List.of(
                event(1, "agent.message", "2026-07-30T10:00:00Z",
                        "{\"content\":{\"input\":{\"token\":\"Bearer raw\"}}}"),
                event(2, "step.failed", "2026-07-30T10:00:01Z",
                        "{\"reason\":{\"prompt\":\"full secret prompt\"}}}")));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 53L);

        assertTrue(timeline.getActivities().isEmpty());
    }

    @Test
    void rejectsJsonStringsFromActivityCandidatesButKeepsPlainText() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(63L)).thenReturn(dispatch(63L, 1L));
        DispatchRuntimeEventDO storedJsonMessage = event(1, "agent.message", "2026-07-30T10:00:00Z", "{}");
        storedJsonMessage.setMessage("{\"token\":\"stored message leak\"}");
        DispatchRuntimeEventDO detailJsonContent = event(2, "agent.message", "2026-07-30T10:00:01Z",
                "{\"content\":\"[\\\"detail message leak\\\"]\"}");
        DispatchRuntimeEventDO structuredFailure = event(3, "step.failed", "2026-07-30T10:00:02Z",
                "{\"reason\":\"{\\\"reason\\\":\\\"detail reason leak\\\"}\",\"error\":\"[\\\"detail error leak\\\"]\",\"message\":\"{\\\"message\\\":\\\"detail message leak\\\"}\"}");
        structuredFailure.setError("{\"error\":\"stored error leak\"}");
        structuredFailure.setMessage("[\"stored message leak\"]");
        DispatchRuntimeEventDO plainMessage = event(4, "agent.message", "2026-07-30T10:00:03Z", "{}");
        plainMessage.setMessage("plain stored message");
        DispatchRuntimeEventDO plainDetailMessage = event(5, "agent.message", "2026-07-30T10:00:04Z",
                "{\"content\":\"plain detail message\"}");
        DispatchRuntimeEventDO plainFailure = event(6, "step.failed", "2026-07-30T10:00:05Z", "{}");
        plainFailure.setError("plain stored error");
        when(eventDao.listByDispatchInArrivalOrder(1L, 63L)).thenReturn(List.of(
                storedJsonMessage, detailJsonContent, structuredFailure,
                plainMessage, plainDetailMessage, plainFailure));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 63L);

        assertEquals(List.of("plain stored message", "plain detail message", "plain stored error"),
                timeline.getActivities().stream().map(RuntimeActivityTimelineVO.Activity::getContent).toList());
        assertTrue(timeline.getActivities().stream().noneMatch(activity -> activity.getContent().contains("leak")));
    }

    @Test
    void reloadsCompleteActivitiesWhenAnUpsertUpdatesTheSamePersistedEvent() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(64L)).thenReturn(dispatch(64L, 1L));
        DispatchRuntimeEventDO beforeUpdate = event(1, "agent.message", "2026-07-30T10:00:00Z",
                "{\"content\":\"first detail\"}");
        beforeUpdate.setId(99L);
        beforeUpdate.setEventId("64:1");
        beforeUpdate.setMessage("first message");
        DispatchRuntimeEventDO afterUpdate = event(1, "agent.message", "2026-07-30T10:00:00Z",
                "{\"content\":\"updated detail\"}");
        afterUpdate.setId(99L);
        afterUpdate.setEventId("64:1");
        afterUpdate.setMessage("updated message");
        afterUpdate.setError("updated error");
        when(eventDao.listByDispatchInArrivalOrder(1L, 64L)).thenReturn(
                List.of(beforeUpdate), List.of(afterUpdate));

        RuntimeTraceService service = new RuntimeTraceService(dispatchDao, eventDao);
        RuntimeActivityTimelineVO firstSnapshot = service.getActivities(1L, 64L);
        RuntimeActivityTimelineVO secondSnapshot = service.getActivities(1L, 64L);

        assertEquals("first detail", firstSnapshot.getActivities().get(0).getContent());
        assertEquals("updated error", secondSnapshot.getActivities().get(0).getContent());
        assertEquals("ERROR", secondSnapshot.getActivities().get(0).getLevel());
        org.mockito.Mockito.verify(eventDao, org.mockito.Mockito.times(2))
                .listByDispatchInArrivalOrder(1L, 64L);
    }

    @Test
    void doesNotMergeMessagesAcrossTextlessDifferentStream() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(54L)).thenReturn(dispatch(54L, 1L));
        when(eventDao.listByDispatchInArrivalOrder(1L, 54L)).thenReturn(List.of(
                event(1, "agent.message", "2026-07-30T10:00:00Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"first\"}"),
                event(2, "agent.message", "2026-07-30T10:00:01Z",
                        "{\"sessionId\":\"s2\",\"turnId\":\"t2\",\"spanId\":\"llm\"}"),
                event(3, "agent.message", "2026-07-30T10:00:02Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"second\"}")));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 54L);

        assertEquals(2, timeline.getActivities().size());
        assertEquals("first", timeline.getActivities().get(0).getContent());
        assertEquals("second", timeline.getActivities().get(1).getContent());
    }

    @Test
    void usesStoredMessageWhenMalformedDetailJsonCannotProvideContent() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(55L)).thenReturn(dispatch(55L, 1L));
        DispatchRuntimeEventDO message = event(1, "agent.message", "2026-07-30T10:00:00Z", "{not valid json");
        message.setMessage("stored readable message");
        when(eventDao.listByDispatchInArrivalOrder(1L, 55L)).thenReturn(List.of(message));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 55L);

        assertEquals("stored readable message", timeline.getActivities().get(0).getContent());
    }

    @Test
    void retainsExistingTraceEventTimeWhenOnlyCreationTimeExists() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(56L)).thenReturn(dispatch(56L, 1L));
        DispatchRuntimeEventDO source = event(1, "session.started", "2026-07-30T10:00:00Z", "{\"sessionId\":\"s1\"}");
        source.setEventTime(null);
        source.setGmtCreate(Date.from(Instant.parse("2026-07-30T10:00:00Z")));
        when(eventDao.listByDispatchInArrivalOrder(1L, 56L)).thenReturn(List.of(source));

        RuntimeTraceVO trace = new RuntimeTraceService(dispatchDao, eventDao).get(1L, 56L);

        assertNull(trace.getEvents().get(0).getEventTime());
    }

    @Test
    void rejectsTimelineRequestsForAnotherTenant() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(57L)).thenReturn(dispatch(57L, 2L));

        assertThrows(RuntimeException.class,
                () -> new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 57L));
    }

    @Test
    void projectsStoredErrorsFromAgentMessageEvents() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(58L)).thenReturn(dispatch(58L, 1L));
        DispatchRuntimeEventDO source = event(1, "agent.message", "2026-07-30T10:00:00Z",
                "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\"}");
        source.setError("stored provider error");
        when(eventDao.listByDispatchInArrivalOrder(1L, 58L)).thenReturn(List.of(source));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 58L);

        assertEquals(1, timeline.getActivities().size());
        assertEquals("ERROR", timeline.getActivities().get(0).getLevel());
        assertEquals("stored provider error", timeline.getActivities().get(0).getContent());
    }

    @Test
    void keepsMessagesWithAnyMissingStreamIdentifierSeparate() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(59L)).thenReturn(dispatch(59L, 1L));
        when(eventDao.listByDispatchInArrivalOrder(1L, 59L)).thenReturn(List.of(
                event(1, "agent.message", "2026-07-30T10:00:00Z",
                        "{\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"missing-session-first\"}"),
                event(2, "agent.message", "2026-07-30T10:00:01Z",
                        "{\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"missing-session-second\"}"),
                event(3, "agent.message", "2026-07-30T10:00:02Z",
                        "{\"sessionId\":\"s2\",\"spanId\":\"llm\",\"content\":\"missing-turn-first\"}"),
                event(4, "agent.message", "2026-07-30T10:00:03Z",
                        "{\"sessionId\":\"s2\",\"spanId\":\"llm\",\"content\":\"missing-turn-second\"}"),
                event(5, "agent.message", "2026-07-30T10:00:04Z",
                        "{\"sessionId\":\"s3\",\"turnId\":\"t3\",\"content\":\"missing-span-first\"}"),
                event(6, "agent.message", "2026-07-30T10:00:05Z",
                        "{\"sessionId\":\"s3\",\"turnId\":\"t3\",\"content\":\"missing-span-second\"}")));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 59L);

        assertEquals(6, timeline.getActivities().size());
        assertEquals("missing-session-first", timeline.getActivities().get(0).getContent());
        assertEquals("missing-session-second", timeline.getActivities().get(1).getContent());
        assertEquals("missing-turn-first", timeline.getActivities().get(2).getContent());
        assertEquals("missing-turn-second", timeline.getActivities().get(3).getContent());
        assertEquals("missing-span-first", timeline.getActivities().get(4).getContent());
        assertEquals("missing-span-second", timeline.getActivities().get(5).getContent());
    }

    @Test
    void keepsCompletePendingMessageWhenAnEmptyMessageSharesItsStream() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(60L)).thenReturn(dispatch(60L, 1L));
        when(eventDao.listByDispatchInArrivalOrder(1L, 60L)).thenReturn(List.of(
                event(1, "agent.message", "2026-07-30T10:00:00Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"first\"}"),
                event(2, "agent.message", "2026-07-30T10:00:01Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\"}"),
                event(3, "agent.message", "2026-07-30T10:00:02Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"second\"}")));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 60L);

        assertEquals(1, timeline.getActivities().size());
        assertEquals("firstsecond", timeline.getActivities().get(0).getContent());
    }

    @Test
    void clearsPendingMessageWhenAnEmptyMessageHasAnIncompleteStream() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(61L)).thenReturn(dispatch(61L, 1L));
        when(eventDao.listByDispatchInArrivalOrder(1L, 61L)).thenReturn(List.of(
                event(1, "agent.message", "2026-07-30T10:00:00Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"first\"}"),
                event(2, "agent.message", "2026-07-30T10:00:01Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\"}"),
                event(3, "agent.message", "2026-07-30T10:00:02Z",
                        "{\"sessionId\":\"s1\",\"turnId\":\"t1\",\"spanId\":\"llm\",\"content\":\"second\"}")));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 61L);

        assertEquals(2, timeline.getActivities().size());
        assertEquals("first", timeline.getActivities().get(0).getContent());
        assertEquals("second", timeline.getActivities().get(1).getContent());
    }

    @Test
    void resolvesFailureContentInStrictPriorityOrderAndSkipsTextlessFailures() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(62L)).thenReturn(dispatch(62L, 1L));
        DispatchRuntimeEventDO storedError = event(1, "step.failed", "2026-07-30T10:00:00Z",
                "{\"reason\":\"detail reason\",\"error\":\"detail error\",\"message\":\"detail message\"}");
        storedError.setError("stored error");
        storedError.setMessage("stored message");
        DispatchRuntimeEventDO detailReason = event(2, "step.failed", "2026-07-30T10:00:01Z",
                "{\"reason\":\"detail reason\",\"error\":\"detail error\",\"message\":\"detail message\"}");
        detailReason.setMessage("stored message");
        DispatchRuntimeEventDO detailError = event(3, "step.failed", "2026-07-30T10:00:02Z",
                "{\"error\":\"detail error\",\"message\":\"detail message\"}");
        detailError.setMessage("stored message");
        DispatchRuntimeEventDO storedMessage = event(4, "step.failed", "2026-07-30T10:00:03Z",
                "{\"message\":\"detail message\"}");
        storedMessage.setMessage("stored message");
        DispatchRuntimeEventDO detailMessage = event(5, "step.failed", "2026-07-30T10:00:04Z",
                "{\"message\":\"detail message\"}");
        DispatchRuntimeEventDO textlessFailure = event(6, "step.failed", "2026-07-30T10:00:05Z", "{}");
        when(eventDao.listByDispatchInArrivalOrder(1L, 62L)).thenReturn(List.of(
                storedError, detailReason, detailError, storedMessage, detailMessage, textlessFailure));

        RuntimeActivityTimelineVO timeline = new RuntimeTraceService(dispatchDao, eventDao).getActivities(1L, 62L);

        assertEquals(5, timeline.getActivities().size());
        assertEquals("stored error", timeline.getActivities().get(0).getContent());
        assertEquals("detail reason", timeline.getActivities().get(1).getContent());
        assertEquals("detail error", timeline.getActivities().get(2).getContent());
        assertEquals("stored message", timeline.getActivities().get(3).getContent());
        assertEquals("detail message", timeline.getActivities().get(4).getContent());
        assertTrue(timeline.getActivities().stream().noneMatch(activity -> "45:6".equals(activity.getEventId())));
    }

    private static DispatchDO dispatch(long id, long tenantId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        return dispatch;
    }

    private static DispatchRuntimeEventDO event(long seq, String type, String time, String detailJson) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setId(seq);
        event.setEventId("45:" + seq);
        event.setSeq(seq);
        event.setEventType(type);
        event.setEventTime(Date.from(Instant.parse(time)));
        event.setDetailJson(detailJson);
        return event;
    }

    @Test
    void failoverRerunAdvancesChangedCursorAndKeepsArrivalOrder() {
        // Two executor runs share one dispatch: run 1 numbers seq 500, the failover re-run
        // restarts seq at 1. The changed cursor and event order must follow the arrival id.
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchRuntimeEventDao eventDao = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(65L)).thenReturn(dispatch(65L, 1L));
        DispatchRuntimeEventDO run1 = eventWithId(11L, 500L, "session.started", "2026-07-30T10:00:00Z",
                "{\"sessionId\":\"s1\"}");
        DispatchRuntimeEventDO run2First = eventWithId(12L, 1L, "session.resumed", "2026-07-30T10:20:00Z",
                "{\"sessionId\":\"s2\"}");
        DispatchRuntimeEventDO run2Second = eventWithId(13L, 2L, "turn.started", "2026-07-30T10:20:01Z",
                "{\"sessionId\":\"s2\",\"turnId\":\"t1\"}");
        when(eventDao.listByDispatchInArrivalOrder(1L, 65L))
                .thenReturn(List.of(run1), List.of(run1, run2First, run2Second));

        RuntimeTraceService service = new RuntimeTraceService(dispatchDao, eventDao);
        RuntimeTraceVO first = service.get(1L, 65L);
        assertEquals(11L, first.getLastSeq());

        RuntimeTraceVO rerun = service.get(1L, 65L, first.getLastSeq());
        assertTrue(rerun.isChanged());
        assertEquals(13L, rerun.getLastSeq());
        assertEquals(List.of(11L, 12L, 13L),
                rerun.getEvents().stream().map(RuntimeTraceVO.Event::getSeq).toList());
    }

    private static DispatchRuntimeEventDO eventWithId(long id, long seq, String type, String time, String detailJson) {
        DispatchRuntimeEventDO event = event(seq, type, time, detailJson);
        event.setId(id);
        return event;
    }
}
