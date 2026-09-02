package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

@Service
public class RuntimeMcpConnectionTestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeMcpConnectionTestService.class);
    private static final int RESULT_TTL_SECONDS = 120;
    private static final long MIN_WAIT_MILLIS = 90_000L;
    private static final long MAX_WAIT_MILLIS = 615_000L;
    private final ExecutorDao executorDao;
    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;

    public RuntimeMcpConnectionTestService(ExecutorDao executorDao, SessionRegistry sessionRegistry, RedisManager redisManager) {
        this.executorDao = executorDao;
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
    }

    public SkillConnectionTestResult test(long tenantId, long executorId, String command, List<String> args) {
        return test(tenantId, executorId, "stdio", command, args, null, Map.of(), 60);
    }

    public SkillConnectionTestResult test(long tenantId, long executorId, String transport,
                                          String command, List<String> args, String url) {
        return test(tenantId, executorId, transport, command, args, url, Map.of(), 60);
    }

    public SkillConnectionTestResult test(long tenantId, long executorId, String transport,
                                          String command, List<String> args, String url,
                                          Map<String, String> headers, int timeoutSeconds) {
		return test(tenantId, executorId, transport, command, args, url, headers, Map.of(), timeoutSeconds);
	}

	public SkillConnectionTestResult test(long tenantId, long executorId, String transport,
										  String command, List<String> args, String url,
										  Map<String, String> headers, Map<String, String> env, int timeoutSeconds) {
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
        frame.put("headers", headers == null ? Map.of() : headers);
		frame.put("env", env == null ? Map.of() : env);
        frame.put("timeoutSeconds", timeoutSeconds);
        send(executorId, frame.toJSONString());
        long waitMillis = Math.min(MAX_WAIT_MILLIS, Math.max(MIN_WAIT_MILLIS, timeoutSeconds * 1_000L + 15_000L));
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            String encodedResult;
            try {
                encodedResult = redisManager.get(resultV2Key(testId));
            } catch (RuntimeException e) {
                LOGGER.error("MCP connection test result read failed testId={} redisKey={} tenantId={} executorId={}",
                        testId, resultV2Key(testId), tenantId, executorId, e);
                throw e;
            }
            if (encodedResult != null) {
                return decodeV2Result(testId, encodedResult);
            }
            Result legacyResult;
            try {
                legacyResult = redisManager.get(resultKey(testId));
            } catch (RuntimeException e) {
                LOGGER.error("MCP connection test legacy result is incompatible testId={} redisKey={} tenantId={} executorId={}",
                        testId, resultKey(testId), tenantId, executorId, e);
                return new SkillConnectionTestResult(false, "测试结果与灰度版本不兼容，请稍后重新测试", null, List.of());
            }
            if (legacyResult != null) {
                List<Map<String, Object>> tools = redisManager.get(toolsKey(testId));
                return new SkillConnectionTestResult(legacyResult.success, legacyResult.message, legacyResult.durationMs, tools);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SkillConnectionTestResult(false, "连接测试被中断", null, List.of());
            }
        }
        return new SkillConnectionTestResult(false, "Runtime 未在 " + (waitMillis / 1000) + " 秒内返回；请确认该 Runtime 在线且已更新", null, List.of());
    }

    public void complete(long tenantId, long executorId, String testId, boolean success, String message,
                         Long durationMs, List<Map<String, Object>> tools) {
        Ticket ticket = redisManager.get(ticketKey(testId));
        if (ticket == null || ticket.tenantId != tenantId || ticket.executorId != executorId) {
            return;
        }
        // V2 uses JSON so adding fields never changes Java serialization compatibility.
        JSONObject result = new JSONObject(true);
        result.put("success", success);
        result.put("message", message);
        result.put("durationMs", durationMs);
        result.put("tools", tools == null ? List.of() : tools);
        redisManager.set(resultV2Key(testId), result.toJSONString(), RESULT_TTL_SECONDS);

        // Keep legacy keys during the rolling release so pre-V2 application nodes can finish their own requests.
        redisManager.set(toolsKey(testId), (java.io.Serializable) (tools == null ? List.of() : List.copyOf(tools)), RESULT_TTL_SECONDS);
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
    private static String resultV2Key(String testId) { return "mcp:connection:test:result:v2:" + testId; }
    private static String resultKey(String testId) { return "mcp:connection:test:result:" + testId; }
    private static String toolsKey(String testId) { return "mcp:connection:test:tools:" + testId; }

    private SkillConnectionTestResult decodeV2Result(String testId, String encodedResult) {
        try {
            JSONObject result = JSON.parseObject(encodedResult);
            JSONArray toolArray = result.getJSONArray("tools");
            List<Map<String, Object>> tools = new ArrayList<>();
            if (toolArray != null) {
                for (Object tool : toolArray) {
                    if (tool instanceof Map<?, ?>) {
                        Map<String, Object> normalized = new LinkedHashMap<>();
                        ((Map<?, ?>) tool).forEach((key, value) -> normalized.put(String.valueOf(key), value));
                        tools.add(normalized);
                    }
                }
            }
            return new SkillConnectionTestResult(Boolean.TRUE.equals(result.getBoolean("success")),
                    result.getString("message"), result.getLong("durationMs"), tools);
        } catch (RuntimeException e) {
            LOGGER.error("MCP connection test V2 result is invalid testId={} redisKey={}",
                    testId, resultV2Key(testId), e);
            return new SkillConnectionTestResult(false, "测试结果格式无效，请重新测试", null, List.of());
        }
    }

    public static class SkillConnectionTestResult {
        public final boolean success;
        public final String message;
        public final Long durationMs;
        public final List<Map<String, Object>> tools;
        SkillConnectionTestResult(boolean success, String message, Long durationMs, List<Map<String, Object>> tools) {
            this.success = success; this.message = message; this.durationMs = durationMs;
            this.tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }
    private static class Ticket implements java.io.Serializable {
        final long tenantId; final long executorId;
        Ticket(long tenantId, long executorId) { this.tenantId = tenantId; this.executorId = executorId; }
    }
    private static class Result implements java.io.Serializable {
        final boolean success; final String message; final Long durationMs;
        Result(boolean success, String message, Long durationMs) {
            this.success = success; this.message = message; this.durationMs = durationMs;
        }
    }
}
