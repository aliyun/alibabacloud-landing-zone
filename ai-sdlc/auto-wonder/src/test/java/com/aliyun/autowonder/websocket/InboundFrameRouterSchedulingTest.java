package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchResultCoordinator;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.json.JSONObject;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboundFrameRouterSchedulingTest {
    @Test
    void slowResultAndTrailingProgressDoNotBlockAnotherDispatchAckOnTheSameConnection() throws Exception {
        var dispatches = mock(DispatchService.class);
        var presence = mock(PresenceManager.class);
        var coordinator = mock(DispatchResultCoordinator.class);
        var router = new InboundFrameRouter(dispatches, mock(ArtifactService.class), presence,
                mock(HandoffService.class), mock(DispatchDrainScheduler.class),
                mock(DispatchPauseService.class), mock(GuidanceService.class));
        router.setResultCoordinator(coordinator);
        var websocket = mock(Session.class);
        var remote = mock(RemoteEndpoint.Basic.class);
        when(websocket.getBasicRemote()).thenReturn(remote);
        when(websocket.isOpen()).thenReturn(true);
        var session = new ExecutorSession(1, 10, 100, websocket);
        var resultStarted = new CountDownLatch(1);
        var releaseResult = new CountDownLatch(1);
        var acked = new CountDownLatch(1);
        when(coordinator.handle(eq(100L), eq(1L), any())).thenAnswer(call -> {
            resultStarted.countDown();
            assertTrue(releaseResult.await(10, TimeUnit.SECONDS));
            return new DispatchResultCoordinator.Outcome(JSONObject.parseObject(
                    "{\"type\":\"TASK_RESULT_ACK\",\"dispatchId\":55,\"accepted\":true}"), null);
        });
        doAnswer(call -> { acked.countDown(); return null; }).when(dispatches).onAck(100, 56);
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(session)).thenReturn(true);
        try (var results = Executors.newSingleThreadExecutor();
             var controls = Executors.newSingleThreadExecutor();
             var receiver = Executors.newSingleThreadExecutor()) {
            router.setFrameScheduler(new InboundFrameScheduler(registry, results, controls, controls, 16));
            var received = receiver.submit(() -> {
                router.route(session, "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true}");
                router.route(session, "{\"type\":\"TASK_PROGRESS\",\"dispatchId\":55}");
                router.route(session, "{\"type\":\"TASK_ACK\",\"dispatchId\":56}");
            });
            try {
                assertTrue(resultStarted.await(2, TimeUnit.SECONDS));
                assertTrue(acked.await(1, TimeUnit.SECONDS), "another dispatch ACK must not wait for result processing");
                verify(remote, never()).sendText(anyString());
                verify(dispatches, never()).onProgress(eq(100L), eq(55L), any());
            } finally {
                releaseResult.countDown();
                received.get(5, TimeUnit.SECONDS);
            }
            verify(remote, timeout(2000)).sendText(contains("\"accepted\":true"));
            verify(dispatches, timeout(2000)).onProgress(eq(100L), eq(55L), any());
        }
    }

    @Test
    void blockedHeartbeatLeaseRenewalDoesNotDelayNewPresenceUpdatesOrAnotherAck() throws Exception {
        var dispatches = mock(DispatchService.class);
        var presence = mock(PresenceManager.class);
        when(presence.publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(PresenceManager.SessionMutationResult.APPLIED);
        var router = new InboundFrameRouter(dispatches, mock(ArtifactService.class), presence,
                mock(HandoffService.class), mock(DispatchDrainScheduler.class),
                mock(DispatchPauseService.class), mock(GuidanceService.class));
        var conversations = mock(com.aliyun.autowonder.conversation.AgentConversationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(router, "agentConversationService", conversations);
        var websocket = mock(Session.class);
        when(websocket.isOpen()).thenReturn(true);
        when(websocket.getId()).thenReturn("session");
        var session = new ExecutorSession(1, 10, 100, websocket);
        var registry = mock(SessionRegistry.class);
        when(registry.isCurrent(session)).thenReturn(true);
        var renewalStarted = new CountDownLatch(1);
        var releaseRenewal = new CountDownLatch(1);
        var acked = new CountDownLatch(1);
        doAnswer(call -> {
            renewalStarted.countDown();
            assertTrue(releaseRenewal.await(10, TimeUnit.SECONDS));
            return null;
        }).when(dispatches).renewActiveLeases(eq(100L), eq(1L), any());
        doAnswer(call -> { acked.countDown(); return null; }).when(dispatches).onAck(100, 56);
        try (var results = Executors.newSingleThreadExecutor();
             var controls = Executors.newSingleThreadExecutor();
             var heartbeats = Executors.newSingleThreadExecutor()) {
            router.setFrameScheduler(new InboundFrameScheduler(registry, results, controls, heartbeats, 16));
            try {
                router.route(session, "{\"type\":\"HEARTBEAT\",\"runningDispatchIds\":[55],\"runningConversationTurnIds\":[77]}");
                assertTrue(renewalStarted.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 20; i++) router.route(session,
                        "{\"type\":\"HEARTBEAT\",\"runningDispatchIds\":[],\"runningConversationTurnIds\":[88]}");
                router.route(session, "{\"type\":\"TASK_ACK\",\"dispatchId\":56}");
                assertTrue(acked.await(2, TimeUnit.SECONDS));
                verify(presence, times(21)).publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any());
                verify(dispatches, times(20)).recordRunningDispatches(1L, java.util.List.of());
                verify(websocket, never()).close(any());
            } finally {
                releaseRenewal.countDown();
            }
            verify(dispatches, timeout(2000).times(2)).renewActiveLeases(eq(100L), eq(1L), any());
            verify(conversations, timeout(2000)).recoverStaleTurnsForExecutor(100L, 1L, java.util.Set.of(88L));
            verify(conversations, never()).recoverStaleTurnsForExecutor(100L, 1L, java.util.Set.of(77L));
        }
    }

    @Test
    void replacedConnectionReceivesNoLateResultAckAndReplayUsesTheNewConnection() throws Exception {
        var coordinator = mock(DispatchResultCoordinator.class);
        var router = new InboundFrameRouter(mock(DispatchService.class), mock(ArtifactService.class),
                mock(PresenceManager.class), mock(HandoffService.class), mock(DispatchDrainScheduler.class),
                mock(DispatchPauseService.class), mock(GuidanceService.class));
        router.setResultCoordinator(coordinator);
        var registry = new SessionRegistry();
        var oldSocket = mock(Session.class);
        var newSocket = mock(Session.class);
        var oldRemote = mock(RemoteEndpoint.Basic.class);
        var newRemote = mock(RemoteEndpoint.Basic.class);
        when(oldSocket.getId()).thenReturn("old");
        when(newSocket.getId()).thenReturn("new");
        when(oldSocket.isOpen()).thenReturn(true);
        when(newSocket.isOpen()).thenReturn(true);
        when(oldSocket.getBasicRemote()).thenReturn(oldRemote);
        when(newSocket.getBasicRemote()).thenReturn(newRemote);
        var oldSession = new ExecutorSession(1, 10, 100, oldSocket);
        var newSession = new ExecutorSession(1, 10, 100, newSocket);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(coordinator.handle(eq(100L), eq(1L), any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return new DispatchResultCoordinator.Outcome(JSONObject.parseObject(
                    "{\"type\":\"TASK_RESULT_ACK\",\"dispatchId\":55,\"accepted\":true}"), null);
        });
        String result = "{\"type\":\"TASK_RESULT\",\"dispatchId\":55,\"success\":true}";
        try (var results = Executors.newSingleThreadExecutor();
             var controls = Executors.newSingleThreadExecutor()) {
            router.setFrameScheduler(new InboundFrameScheduler(registry, results, controls, controls, 16));
            registry.register(oldSession);
            try {
                router.route(oldSession, result);
                assertTrue(started.await(2, TimeUnit.SECONDS));
                registry.register(newSession);
                router.route(newSession, result);
            } finally {
                release.countDown();
            }
            verify(newRemote, timeout(2000)).sendText(contains("\"accepted\":true"));
            verify(oldRemote, never()).sendText(anyString());
            verify(coordinator, times(2)).handle(eq(100L), eq(1L), any());
        }
    }
}
