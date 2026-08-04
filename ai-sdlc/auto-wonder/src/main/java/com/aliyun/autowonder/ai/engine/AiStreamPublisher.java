package com.aliyun.autowonder.ai.engine;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.BrowserRealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(AiStreamPublisher.class);

    private final RedisManager redisManager;
    private final BrowserRealtimePublisher browserRealtimePublisher;

    public AiStreamPublisher(RedisManager redisManager, BrowserRealtimePublisher browserRealtimePublisher) {
        this.redisManager = redisManager;
        this.browserRealtimePublisher = browserRealtimePublisher;
    }

    public void publishDelta(long sessionId, long tenantId, String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "delta");
        event.put("sessionId", sessionId);
        event.put("text", text);
        publish(sessionId, tenantId, event);
    }

    public void publishStatus(long sessionId, long tenantId, String status) {
        log.info("ai stream publishStatus sessionId={} status={}", sessionId, status);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "status");
        event.put("sessionId", sessionId);
        event.put("status", status);
        publish(sessionId, tenantId, event);
    }

    public void publishResult(long sessionId, long tenantId, String resultJson) {
        log.info("ai stream publishResult sessionId={} len={}", sessionId, resultJson != null ? resultJson.length() : 0);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "result");
        event.put("sessionId", sessionId);
        event.put("resultJson", resultJson);
        publish(sessionId, tenantId, event);
    }

    public static String channelFor(long sessionId) {
        return "ai:stream:" + sessionId;
    }

    public static String frontendChannelFor(long sessionId) {
        return "ai:session:" + sessionId;
    }

    private void publish(long sessionId, long tenantId, Map<String, Object> event) {
        redisManager.publish(channelFor(sessionId), JSON.toJSONString(event));
        browserRealtimePublisher.publish(tenantId, frontendChannelFor(sessionId), "AI_STREAM", event);
    }
}
