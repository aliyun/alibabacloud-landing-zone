package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.executor.ExecutorUpdateService;
import com.aliyun.autowonder.guidance.GuidanceService;
import javax.websocket.Session;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Routing of the executor-upgrade frames added for 一键更新/自动升级. */
class InboundFrameRouterUpgradeTest {

    private static String heartbeat() {
        return "{\"type\":\"HEARTBEAT\",\"version\":\"0.2.150\","
                + "\"maxConcurrentDispatches\":1,"
                + "\"protocolFeatures\":[\"dispatch_inventory_v1\"],"
                + "\"runningDispatchIds\":[],\"ownedDispatchIds\":[],"
                + "\"runningConversationTurnIds\":[],\"dispatchInventoryReady\":true}";
    }

    private final PresenceManager presenceManager = mock(PresenceManager.class);
    private final ExecutorUpdateService updateService = mock(ExecutorUpdateService.class);
    private final Session websocket = mock(Session.class);
    private final ExecutorSession session = new ExecutorSession(9L, 3L, 1L, websocket);

    private final InboundFrameRouter router = newRouter();

    private InboundFrameRouter newRouter() {
        InboundFrameRouter router = new InboundFrameRouter(mock(DispatchService.class),
                mock(ArtifactService.class), presenceManager, mock(HandoffService.class),
                mock(DispatchDrainScheduler.class), mock(DispatchPauseService.class),
                mock(GuidanceService.class));
        router.setUpdateService(updateService);
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(PresenceManager.SessionMutationResult.APPLIED);
        return router;
    }

    @Test
    void routesUpgradeResultToTheUpdateService() {
        router.route(session, "{\"type\":\"EXECUTOR_UPGRADE_RESULT\",\"requestId\":\"req-1\","
                + "\"phase\":\"draining\"}");

        ArgumentCaptor<JSONObject> frame = ArgumentCaptor.forClass(JSONObject.class);
        verify(updateService).onUpgradeResult(eq(session), frame.capture());
        assertEquals("req-1", frame.getValue().getString("requestId"));
        assertEquals("draining", frame.getValue().getString("phase"));
    }

    @Test
    void heartbeatRunsTheUpgradeHookAfterTheVersionIsRecorded() {
        router.route(session, heartbeat());

        // The hook reads the version that recordVersion just stored, so the order is the contract.
        var order = inOrder(presenceManager, updateService);
        order.verify(presenceManager).publishHeartbeat(eq(9L), eq(3L), any(), any(), any(),
                eq("0.2.150"), any());
        order.verify(updateService).onHeartbeat(eq(session), any(JSONObject.class));
    }

    @Test
    void aFailingUpgradeHookMustNotCostTheExecutorItsHeartbeat() throws Exception {
        doThrow(new IllegalStateException("db down")).when(updateService).onHeartbeat(any(), any());

        router.route(session, heartbeat());

        // Liveness is primary: the session stays open and presence was still recorded.
        verify(presenceManager).publishHeartbeat(eq(9L), eq(3L), any(), any(), any(),
                eq("0.2.150"), any());
        verify(websocket, never()).close();
    }

    @Test
    void aRejectedHeartbeatSkipsTheUpgradeHookEntirely() {
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(PresenceManager.SessionMutationResult.DELETED);

        router.route(session, heartbeat());

        verify(updateService, never()).onHeartbeat(any(), any());
    }

    @Test
    void unrelatedFramesNeverReachTheUpdateService() {
        router.route(session, "{\"type\":\"EXECUTOR_RESTART_RESULT\",\"requestId\":\"r\",\"status\":\"FAILED\"}");
        router.route(session, "{\"type\":\"SOMETHING_NEW\"}");
        router.route(session, "not json at all");

        verify(updateService, never()).onUpgradeResult(any(), any());
        verify(updateService, never()).onHeartbeat(any(), any());
    }
}
