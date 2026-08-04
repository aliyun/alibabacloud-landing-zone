package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RuntimeTraceService {

    private final DispatchDao dispatchDao;
    private final DispatchRuntimeEventDao eventDao;

    public RuntimeTraceService(DispatchDao dispatchDao, DispatchRuntimeEventDao eventDao) {
        this.dispatchDao = dispatchDao;
        this.eventDao = eventDao;
    }

    public RuntimeTraceVO get(long tenantId, long dispatchId) {
        return get(tenantId, dispatchId, null);
    }

    public RuntimeTraceVO get(long tenantId, long dispatchId, Long afterSeq) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        List<DispatchRuntimeEventDO> sources = eventDao.listByDispatch(tenantId, dispatchId);
        long lastSeq = sources.stream().map(DispatchRuntimeEventDO::getSeq)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        RuntimeTraceVO trace = new RuntimeTraceVO();
        trace.setDispatchId(dispatchId);
        trace.setLastSeq(lastSeq);
        if (afterSeq != null && lastSeq <= afterSeq) {
            trace.setChanged(false);
            return trace;
        }

        Map<String, RuntimeTraceVO.Session> sessions = new LinkedHashMap<>();
        Map<String, RuntimeTraceVO.Turn> turns = new LinkedHashMap<>();
        Map<String, RuntimeTraceVO.Span> spans = new LinkedHashMap<>();
        Map<String, Instant> sessionFirstEvent = new LinkedHashMap<>();
        Map<String, Instant> sessionLastEvent = new LinkedHashMap<>();
        String currentStepId = null;
        String currentStepName = null;
        for (DispatchRuntimeEventDO source : sources) {
            RuntimeTraceVO.Event event = toEvent(source);
            trace.getEvents().add(event);
            String eventType = source.getEventType() == null ? "" : source.getEventType();
            if ("step.started".equals(eventType)) {
                currentStepId = first(text(event.getDetail(), "stepId"), source.getStepKey());
                currentStepName = first(text(event.getDetail(), "stepName"), source.getStepName());
            }
            if (trace.getRuntimeId() == null) {
                trace.setRuntimeId(text(event.getDetail(), "runtimeId"));
            }
            if (trace.getProvider() == null) {
                trace.setProvider(text(event.getDetail(), "provider"));
            }
            String sessionId = text(event.getDetail(), "sessionId");
            if (sessionId == null) {
                continue;
            }
            if (source.getEventTime() != null) {
                Instant eventTime = source.getEventTime().toInstant();
                sessionFirstEvent.merge(sessionId, eventTime, (left, right) -> left.isBefore(right) ? left : right);
                sessionLastEvent.merge(sessionId, eventTime, (left, right) -> left.isAfter(right) ? left : right);
            }
            RuntimeTraceVO.Session session = sessions.computeIfAbsent(sessionId, key -> {
                RuntimeTraceVO.Session created = new RuntimeTraceVO.Session();
                created.setSessionId(key);
                String parentSessionId = text(event.getDetail(), "parentSessionId");
                created.setParentSessionId(key.equals(parentSessionId) ? null : parentSessionId);
                created.setStatus("RUNNING");
                trace.getSessions().add(created);
                return created;
            });
            session.getEventIds().add(source.getEventId());
            applySessionLifecycle(session, eventType, event);

            String turnId = text(event.getDetail(), "turnId");
            if (turnId == null) {
                continue;
            }
            String stepId = currentStepId;
            String stepName = currentStepName;
            RuntimeTraceVO.Turn turn = turns.computeIfAbsent(sessionId + "\u0000" + turnId, key -> {
                RuntimeTraceVO.Turn created = new RuntimeTraceVO.Turn();
                created.setTurnId(turnId);
                created.setStepId(stepId);
                created.setStepName(stepName);
                created.setStatus("RUNNING");
                session.getTurns().add(created);
                return created;
            });
            turn.getEventIds().add(source.getEventId());
            applyTurnLifecycle(turn, eventType, event);

            String kind = spanKind(eventType, event.getDetail());
            String spanId = text(event.getDetail(), "spanId");
            if (kind == null || spanId == null) {
                continue;
            }
            RuntimeTraceVO.Span span = spans.computeIfAbsent(
                    sessionId + "\u0000" + turnId + "\u0000" + kind + "\u0000" + spanId, key -> {
                        RuntimeTraceVO.Span created = new RuntimeTraceVO.Span();
                        created.setSpanId(spanId);
                        created.setParentSpanId(text(event.getDetail(), "parentSpanId"));
                        created.setKind(kind);
                        created.setStatus("RUNNING");
                        turn.getSpans().add(created);
                        return created;
                    });
            span.getEventIds().add(source.getEventId());
            applySpan(span, eventType, event);
            if ("llm.usage".equals(eventType)) {
                addUsage(span.getTokenUsage(), event.getDetail());
                addUsage(turn.getTokenUsage(), event.getDetail());
            }
        }
        for (RuntimeTraceVO.Session session : trace.getSessions()) {
            for (RuntimeTraceVO.Turn turn : session.getTurns()) {
                addUsage(session.getTokenUsage(), turn.getTokenUsage());
            }
            Instant firstEvent = sessionFirstEvent.get(session.getSessionId());
            Instant lastEvent = sessionLastEvent.get(session.getSessionId());
            if (firstEvent != null && lastEvent != null) {
                session.setDurationMs(Math.max(0L, Duration.between(firstEvent, lastEvent).toMillis()));
            }
            addUsage(trace.getTokenUsage(), session.getTokenUsage());
        }
        return trace;
    }

    private static RuntimeTraceVO.Event toEvent(DispatchRuntimeEventDO source) {
        RuntimeTraceVO.Event event = new RuntimeTraceVO.Event();
        event.setEventId(source.getEventId());
        event.setSeq(source.getSeq());
        event.setEventType(source.getEventType());
        event.setEventTime(source.getEventTime() == null ? null : source.getEventTime().toInstant().toString());
        JSONObject parsed = null;
        try {
            parsed = source.getDetailJson() == null ? null : JSON.parseObject(source.getDetailJson());
        } catch (RuntimeException ignored) {
            // A malformed optional detail must not hide the rest of a dispatch trace.
        }
        event.setDetail(parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed));
        return event;
    }

    private static void applySessionLifecycle(RuntimeTraceVO.Session session, String type,
                                              RuntimeTraceVO.Event event) {
        String kind = switch (type) {
            case "session.started" -> "STARTED";
            case "session.resumed" -> "RESUMED";
            case "session.forked" -> "FORKED";
            case "session.interrupted" -> "INTERRUPTED";
            case "session.completed" -> "COMPLETED";
            case "session.failed" -> "FAILED";
            case "session.cancelled" -> "CANCELLED";
            default -> null;
        };
        if (kind == null) {
            return;
        }
        RuntimeTraceVO.Boundary boundary = new RuntimeTraceVO.Boundary();
        boundary.setEventId(event.getEventId());
        boundary.setKind(kind);
        boundary.setEventTime(event.getEventTime());
        boundary.setLabel(boundaryLabel(kind, event.getDetail()));
        session.getBoundaries().add(boundary);
        if (session.getStartedAt() == null && ("STARTED".equals(kind) || "RESUMED".equals(kind) || "FORKED".equals(kind))) {
            session.setStartedAt(event.getEventTime());
        }
        session.setStatus(switch (kind) {
            case "INTERRUPTED", "COMPLETED", "FAILED", "CANCELLED" -> kind;
            default -> "RUNNING";
        });
        if ("INTERRUPTED".equals(kind) || "COMPLETED".equals(kind)
                || "FAILED".equals(kind) || "CANCELLED".equals(kind)) {
            session.setEndedAt(event.getEventTime());
        }
    }

    private static String boundaryLabel(String kind, Map<String, Object> detail) {
        String reason = text(detail, "reason");
        String checkpoint = text(detail, "checkpointSeq");
        if (reason != null && checkpoint != null) {
            return kind + " · " + reason + " · checkpoint #" + checkpoint;
        }
        return reason == null ? kind : kind + " · " + reason;
    }

    private static void applyTurnLifecycle(RuntimeTraceVO.Turn turn, String type,
                                           RuntimeTraceVO.Event event) {
        if ("turn.started".equals(type)) {
            turn.setStartedAt(event.getEventTime());
            turn.setStatus("RUNNING");
            turn.setPrompt(first(text(event.getDetail(), "prompt"), turn.getPrompt()));
            turn.setSystemPrompt(first(text(event.getDetail(), "systemPrompt"), turn.getSystemPrompt()));
            return;
        }
        String status = switch (type) {
            case "turn.completed" -> "COMPLETED";
            case "turn.failed" -> "FAILED";
            case "turn.interrupted" -> "INTERRUPTED";
            default -> null;
        };
        if (status == null) {
            return;
        }
        turn.setStatus(status);
        turn.setEndedAt(event.getEventTime());
        turn.setDurationMs(duration(event.getDetail(), turn.getStartedAt(), event.getEventTime()));
    }

    private static void applySpan(RuntimeTraceVO.Span span, String type, RuntimeTraceVO.Event event) {
        Map<String, Object> detail = event.getDetail();
        String nextName = first(skillName(detail), text(detail, "tool"), text(detail, "model"), text(detail, "name"));
        if ("SKILL".equals(span.getKind()) && "skill".equalsIgnoreCase(nextName) && span.getName() != null) {
            nextName = span.getName();
        }
        span.setName(first(nextName, span.getName(), span.getKind()));
        span.setModel(first(text(detail, "model"), span.getModel()));
        span.setInputSummary(first(text(detail, "inputSummary"), span.getInputSummary()));
        span.setOutputSummary(first(text(detail, "outputSummary"), text(detail, "contentSummary"), span.getOutputSummary()));
        if (detail.containsKey("input")) {
            span.setInput(detail.get("input"));
        }
        span.setOutput(first(text(detail, "output"), span.getOutput()));
        String content = text(detail, "content");
        if (content != null && "agent.message".equals(type)) {
            span.setContent((span.getContent() == null ? "" : span.getContent()) + content);
        } else {
            span.setContent(first(content, span.getContent()));
        }
        span.setErrorCategory(first(text(detail, "errorCategory"), span.getErrorCategory()));
        if (isSpanStart(type)) {
            if (span.getStartedAt() == null) {
                span.setStartedAt(event.getEventTime());
            }
            span.setStatus(isInstantSpan(type) ? "COMPLETED" : "RUNNING");
        }
        if (isSpanEnd(type)) {
            span.setEndedAt(event.getEventTime());
            span.setDurationMs(duration(detail, span.getStartedAt(), event.getEventTime()));
            span.setStatus(isSuccessful(detail) ? "COMPLETED" : "FAILED");
        }
    }

    private static String spanKind(String type, Map<String, Object> detail) {
        if (type.startsWith("llm.thinking_")) return "THINKING";
        // Qoder ACP exposes a provider-turn envelope, not exact internal model
        // request boundaries. Calling this an LLM call would overstate coverage.
        if (type.startsWith("llm.")) return "PROVIDER";
        if (type.startsWith("bash.")) return "BASH";
        if (type.startsWith("cli.")) return "CLI";
        if (type.startsWith("mcp.")) return "MCP";
        if (type.startsWith("agent.tool_") && "skill".equalsIgnoreCase(text(detail, "tool"))) return "SKILL";
        if (type.startsWith("agent.tool_")) return "TOOL";
        if (type.equals("agent.message") && Boolean.TRUE.equals(detail.get("providerEvent"))) return "PROVIDER";
        if (type.startsWith("skill.")) return "SKILL";
        if (type.startsWith("guidance.")) return "GUIDANCE";
        if (type.startsWith("artifact.")) return "ARTIFACT";
        return null;
    }

    private static String skillName(Map<String, Object> detail) {
        Object input = detail.get("input");
        if (!(input instanceof Map<?, ?> values)) {
            return null;
        }
        Object value = values.get("skill");
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isSpanStart(String type) {
        return type.endsWith(".started") || type.endsWith(".call") || type.endsWith(".tool_use")
                || type.endsWith(".loaded") || type.endsWith(".invoked") || type.endsWith(".received");
    }

    private static boolean isInstantSpan(String type) {
        return type.endsWith(".loaded") || type.endsWith(".invoked") || type.endsWith(".received")
                || type.startsWith("artifact.");
    }

    private static boolean isSpanEnd(String type) {
        return type.endsWith(".completed") || type.endsWith(".failed") || type.endsWith(".result")
                || type.endsWith(".tool_result") || type.endsWith(".applied");
    }

    private static boolean isSuccessful(Map<String, Object> detail) {
        String value = text(detail, "status");
        if (value == null) {
            return text(detail, "errorCategory") == null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("ok") || normalized.equals("success") || normalized.equals("succeeded")
                || normalized.equals("completed") || normalized.equals("0");
    }

    private static Long duration(Map<String, Object> detail, String startedAt, String endedAt) {
        Long explicit = longValue(detail.get("durationMs"));
        if (explicit != null) {
            return explicit;
        }
        if (startedAt == null || endedAt == null) {
            return null;
        }
        try {
            return Math.max(0L, Duration.between(Instant.parse(startedAt), Instant.parse(endedAt)).toMillis());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void addUsage(RuntimeTraceVO.TokenUsage target, Map<String, Object> detail) {
        target.setAvailable(true);
        target.setInputTokens(target.getInputTokens() + value(detail, "inputTokens"));
        target.setOutputTokens(target.getOutputTokens() + value(detail, "outputTokens"));
        target.setReasoningTokens(target.getReasoningTokens() + value(detail, "reasoningTokens"));
        target.setCacheReadTokens(target.getCacheReadTokens() + value(detail, "cacheReadTokens"));
        target.setCacheWriteTokens(target.getCacheWriteTokens() + value(detail, "cacheWriteTokens"));
        target.setTotalTokens(target.getInputTokens() + target.getOutputTokens());
    }

    private static void addUsage(RuntimeTraceVO.TokenUsage target, RuntimeTraceVO.TokenUsage source) {
        if (!source.isAvailable()) {
            return;
        }
        target.setAvailable(true);
        target.setInputTokens(target.getInputTokens() + source.getInputTokens());
        target.setOutputTokens(target.getOutputTokens() + source.getOutputTokens());
        target.setReasoningTokens(target.getReasoningTokens() + source.getReasoningTokens());
        target.setCacheReadTokens(target.getCacheReadTokens() + source.getCacheReadTokens());
        target.setCacheWriteTokens(target.getCacheWriteTokens() + source.getCacheWriteTokens());
        target.setTotalTokens(target.getInputTokens() + target.getOutputTokens());
    }

    private static long value(Map<String, Object> detail, String key) {
        Long value = longValue(detail.get(key));
        return value == null ? 0L : value;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Map<String, Object> detail, String key) {
        Object value = detail.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
