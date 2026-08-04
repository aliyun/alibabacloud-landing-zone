package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.guidance.GuidanceTransport;
import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

@Component
public class WsGuidanceTransport implements GuidanceTransport {
    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;

    public WsGuidanceTransport(SessionRegistry sessionRegistry, RedisManager redisManager) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
    }

    @Override
    public void send(GuidanceDO guidance, String contentMd) {
        if (guidance.getExecutorId() == null || guidance.getDispatchId() == null) {
            throw new IllegalArgumentException("guidance must be bound to a dispatch and executor");
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "TASK_GUIDANCE");
        frame.put("executorId", guidance.getExecutorId());
        frame.put("guidanceId", guidance.getId());
        frame.put("dispatchId", guidance.getDispatchId());
        frame.put("workitemId", guidance.getWorkitemId());
        frame.put("targetAgentId", guidance.getTargetAgentId());
        frame.put("content", contentMd);
        frame.put("mode", "SAFE_STEER");
        String payload = frame.toJSONString();
        ExecutorSession session = sessionRegistry.findByExecutorId(guidance.getExecutorId());
        try {
            if (session != null && session.getSession().isOpen()) {
                session.sendText(payload);
            } else {
                redisManager.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
            }
        } catch (Exception e) {
            throw new IllegalStateException("WebSocket guidance send failed", e);
        }
    }
}
