package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.dispatch.dto.DispatchLiveActivityVO;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.mockito.Mockito.*;

class DispatchLiveActivityPublisherTest {

    private RedisManager redis;
    private DispatchLiveActivityService activityService;
    private DispatchLiveActivityMetrics metrics;
    private DispatchLiveActivityPublisher publisher;

    @BeforeEach
    void setUp() {
        redis = mock(RedisManager.class);
        activityService = mock(DispatchLiveActivityService.class);
        metrics = mock(DispatchLiveActivityMetrics.class);
        publisher = new DispatchLiveActivityPublisher(redis, activityService, metrics);
    }

    @Test
    void publishSendsToRedisChannel() {
        DispatchDO dispatch = buildDispatch(10L, 100L);
        DispatchRuntimeEventDO event = buildEvent("step.started", 1L);
        DispatchLiveActivityVO.Action action = new DispatchLiveActivityVO.Action();
        action.setSeq(1L);
        action.setActionType("SDLC_STEP");
        action.setAgentId(5L);
        when(activityService.toAction(dispatch, event)).thenReturn(action);

        publisher.publish(dispatch, event);

        verify(redis).publish(eq("dispatch:10"), anyString());
        verify(metrics).published("SDLC_STEP");
        verify(metrics).reportLatency(anyLong());
    }

    @Test
    void publishSkipsFilteredEvents() {
        DispatchDO dispatch = buildDispatch(10L, 100L);
        DispatchRuntimeEventDO event = buildEvent("agent.message", 1L);
        when(activityService.toAction(dispatch, event)).thenReturn(null);

        publisher.publish(dispatch, event);

        verify(redis, never()).publish(anyString(), anyString());
        verify(metrics).filteredIngest("agent.message");
    }

    @Test
    void publishSwallowsProjectionFailure() {
        DispatchDO dispatch = buildDispatch(10L, 100L);
        DispatchRuntimeEventDO event = buildEvent("step.started", 1L);
        when(activityService.toAction(dispatch, event)).thenThrow(new RuntimeException("boom"));

        publisher.publish(dispatch, event);

        verify(metrics).publishFailed("projection");
        verify(redis, never()).publish(anyString(), anyString());
    }

    @Test
    void publishHandlesRedisFailure() {
        DispatchDO dispatch = buildDispatch(10L, 100L);
        DispatchRuntimeEventDO event = buildEvent("step.started", 1L);
        DispatchLiveActivityVO.Action action = new DispatchLiveActivityVO.Action();
        action.setSeq(1L);
        action.setActionType("SDLC_STEP");
        action.setAgentId(5L);
        when(activityService.toAction(dispatch, event)).thenReturn(action);
        doThrow(new RuntimeException("connection refused")).when(redis).publish(anyString(), anyString());

        publisher.publish(dispatch, event);

        verify(metrics).publishFailed("redis_publish");
    }

    @Test
    void publishHandlesNullRedis() {
        DispatchLiveActivityPublisher noRedis = new DispatchLiveActivityPublisher(null, activityService, metrics);
        DispatchDO dispatch = buildDispatch(10L, 100L);
        DispatchRuntimeEventDO event = buildEvent("step.started", 1L);
        DispatchLiveActivityVO.Action action = new DispatchLiveActivityVO.Action();
        action.setSeq(1L);
        action.setActionType("SDLC_STEP");
        action.setAgentId(5L);
        when(activityService.toAction(dispatch, event)).thenReturn(action);

        noRedis.publish(dispatch, event);

        verify(metrics).publishFailed("redis_unavailable");
    }

    @Test
    void publishSkipsNullInputs() {
        publisher.publish(null, buildEvent("step.started", 1L));
        publisher.publish(buildDispatch(10L, 100L), null);
        verifyNoInteractions(redis);
    }

    private DispatchDO buildDispatch(long id, long tenantId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        dispatch.setWorkitemId(1001L);
        dispatch.setAgentId(5L);
        dispatch.setAttempt(1);
        dispatch.setSourceType("WORKITEM");
        return dispatch;
    }

    private DispatchRuntimeEventDO buildEvent(String eventType, long seq) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setEventId("evt-" + seq);
        event.setSeq(seq);
        event.setEventType(eventType);
        event.setEventTime(new Date());
        return event;
    }
}
