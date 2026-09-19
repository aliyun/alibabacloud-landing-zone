package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.dto.DispatchLiveActivityVO;
import com.aliyun.autowonder.util.MojibakeDetector;
import com.aliyun.autowonder.util.RuntimeActionSanitizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Projects persisted {@code dispatch_runtime_event} rows into the bounded, sanitized action feed
 * rendered by the delivery progress live activity panel.
 *
 * <p>Two rules keep this safe: only allowlisted event types become actions, and only allowlisted
 * detail keys may contribute to a summary. Model output, thinking, prompts and guidance content are
 * never displayable, so a runtime that reports them cannot leak them through this feed.
 */
@Service
public class DispatchLiveActivityService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    private static final int MAX_SUMMARY_CHARS = RuntimeActionSanitizer.DEFAULT_MAX_CHARS;

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "package.", "bootstrap.", "workspace.", "repo.",
            "step.", "sdlc.", "dispatch.", "artifact.", "upload.",
            "agent.", "bash.", "cli.", "mcp.", "skill.", "plugin.",
            "session.", "turn.", "task.", "subagent.", "handoff.");
    private static final Set<String> ALLOWED_EXACT_TYPES = Set.of("completion_requested");
    /** Raw assistant content, model internals and human guidance text are never browser visible. */
    private static final Set<String> DENIED_EXACT_TYPES = Set.of("agent.message");
    private static final List<String> DENIED_PREFIXES = List.of("llm.", "guidance.", "comment.");
    /**
     * Chain-of-thought markers denied anywhere in the event type, so a runtime that reports private
     * reasoning under a broad {@code agent.}/{@code turn.} prefix cannot surface it even when the
     * payload also carries an allowlisted summary key.
     */
    private static final List<String> DENIED_SUBSTRINGS =
            List.of("thinking", "reasoning", "chain_of_thought", "chainofthought");

    /** Detail keys that may contribute to a summary. Anything else stays server side. */
    private static final List<String> SUMMARY_KEYS = List.of(
            "message", "summary", "text", "resultSummary", "inputSummary", "outputSummary", "reason");
    /** Short, already bounded identifiers that may name the action target. */
    private static final List<String> TARGET_KEYS = List.of("tool", "name", "stepName");

    private final DispatchDao dispatchDao;
    private final DispatchRuntimeEventDao eventDao;
    private final DispatchLiveActivityMetrics metrics;

    public DispatchLiveActivityService(DispatchDao dispatchDao, DispatchRuntimeEventDao eventDao,
            DispatchLiveActivityMetrics metrics) {
        this.dispatchDao = dispatchDao;
        this.eventDao = eventDao;
        this.metrics = metrics;
    }

    public DispatchLiveActivityVO get(long tenantId, long dispatchId) {
        return get(tenantId, dispatchId, null, null);
    }

    public DispatchLiveActivityVO get(long tenantId, long dispatchId, Long afterSeq, Integer limit) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        // Push the client cursor into SQL: a poll/reconnect only reprojects rows newer than afterSeq
        // instead of the whole dispatch history. limit stays in memory because totalActions/truncated
        // must still count every displayable delta row, which a SQL LIMIT would hide.
        List<DispatchRuntimeEventDO> sources;
        long lastSeq;
        if (afterSeq != null) {
            metrics.backfill();
            sources = safeList(eventDao.listByDispatchAfterSeq(tenantId, dispatchId, afterSeq));
            // An empty delta means no row is newer than the cursor, so lastSeq falls back to afterSeq
            // and the changed=false early return below still fires exactly as the full scan did.
            lastSeq = sources.stream().map(DispatchRuntimeEventDO::getSeq)
                    .filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(afterSeq);
        } else {
            sources = safeList(eventDao.listByDispatch(tenantId, dispatchId));
            lastSeq = sources.stream().map(DispatchRuntimeEventDO::getSeq)
                    .filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        }

        DispatchLiveActivityVO vo = new DispatchLiveActivityVO();
        vo.setDispatchId(dispatchId);
        vo.setAgentId(dispatch.getAgentId());
        vo.setWorkitemId(dispatch.getWorkitemId());
        vo.setSourceType(dispatch.executionSourceType().name());
        vo.setAttempt(dispatch.getAttempt());
        vo.setDispatchStatus(dispatch.getStatus());
        vo.setLastSeq(lastSeq);
        if (afterSeq != null && lastSeq <= afterSeq) {
            vo.setChanged(false);
            return vo;
        }

        List<DispatchLiveActivityVO.Action> displayable = new ArrayList<>();
        for (DispatchRuntimeEventDO source : sources) {
            if (afterSeq != null && source.getSeq() != null && source.getSeq() <= afterSeq) {
                continue;
            }
            DispatchLiveActivityVO.Action action = toAction(dispatch, source);
            if (action != null) {
                displayable.add(action);
            } else {
                metrics.filteredRead(source.getEventType());
            }
        }
        vo.setTotalActions(displayable.size());
        // A delta response carries no full picture, so the compatibility hint only applies to a full read.
        vo.setAwaitingRuntime(afterSeq == null && displayable.isEmpty());

        int window = normalizeLimit(limit);
        int from = Math.max(0, displayable.size() - window);
        metrics.truncated(from);
        vo.setTruncated(from > 0);
        List<DispatchLiveActivityVO.Action> newest = new ArrayList<>(displayable.subList(from, displayable.size()));
        // Newest first: the panel highlights the current action and reads the timeline downwards.
        Collections.reverse(newest);
        vo.setActions(newest);
        if (!displayable.isEmpty()) {
            DispatchLiveActivityVO.Action latest = displayable.get(displayable.size() - 1);
            vo.setLastUpdatedAt(latest.getEventTime());
            for (int i = displayable.size() - 1; i >= 0; i--) {
                if ("RUNNING".equals(displayable.get(i).getStatus())) {
                    vo.setCurrentAction(displayable.get(i));
                    break;
                }
            }
        }
        return vo;
    }

    /** Maps one persisted event to a browser visible action, or null when it must not be displayed. */
    public DispatchLiveActivityVO.Action toAction(DispatchDO dispatch, DispatchRuntimeEventDO event) {
        if (dispatch == null || event == null) {
            return null;
        }
        String eventType = event.getEventType();
        JSONObject detail = parseDetail(event.getDetailJson());
        String actionType = actionType(eventType, detail);
        if (actionType == null) {
            return null;
        }
        DispatchLiveActivityVO.Action action = new DispatchLiveActivityVO.Action();
        action.setEventId(event.getEventId());
        action.setSeq(event.getSeq());
        action.setEventTime(instantOf(event.getEventTime()));
        action.setEventType(eventType);
        action.setActionType(actionType);
        action.setStatus(actionStatus(eventType, detail, event.getError()));
        action.setStepId(event.getStepId());
        action.setStepKey(event.getStepKey());
        action.setStepName(sanitize(event.getStepName()));
        action.setAgentId(event.getAgentId() != null ? event.getAgentId() : dispatch.getAgentId());
        action.setDispatchId(dispatch.getId());
        action.setAttempt(dispatch.getAttempt());
        action.setSummary(summary(event, detail, actionType, eventType));
        return action;
    }

    private String summary(DispatchRuntimeEventDO event, JSONObject detail, String actionType, String eventType) {
        String reported = firstText(detail, SUMMARY_KEYS);
        if (reported == null && event.getMessage() != null && !event.getMessage().isBlank()
                && !MojibakeDetector.looksLikeMojibake(event.getMessage())) {
            reported = event.getMessage();
        }
        if (event.getError() != null && !event.getError().isBlank()
                && !MojibakeDetector.looksLikeMojibake(event.getError())) {
            reported = reported == null ? event.getError() : reported + " · " + event.getError();
        }
        String sanitized = sanitize(reported);
        if (sanitized != null && !RuntimeActionSanitizer.looksSensitive(sanitized)) {
            return sanitized;
        }
        // A summary that still carries a secret shape falls back to the generic label.
        String target = sanitize(firstText(detail, TARGET_KEYS), 48);
        String label = label(actionType, eventType);
        return target == null ? label : label + " · " + target;
    }

    static String actionType(String eventType, JSONObject detail) {
        if (!isDisplayable(eventType)) {
            return null;
        }
        String type = eventType.toLowerCase(Locale.ROOT);
        if (type.startsWith("package.") || type.startsWith("bootstrap.") || type.startsWith("workspace.")) {
            return "CONTEXT_PREPARE";
        }
        if (type.startsWith("repo.")) {
            return "REPO_PREPARE";
        }
        if (type.startsWith("step.") || type.startsWith("sdlc.") || "completion_requested".equals(type)) {
            return "SDLC_STEP";
        }
        if (type.startsWith("dispatch.")) {
            return "DISPATCH";
        }
        if (type.startsWith("handoff.")) {
            return "HANDOFF";
        }
        if (type.startsWith("artifact.") || type.startsWith("upload.")) {
            return "ARTIFACT";
        }
        if (type.startsWith("bash.") || type.startsWith("cli.")) {
            return "COMMAND";
        }
        // Capability installation is not an invocation by the agent.
        if ("skill.loaded".equals(type)) return "SKILL_LOAD";
        if ("plugin.loaded".equals(type)) return "PLUGIN_LOAD";
        if ("mcp.loaded".equals(type)) return "MCP_LOAD";
        if (type.startsWith("mcp.")) {
            return "MCP_CALL";
        }
        if (type.startsWith("skill.") || type.startsWith("plugin.")) {
            return "SKILL";
        }
        if (type.startsWith("session.")) {
            return "SESSION";
        }
        if (type.startsWith("turn.")) {
            return "MODEL_TURN";
        }
        if (type.startsWith("task.") || type.startsWith("subagent.")) {
            return "SUBAGENT";
        }
        if (type.startsWith("agent.tool_use") || type.startsWith("agent.tool_result")) {
            return toolActionType(text(detail, "tool"));
        }
        return "AGENT";
    }

    static String toolActionType(String tool) {
        if (tool == null || tool.isBlank()) {
            return "TOOL";
        }
        String normalized = tool.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("mcp__") || normalized.startsWith("mcp.")) {
            return "MCP_CALL";
        }
        if (Set.of("read", "grep", "glob", "ls", "search", "webfetch", "websearch", "view").contains(normalized)) {
            return "SEARCH_READ";
        }
        if (Set.of("edit", "write", "multiedit", "notebookedit", "apply_patch", "create_file").contains(normalized)) {
            return "FILE_EDIT";
        }
        if (Set.of("bash", "shell", "run", "execute", "runcommand").contains(normalized)) {
            return "COMMAND";
        }
        if (Set.of("skill", "plugin").contains(normalized)) {
            return "SKILL";
        }
        if (Set.of("agent", "task", "subagent", "dispatch_agent").contains(normalized)) {
            return "SUBAGENT";
        }
        return "TOOL";
    }

    static boolean isDisplayable(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        String type = eventType.toLowerCase(Locale.ROOT);
        if (DENIED_EXACT_TYPES.contains(type)) {
            return false;
        }
        for (String denied : DENIED_PREFIXES) {
            if (type.startsWith(denied)) {
                return false;
            }
        }
        for (String denied : DENIED_SUBSTRINGS) {
            if (type.contains(denied)) {
                return false;
            }
        }
        if (ALLOWED_EXACT_TYPES.contains(type)) {
            return true;
        }
        return ALLOWED_PREFIXES.stream().anyMatch(type::startsWith);
    }

    static String actionStatus(String eventType, JSONObject detail, String error) {
        if (error != null && !error.isBlank()) {
            return "FAILED";
        }
        String type = eventType == null ? "" : eventType.toLowerCase(Locale.ROOT);
        String reported = text(detail, "status");
        if (reported != null) {
            String normalized = reported.toLowerCase(Locale.ROOT);
            if (Set.of("failed", "error", "failure", "timeout").contains(normalized)) {
                return "FAILED";
            }
            if (Set.of("cancelled", "canceled", "aborted").contains(normalized)) {
                return "CANCELLED";
            }
            if (Set.of("paused", "pausing", "interrupted").contains(normalized)) {
                return "PAUSED";
            }
        }
        if (type.endsWith(".failed") || type.endsWith(".error") || type.endsWith(".timeout")) {
            return "FAILED";
        }
        if (type.endsWith(".cancelled") || type.endsWith(".canceled")) {
            return "CANCELLED";
        }
        if ("session.interrupted".equals(type) || type.endsWith(".paused") || type.endsWith(".pausing")) {
            return "PAUSED";
        }
        if ("session.resumed".equals(type) || type.endsWith(".resumed") || type.endsWith(".resume")) {
            return "RESUMED";
        }
        if (type.endsWith(".started") || type.endsWith(".call") || type.endsWith(".tool_use")
                || type.endsWith(".invoked") || type.endsWith(".received")
                || type.endsWith(".progress") || type.endsWith(".gate_started") || type.endsWith(".requested")) {
            return "RUNNING";
        }
        if (type.endsWith(".completed") || type.endsWith(".result") || type.endsWith(".tool_result")
                || type.endsWith(".applied") || type.endsWith(".finished") || type.endsWith(".uploaded")
                || type.endsWith(".gate_finished") || type.endsWith(".loaded") || "completion_requested".equals(type)) {
            return "COMPLETED";
        }
        return "INFO";
    }

    static String label(String actionType, String eventType) {
        String type = eventType == null ? "" : eventType.toLowerCase(Locale.ROOT);
        return switch (actionType) {
            case "CONTEXT_PREPARE" -> "准备任务包与上下文";
            case "REPO_PREPARE" -> "准备工作仓库";
            case "SDLC_STEP" -> switch (type) {
                case "step.gate_started" -> "开始校验";
                case "step.gate_finished" -> "校验完成";
                case "step.fix_required" -> "需要修复";
                case "step.failed" -> "步骤执行失败";
                case "completion_requested" -> "请求完成校验";
                default -> type.endsWith(".started") ? "开始 SDLC 步骤" : "SDLC 步骤更新";
            };
            case "DISPATCH" -> switch (type) {
                case "dispatch.started" -> "开始执行";
                case "dispatch.completed" -> "执行完成";
                case "dispatch.failed" -> "执行失败";
                case "dispatch.paused" -> "执行已暂停";
                case "dispatch.resumed" -> "执行已恢复";
                default -> "调度状态更新";
            };
            case "HANDOFF" -> switch (type) {
                case "handoff.submitted" -> "已提交交接";
                case "handoff.accepted" -> "交接已接收";
                default -> "交接处理";
            };
            case "ARTIFACT" -> "生成并上传产物";
            case "COMMAND" -> "执行命令";
            case "SKILL_LOAD" -> "已加载 Skill";
            case "PLUGIN_LOAD" -> "已加载 Plugin";
            case "MCP_LOAD" -> "已加载 MCP 服务";
            case "MCP_CALL" -> "调用 MCP 工具";
            case "SKILL" -> "调用 Skill";
            case "SESSION" -> switch (type) {
                case "session.started" -> "会话开始";
                case "session.resumed" -> "会话恢复";
                case "session.forked" -> "会话分叉";
                case "session.interrupted" -> "会话中断";
                case "session.completed" -> "会话结束";
                case "session.failed" -> "会话失败";
                case "session.cancelled" -> "会话取消";
                default -> "会话状态更新";
            };
            case "MODEL_TURN" -> switch (type) {
                case "turn.started" -> "开始新一轮推理";
                case "turn.completed" -> "本轮推理完成";
                case "turn.failed" -> "本轮推理失败";
                case "turn.interrupted" -> "本轮推理中断";
                default -> "推理轮次更新";
            };
            case "SUBAGENT" -> "调度子代理";
            case "SEARCH_READ" -> "检索或读取代码";
            case "FILE_EDIT" -> "修改文件";
            case "TOOL" -> "调用工具";
            default -> "Agent 进度更新";
        };
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static JSONObject parseDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSON.parseObject(detailJson);
            return parsed == null ? new JSONObject() : parsed;
        } catch (RuntimeException ignored) {
            // A malformed optional detail must not hide the rest of the live activity feed.
            return new JSONObject();
        }
    }

    private static String firstText(JSONObject detail, List<String> keys) {
        for (String key : keys) {
            String value = text(detail, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JSONObject detail, String key) {
        Object value = detail.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static String sanitize(String value) {
        return sanitize(value, MAX_SUMMARY_CHARS);
    }

    private static String sanitize(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (MojibakeDetector.looksLikeMojibake(value)) {
            return null;
        }
        return RuntimeActionSanitizer.sanitize(value, maxChars);
    }

    private static String instantOf(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    private static <T> List<T> safeList(List<T> source) {
        return source == null ? List.of() : source;
    }
}
