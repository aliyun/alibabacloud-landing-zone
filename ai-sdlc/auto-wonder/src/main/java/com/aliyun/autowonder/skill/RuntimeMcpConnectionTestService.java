package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RuntimeMcpConnectionTestService {
    private static final int RESULT_TTL_SECONDS = 120;
    private static final long WAIT_MILLIS = 90_000L;
    private final ExecutorDao executorDao;
    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;

    public RuntimeMcpConnectionTestService(ExecutorDao executorDao, SessionRegistry sessionRegistry, RedisManager redisManager) {
        this.executorDao = executorDao;
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
    }

    public SkillConnectionTestResult test(long tenantId, long executorId, String command, List<String> args) {
        return test(tenantId, executorId, "stdio", command, args, null);
    }

    public SkillConnectionTestResult test(long tenantId, long executorId, String transport,
                                          String command, List<String> args, String url) {
        ExecutorDO executor = executorDao.findById(executorId);
        if (executor == null || executor.getTenantId() == null || executor.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        String testId = UUID.randomUUID().toString();
        Ticket ticket = new Ticket(tenantId, executorId);
        if (!redisManager.set(ticketKey(testId), ticket, RESULT_TTL_SECONDS)) {
            throw new IllegalStateException("无法创建 Runtime 测试请求");
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "MCP_CONNECTION_TEST");
        frame.put("testId", testId);
        frame.put("executorId", executorId);
        frame.put("transport", transport);
        frame.put("command", command);
        frame.put("args", args);
        frame.put("url", url);
        send(executorId, frame.toJSONString());
        long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Result result = redisManager.get(resultKey(testId));
            if (result != null) {
                return new SkillConnectionTestResult(result.success, result.message, result.durationMs);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SkillConnectionTestResult(false, "连接测试被中断", null);
            }
        }
        return new SkillConnectionTestResult(false, "Runtime 未在 90 秒内返回；请确认该 Runtime 在线且已更新", null);
    }

    public void complete(long tenantId, long executorId, String testId, boolean success, String message, Long durationMs) {
        Ticket ticket = redisManager.get(ticketKey(testId));
        if (ticket == null || ticket.tenantId != tenantId || ticket.executorId != executorId) {
            return;
        }
        redisManager.set(resultKey(testId), new Result(success, message, durationMs), RESULT_TTL_SECONDS);
    }

    private void send(long executorId, String payload) {
        try {
            ExecutorSession session = sessionRegistry.findByExecutorId(executorId);
            if (session != null && session.getSession().isOpen()) {
                session.sendText(payload);
            } else {
                redisManager.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Runtime MCP 测试下发失败", e);
        }
    }

    private static String ticketKey(String testId) { return "mcp:connection:test:ticket:" + testId; }
    private static String resultKey(String testId) { return "mcp:connection:test:result:" + testId; }

    public static class SkillConnectionTestResult {
        public final boolean success;
        public final String message;
        public final Long durationMs;
        SkillConnectionTestResult(boolean success, String message, Long durationMs) {
            this.success = success; this.message = message; this.durationMs = durationMs;
        }
    }
    private static class Ticket implements java.io.Serializable {
        final long tenantId; final long executorId;
        Ticket(long tenantId, long executorId) { this.tenantId = tenantId; this.executorId = executorId; }
    }
    private static class Result implements java.io.Serializable {
        final boolean success; final String message; final Long durationMs;
        Result(boolean success, String message, Long durationMs) { this.success = success; this.message = message; this.durationMs = durationMs; }
    }
}
