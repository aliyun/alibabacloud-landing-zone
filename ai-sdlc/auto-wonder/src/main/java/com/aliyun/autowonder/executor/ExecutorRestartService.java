package com.aliyun.autowonder.executor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
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
    private static final Set<String> ACTIVE = Set.of("REQUESTED", "UPDATING", "RESTARTING");

    public ExecutorRestartService(ExecutorDao dao, ExecutorRegistry registry, PresenceManager presence,
                                  RedisManager redis, SessionRegistry sessions) {
        this.dao = dao; this.registry = registry; this.presence = presence; this.redis = redis; this.sessions = sessions;
    }
    static String key(long id) { return "exec:restart:" + id; }
    static String lock(long id) { return "exec:restart-lock:" + id; }

    public JSONObject request(long id, long tenantId, long userId, boolean update) {
        ExecutorDO executor = dao.findById(id);
        if (executor == null || !Objects.equals(executor.getTenantId(), tenantId)) throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        if (!registry.isOnline(id)) throw new BizException(ErrorCode.PARAM_INVALID, "执行器离线，无法远程重启");
        String feature = update ? "EXECUTOR_UPDATE_RESTART_V1" : "EXECUTOR_RESTART_V1";
        if (!presence.supportsProtocolFeature(id, feature)) throw new BizException(ErrorCode.PARAM_INVALID,
                update ? "当前客户端不支持发布版更新，请在本地更新源码或升级客户端" : "当前客户端不支持远程重启，请先在本地升级客户端");
        if (executor.getLastStartedAt() == null) throw new BizException(ErrorCode.PARAM_INVALID, "等待客户端首次上报启动时间后再重试");
        if (!redis.setIfAbsent(lock(id), "1", 600L)) throw new BizException(ErrorCode.PARAM_INVALID, "已有重启请求，请等待结果");
        JSONObject result = new JSONObject(true);
        result.put("requestId", UUID.randomUUID().toString());
        result.put("status", "REQUESTED"); result.put("message", "已发送，等待客户端响应");
        result.put("issuedAt", Instant.now().toString()); result.put("update", update);
        result.put("requestedBy", userId);
        result.put("previousStartedAt", executor.getLastStartedAt() == null ? null : executor.getLastStartedAt().getTime());
        save(id, result);
        JSONObject frame = new JSONObject(true);
        frame.putAll(result);
        frame.put("type", "EXECUTOR_RESTART"); frame.put("executorId", id);
        try {
            ExecutorSession session = sessions.findByExecutorId(id);
            if (session != null && session.getSession().isOpen()) session.sendText(frame.toJSONString());
            else redis.publish(WsDispatchTransport.BROADCAST_CHANNEL, frame.toJSONString());
        } catch (Exception e) {
            result.put("status", "FAILED"); result.put("message", "发送失败，请稍后重试"); save(id, result); redis.del(lock(id));
        }
        return result;
    }

    public JSONObject status(long id) {
        String raw = redis.getString(key(id));
        if (raw == null || raw.isBlank()) return null;
        JSONObject result = JSON.parseObject(raw);
        if (ACTIVE.contains(result.getString("status")) && Instant.parse(result.getString("issuedAt")).isBefore(Instant.now().minusSeconds(600))) {
            result.put("status", "TIMED_OUT"); result.put("message", "未收到重启后的心跳，请检查客户端");
        }
        return result;
    }

    public void onResult(ExecutorSession session, JSONObject frame) {
        long id = session.getExecutorId();
        JSONObject current = status(id);
        if (current == null || !Objects.equals(current.getString("requestId"), frame.getString("requestId")) || !ACTIVE.contains(current.getString("status"))) return;
        String status = frame.getString("status");
        if (!Set.of("UPDATING", "RESTARTING", "FAILED").contains(status)) return;
        current.put("status", status);
        String message = frame.getString("message");
        current.put("message", message == null ? "" : message.substring(0, Math.min(message.length(), 1000)));
        save(id, current);
        if ("FAILED".equals(status)) redis.del(lock(id));
    }

    public void onHeartbeat(ExecutorSession session, JSONObject frame) {
        String value = frame.getString("startedAt");
        if (value == null || value.isBlank()) return;
        Instant started;
        try { started = Instant.parse(value); } catch (RuntimeException invalid) { return; }
        if (started.isAfter(Instant.now().plusSeconds(300)) || started.isBefore(Instant.parse("2020-01-01T00:00:00Z"))) return;
        long id = session.getExecutorId();
        dao.updateLastStartedAt(id, session.getTenantId(), Date.from(started));
        JSONObject current = status(id);
        if (current == null || !ACTIVE.contains(current.getString("status")) || !Objects.equals(current.getString("requestId"), frame.getString("restartRequestId"))) return;
        Long previous = current.getLong("previousStartedAt");
        if (previous != null && started.toEpochMilli() <= previous) return;
        current.put("status", "COMPLETED"); current.put("message", "客户端已重新启动并上线");
        current.put("completedAt", Instant.now().toString()); save(id, current); redis.del(lock(id));
    }
    private void save(long id, JSONObject state) { redis.setWithExpire(key(id), state.toJSONString(), 86400); }
}
