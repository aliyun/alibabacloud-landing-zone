package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BrowserRealtimePublisher {

    public void publish(long tenantId, String channel, String type, Object payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("channel", channel);
        frame.put("type", type);
        frame.put("payload", payload);
        frame.put("timestamp", System.currentTimeMillis());
        BrowserRealtimeEndpoint.broadcast(tenantId, JSON.toJSONString(frame));
    }
}
