package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.dispatch.dto.DispatchLiveActivityVO;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fans one sanitized live activity action out to browsers watching that dispatch.
 *
 * <p>Redis is the sole delivery path: {@code WebSocketConfig} forwards the {@code dispatch:*}
 * pattern only to sessions that passed dispatch channel authorization and subscribed explicitly, so
 * the realtime path cannot bypass the read permission the REST endpoint enforces.
 */
@Service
public class DispatchLiveActivityPublisher {

    public static final String CHANNEL_PREFIX = "dispatch:";
    public static final String FRAME_TYPE = "live-activity";

    private static final Logger log = LoggerFactory.getLogger(DispatchLiveActivityPublisher.class);

    private final RedisManager redis;
    private final DispatchLiveActivityService activityService;
    private final DispatchLiveActivityMetrics metrics;

    public DispatchLiveActivityPublisher(RedisManager redis, DispatchLiveActivityService activityService,
            DispatchLiveActivityMetrics metrics) {
        this.redis = redis;
        this.activityService = activityService;
        this.metrics = metrics;
    }

    /** Never throws: a realtime fan-out failure must not fail runtime event ingestion. */
    public void publish(DispatchDO dispatch, DispatchRuntimeEventDO event) {
        if (dispatch == null || event == null || dispatch.getId() == null || dispatch.getTenantId() == null) {
            return;
        }
        // The event is already persisted by the time we get here, so any projection or fan-out
        // failure must be swallowed: propagating it would 500 the report endpoint and make the
        // runtime retry an event that is durably recorded.
        try {
            DispatchLiveActivityVO.Action action = activityService.toAction(dispatch, event);
            if (action == null) {
                metrics.filteredIngest(event.getEventType());
                return;
            }
            long latencyMs = latencyMillis(event.getEventTime());
            metrics.reportLatency(latencyMs);
            if (redis == null) {
                metrics.publishFailed("redis_unavailable");
                return;
            }
            String channel = CHANNEL_PREFIX + dispatch.getId();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dispatchId", dispatch.getId());
            payload.put("agentId", action.getAgentId());
            payload.put("workitemId", dispatch.getWorkitemId());
            payload.put("sourceType", dispatch.executionSourceType().name());
            payload.put("lastSeq", action.getSeq());
            payload.put("reportLatencyMs", latencyMs);
            payload.put("action", action);
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("channel", channel);
            frame.put("type", FRAME_TYPE);
            frame.put("payload", payload);
            frame.put("timestamp", System.currentTimeMillis());
            try {
                redis.publish(channel, JSON.toJSONString(frame));
                metrics.published(action.getActionType());
            } catch (Exception e) {
                metrics.publishFailed("redis_publish");
                log.warn("live activity publish failed dispatchId={} eventId={}: {}",
                        dispatch.getId(), event.getEventId(), e.getMessage());
            }
        } catch (Exception e) {
            metrics.publishFailed("projection");
            log.warn("live activity projection failed dispatchId={} eventId={}: {}",
                    dispatch.getId(), event.getEventId(), e.getMessage());
        }
    }

    private static long latencyMillis(Date eventTime) {
        return eventTime == null ? 0L : Math.max(0L, System.currentTimeMillis() - eventTime.getTime());
    }
}
