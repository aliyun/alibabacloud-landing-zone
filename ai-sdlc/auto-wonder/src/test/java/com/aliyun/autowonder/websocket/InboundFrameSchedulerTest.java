package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.websocket.CloseReason;
import javax.websocket.Session;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboundFrameSchedulerTest {
    private static ExecutorSession session(long tenant, long executor, String id) {
        Session ws = mock(Session.class);
        when(ws.getId()).thenReturn(id);
        when(ws.isOpen()).thenReturn(true);
        return new ExecutorSession(executor, 10, tenant, ws);
    }

    @Test
    void boundsQueuedFramesAndRecoversCapacityWithoutRunningRejectedWorkOnTheReader() throws Exception {
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(any())).thenReturn(true);
        var tasks = new ArrayDeque<Runnable>();
        var scheduler = new InboundFrameScheduler(registry, tasks::add, tasks::add, tasks::add, 1);
        var first = session(100, 1, "first");
        var rejected = session(100, 2, "rejected");
        var completed = new ArrayList<String>();
        scheduler.dispatch(first, 55, "TASK_RESULT", () -> completed.add("first"));
        scheduler.dispatch(rejected, 56, "TASK_RESULT", () -> completed.add("rejected"));
        assertTrue(completed.isEmpty());
        verify(rejected.getSession()).close(argThat(reason -> reason.getCloseCode().getCode() == 1013));
        tasks.remove().run();
        scheduler.dispatch(first, 57, "TASK_ACK", () -> completed.add("next"));
        tasks.remove().run();
        assertEquals(List.of("first", "next"), completed);
    }

    @Test
    void executorRejectionReleasesAdmissionAndNeverFabricatesAReceipt() throws Exception {
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(any())).thenReturn(true);
        var rejecting = new AtomicBoolean(true);
        var tasks = new ArrayDeque<Runnable>();
        Executor pool = task -> {
            if (rejecting.get()) throw new RejectedExecutionException("full");
            tasks.add(task);
        };
        var scheduler = new InboundFrameScheduler(registry, pool, pool, pool, 1);
        var old = session(100, 1, "old");
        scheduler.dispatch(old, 55, "TASK_RESULT", () -> fail("rejected action must not execute"));
        verify(old.getSession()).close(any(CloseReason.class));
        verify(old.getSession(), never()).getBasicRemote();
        rejecting.set(false);
        var replacement = session(100, 1, "new");
        var replayed = new AtomicBoolean();
        scheduler.dispatch(replacement, 55, "TASK_RESULT", () -> replayed.set(true));
        tasks.remove().run();
        assertTrue(replayed.get());
    }

    @Test
    void queuesSkipReplacedSessionAndRetainNewSessionOrderAndTenantIsolation() {
        var registry = new SessionRegistry();
        var tasks = new ArrayDeque<Runnable>();
        var scheduler = new InboundFrameScheduler(registry, tasks::add, tasks::add, tasks::add, 8);
        var old = session(100, 1, "old");
        var replacement = session(100, 1, "new");
        var otherTenant = session(200, 2, "other");
        registry.register(old);
        registry.register(otherTenant);
        var events = new ArrayList<String>();
        scheduler.dispatch(old, 55, "TASK_RESULT", () -> events.add("stale"));
        registry.register(replacement);
        scheduler.dispatch(replacement, 55, "TASK_ACK", () -> events.add("new-ack"));
        scheduler.dispatch(otherTenant, 55, "TASK_ACK", () -> events.add("other-tenant"));
        scheduler.dispatch(replacement, 55, "TASK_PROGRESS", () -> events.add("new-progress"));
        while (!tasks.isEmpty()) tasks.remove().run();
        assertEquals(List.of("other-tenant", "new-ack", "new-progress"), events);
    }

    @Test
    void failedFrameDoesNotStrandFollowingFramesAndEachKeepsItsOwnTraceContext() {
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(any())).thenReturn(true);
        var tasks = new ArrayDeque<Runnable>();
        var scheduler = new InboundFrameScheduler(registry, tasks::add, tasks::add, tasks::add, 4);
        var session = session(100, 1, "current");
        var contexts = new ArrayList<String>();
        try {
            MDC.put("traceId", "first");
            scheduler.dispatch(session, 55, "TASK_RESULT", () -> {
                contexts.add(MDC.get("traceId"));
                throw new IllegalStateException("retryable fixture failure");
            });
            MDC.put("traceId", "second");
            scheduler.dispatch(session, 55, "TASK_RESULT", () -> contexts.add(MDC.get("traceId")));
            MDC.put("traceId", "caller");
            while (!tasks.isEmpty()) tasks.remove().run();
            assertEquals(List.of("first", "second"), contexts);
            assertEquals("caller", MDC.get("traceId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void heartbeatSideEffectsKeepOnlyTheLatestWaitingSnapshot() {
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(any())).thenReturn(true);
        var tasks = new ArrayDeque<Runnable>();
        var scheduler = new InboundFrameScheduler(registry, tasks::add, tasks::add, tasks::add, 2);
        var session = session(100, 1, "current");
        var values = new ArrayList<Integer>();
        for (int i = 0; i < 100; i++) {
            int value = i;
            scheduler.heartbeat(session, () -> values.add(value));
        }
        while (!tasks.isEmpty()) tasks.remove().run();
        assertEquals(List.of(0, 99), values);
    }

    @Test
    void traceBacklogDoesNotConsumeHeartbeatAdmissionAndBothRemainBounded() throws Exception {
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(any())).thenReturn(true);
        var frames = new ArrayDeque<Runnable>();
        var heartbeats = new ArrayDeque<Runnable>();
        var scheduler = new InboundFrameScheduler(registry, frames::add, frames::add, heartbeats::add, 1);
        var noisy = session(100, 1, "noisy");
        var healthy = session(100, 2, "healthy");
        var excess = session(100, 3, "excess");
        var completed = new ArrayList<String>();
        scheduler.dispatch(noisy, 55, "TASK_PROGRESS", () -> completed.add("progress"));
        scheduler.heartbeat(healthy, () -> completed.add("heartbeat"));
        verify(healthy.getSession(), never()).close(any(CloseReason.class));
        assertEquals(1, frames.size());
        assertEquals(1, heartbeats.size());
        assertTrue(completed.isEmpty());
        scheduler.heartbeat(excess, () -> fail("heartbeat lane must remain bounded"));
        verify(excess.getSession()).close(argThat(reason -> reason.getCloseCode().getCode() == 1013));
        frames.remove().run();
        scheduler.dispatch(noisy, 56, "TASK_ACK", () -> completed.add("ack"));
        frames.remove().run();
        heartbeats.remove().run();
        scheduler.heartbeat(healthy, () -> completed.add("next-heartbeat"));
        heartbeats.remove().run();
        assertEquals(List.of("progress", "ack", "heartbeat", "next-heartbeat"), completed);
    }
}
