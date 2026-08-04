package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ConversationRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(ConversationRealtimePublisher.class);
    public static final String REDIS_CHANNEL = "autowonder:conversation-events";

    private final RedisManager redisManager;

    public ConversationRealtimePublisher(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void publish(String channel, String type, Object payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", channel);
        message.put("type", type);
        message.put("payload", payload);
        message.put("timestamp", System.currentTimeMillis());
        String json = JSON.toJSONString(message);
        try (Jedis jedis = redisManager.getJedisPool().getResource()) {
            jedis.publish(REDIS_CHANNEL, json);
        } catch (Exception e) {
            log.warn("conversation realtime publish failed channel={}: {}", channel, e.getMessage());
        }
    }
}
