package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchControlTransport;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

@Component
public class WsDispatchControlTransport implements DispatchControlTransport {

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;

    public WsDispatchControlTransport(SessionRegistry sessionRegistry, RedisManager redisManager) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
    }

    @Override
    public void pause(DispatchDO dispatch) {
        if (dispatch.getExecutorId() == null) {
            throw new IllegalStateException("pause requires an assigned executor");
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "TASK_PAUSE");
        frame.put("dispatchId", dispatch.getId());
        frame.put("executorId", dispatch.getExecutorId());
        String payload = frame.toJSONString();
        ExecutorSession session = sessionRegistry.findByExecutorId(dispatch.getExecutorId());
        try {
            if (session != null && session.getSession().isOpen()) {
                session.sendText(payload);
            } else {
                redisManager.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
            }
        } catch (Exception e) {
            throw new IllegalStateException("WebSocket pause send failed", e);
        }
    }
}
