package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ExecutorRestartService {
    private final ExecutorDao dao;
    private final ExecutorRegistry registry;
    private final PresenceManager presence;
    private final RedisManager redis;
    private final SessionRegistry sessions;
    private final ExecutorLaunchConfigService launchConfigs;
    private final PlatformBrandingService branding;
    private static final Set<String> ACTIVE = Set.of("REQUESTED", "WAITING_IDLE", "UPDATING", "RESTARTING");

    public ExecutorRestartService(ExecutorDao dao, ExecutorRegistry registry, PresenceManager presence,
                                  RedisManager redis, SessionRegistry sessions, ExecutorLaunchConfigService launchConfigs,
                                  PlatformBrandingService branding) {
        this.dao = dao; this.registry = registry; this.presence = presence; this.redis = redis; this.sessions = sessions;
        this.launchConfigs = launchConfigs; this.branding = branding;
    }
    static String key(long id) { return "exec:restart:" + id; }
    static String lock(long id) { return "exec:restart-lock:" + id; }

    public JSONObject request(long id, long tenantId, long userId, boolean update) {
        return request(id, tenantId, userId, update, null, null, null);
    }

    public JSONObject applyLaunchCommand(long id, long tenantId, long userId, Integer version) {
        return applyLaunchCommand(id, tenantId, userId, version, null);
    }

    public JSONObject applyLaunchCommand(long id, long tenantId, long userId, Integer version, String restartMode) {
        if (restartMode != null && !Set.of("WHEN_IDLE", "FORCE").contains(restartMode)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.restart.invalid_mode", null, "无效的在线应用重启方式");
        }
        if (version == null) throw BizException.keyed(ErrorCode.PARAM_INVALID,
                "executor.config.version_required", null, "缺少启动配置版本号");
        var config = launchConfigs.requireCompleteConfig(id, tenantId, version);
        JSONObject launch = new JSONObject(true);
        String targetVersion = config.runtimeVersion == null ? presence.currentVersion(id) : config.runtimeVersion;
        launch.put("runtimeVersion", targetVersion == null || targetVersion.isBlank() ? branding.recommendedRuntimeVersion() : targetVersion);
        launch.put("workspaceRoot", config.workspaceRoot);
        launch.put("daemonName", config.daemonName);
        launch.put("debug", Boolean.TRUE.equals(config.debug));
        launch.put("memoryMode", config.memoryMode);
        launch.put("maxConcurrentDispatches", ExecutorLaunchConfigService.resolveMaxConcurrentDispatches(config.maxConcurrentDispatches));
        launch.put("model", config.model);
        launch.put("reasoningEffort", config.reasoningEffort);
        launch.put("contextWindow", config.contextWindow);
        if (restartMode != null) launch.put("restartMode", restartMode);
        return request(id, tenantId, userId, false, launch, version, restartMode);
    }

    private JSONObject request(long id, long tenantId, long userId, boolean update, JSONObject launch, Integer version, String restartMode) {
        ExecutorDO executor = dao.findById(id);
        if (executor == null || !Objects.equals(executor.getTenantId(), tenantId)) throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        if (!registry.isOnline(id)) throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.restart.offline", null, "执行器离线，无法远程重启");
        String feature = launch != null ? "EXECUTOR_LAUNCH_COMMAND_V1" : update ? "EXECUTOR_UPDATE_RESTART_V1" : "EXECUTOR_RESTART_V1";
        if (launch != null && !presence.supportsProtocolFeature(id, feature)) throw BizException.keyed(ErrorCode.PARAM_INVALID,
                "executor.command.online_unsupported", null, "当前客户端不支持在线应用启动命令，请先在本地升级客户端");
        if (!presence.supportsProtocolFeature(id, feature)) throw BizException.keyed(ErrorCode.PARAM_INVALID,
                update ? "executor.restart.update_unsupported" : "executor.restart.unsupported", null,
                update ? "当前客户端不支持发布版更新，请在本地更新源码或升级客户端" : "当前客户端不支持远程重启，请先在本地升级客户端");
        if (restartMode != null && !presence.supportsProtocolFeature(id, "EXECUTOR_RESTART_POLICY_V1")) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.restart.policy_unsupported", null,
                    "当前客户端不支持选择重启方式，请先升级客户端");
        }
        if (executor.getLastStartedAt() == null) throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.restart.started_at_required", null, "等待客户端首次上报启动时间后再重试");
        String requestId = UUID.randomUUID().toString();
        if (!redis.setIfAbsent(lock(id), restartMode == null ? "1" : requestId, 600L)) throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.restart.active", null, "已有重启请求，请等待结果");
        JSONObject result = new JSONObject(true);
        result.put("requestId", requestId);
        if (restartMode != null) result.put("restartMode", restartMode);
        result.put("status", "REQUESTED"); result.put("message", "已发送，等待客户端响应");
        result.put("messageOrigin", "SYSTEM");
        result.put("issuedAt", Instant.now().toString()); result.put("update", update);
        result.put("requestedBy", userId);
        result.put("previousStartedAt", executor.getLastStartedAt() == null ? null : executor.getLastStartedAt().getTime());
        if (launch != null) {
            result.put("launchConfigVersion", version);
            result.put("targetRuntimeVersion", launch.getString("runtimeVersion"));
        }
        save(id, result);
        JSONObject frame = new JSONObject(true);
        frame.putAll(result);
        frame.remove("messageOrigin");
        frame.put("type", "EXECUTOR_RESTART"); frame.put("executorId", id);
        if (launch != null) frame.put("launch", launch);
        try {
            ExecutorSession session = sessions.findByExecutorId(id);
            if (session != null && session.getSession().isOpen()) session.sendText(frame.toJSONString());
            else redis.publish(WsDispatchTransport.BROADCAST_CHANNEL, frame.toJSONString());
        } catch (Exception e) {
            result.put("status", "FAILED"); result.put("message", "发送失败，请稍后重试");
            result.put("messageOrigin", "SYSTEM"); save(id, result); release(id, result);
        }
        return response(result);
    }

    public JSONObject status(long id) {
        return response(readState(id));
    }

    private JSONObject readState(long id) {
        String raw = redis.getString(key(id));
        if (raw == null || raw.isBlank()) return null;
        JSONObject result = JSON.parseObject(raw);
        if (ACTIVE.contains(result.getString("status")) && Instant.parse(result.getString("progressAt") == null ? result.getString("issuedAt") : result.getString("progressAt")).isBefore(Instant.now().minusSeconds(600))) {
            result.put("status", "TIMED_OUT"); result.put("message", result.containsKey("restartMode")
                    ? "在线应用长时间未收到客户端进度或重启心跳，请检查客户端" : "未收到重启后的心跳，请检查客户端");
            result.put("messageOrigin", "SYSTEM");
        }
        return result;
    }

    public void onResult(ExecutorSession session, JSONObject frame) {
        long id = session.getExecutorId();
        JSONObject current = readState(id);
        if (current == null || !Objects.equals(current.getString("requestId"), frame.getString("requestId")) || !ACTIVE.contains(current.getString("status"))) return;
        String status = frame.getString("status");
        if (!Set.of("WAITING_IDLE", "UPDATING", "RESTARTING", "FAILED").contains(status)) return;
        if ("WAITING_IDLE".equals(status) && (!"WHEN_IDLE".equals(current.getString("restartMode"))
                || !Set.of("REQUESTED", "WAITING_IDLE").contains(current.getString("status")))) return;
        if (current.containsKey("restartMode")) {
            Object renewed = redis.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], 600) else return 0 end",
                    java.util.List.of(lock(id)), java.util.List.of(current.getString("requestId")));
            if (!(renewed instanceof Number) || ((Number) renewed).longValue() != 1L) return;
            current.put("progressAt", Instant.now().toString());
        }
        current.put("status", status);
        current.put("messageOrigin", "CLIENT");
        String message = frame.getString("message");
        current.put("message", message == null ? "" : message.substring(0, Math.min(message.length(), 1000)));
        save(id, current);
        if ("FAILED".equals(status)) release(id, current);
    }

    public void onHeartbeat(ExecutorSession session, JSONObject frame) {
        String value = frame.getString("startedAt");
        if (value == null || value.isBlank()) return;
        Instant started;
        try { started = Instant.parse(value); } catch (RuntimeException invalid) { return; }
        if (started.isAfter(Instant.now().plusSeconds(300)) || started.isBefore(Instant.parse("2020-01-01T00:00:00Z"))) return;
        long id = session.getExecutorId();
        dao.updateLastStartedAt(id, session.getTenantId(), Date.from(started));
        JSONObject current = readState(id);
        if (current == null || !ACTIVE.contains(current.getString("status")) || !Objects.equals(current.getString("requestId"), frame.getString("restartRequestId"))) return;
        Long previous = current.getLong("previousStartedAt");
        if (previous != null && started.toEpochMilli() <= previous) return;
        if (current.containsKey("targetRuntimeVersion")
                && !Objects.equals(frame.getString("version"), current.getString("targetRuntimeVersion"))
                && RuntimeVersion.compare(frame.getString("version"), current.getString("targetRuntimeVersion")).orElse(1) != 0) return;
        current.put("status", "COMPLETED"); current.put("message", "客户端已重新启动并上线");
        current.put("messageOrigin", "SYSTEM");
        current.put("completedAt", Instant.now().toString()); save(id, current); release(id, current);
    }
    private void release(long id, JSONObject state) {
        if (state.containsKey("restartMode")) redis.releaseLock(lock(id), state.getString("requestId"));
        else redis.del(lock(id));
    }

    private static JSONObject response(JSONObject state) {
        if (state == null) return null;
        JSONObject result = new JSONObject(state);
        if (!"SYSTEM".equals(state.getString("messageOrigin"))) return result;
        String key = switch (Objects.toString(state.getString("status"), "")) {
            case "REQUESTED" -> "executor.restart.requested";
            case "FAILED" -> "executor.restart.send_failed";
            case "TIMED_OUT" -> state.containsKey("restartMode") ? "executor.restart.policy_timed_out" : "executor.restart.timed_out";
            case "COMPLETED" -> "executor.restart.completed";
            default -> null;
        };
        result.put("message", SystemMessages.render(key, null, state.getString("message")));
        return result;
    }
    private void save(long id, JSONObject state) { redis.setWithExpire(key(id), state.toJSONString(), 86400); }
}
