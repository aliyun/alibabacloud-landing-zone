package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.json.JSONObject;
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
        send(dispatch, false);
    }

    @Override
    public void stop(DispatchDO dispatch) {
        send(dispatch, true);
    }

    private void send(DispatchDO dispatch, boolean cancel) {
        if (dispatch.getExecutorId() == null) {
            throw new IllegalStateException("pause requires an assigned executor");
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "TASK_PAUSE");
        frame.put("dispatchId", dispatch.getId());
        frame.put("executorId", dispatch.getExecutorId());
        // Older runtimes ignore the additive field and retain pause semantics.
        if (cancel) frame.put("cancel", true);
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
