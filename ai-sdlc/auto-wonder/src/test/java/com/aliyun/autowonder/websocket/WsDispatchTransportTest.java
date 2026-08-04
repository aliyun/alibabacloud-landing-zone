package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.ResumeDescriptor;
import com.aliyun.autowonder.dispatch.ResumeCheckpointCandidate;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WsDispatchTransportTest {

    private SessionRegistry sessionRegistry;
    private RedisManager redisManager;
    private NodeIdentity nodeIdentity;
    private WsDispatchTransport transport;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(SessionRegistry.class);
        redisManager = mock(RedisManager.class);
        nodeIdentity = mock(NodeIdentity.class);
        when(nodeIdentity.getNodeId()).thenReturn("local-node");
        transport = new WsDispatchTransport(sessionRegistry, redisManager, nodeIdentity);
    }

    private DispatchDO dispatch(long id, long executorId) {
        DispatchDO d = new DispatchDO();
        d.setId(id);
        d.setExecutorId(executorId);
        d.setTenantId(100L);
        d.setWorkitemId(500L);
        d.setSdlcStepId(400164L);
        d.setAttempt(1);
        return d;
    }

    private TaskPackageResult pkg() {
        return new TaskPackageResult("oss-ref", "abc123", 2048L, "https://oss/dl",
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
    }

    @Test
    void sendsLocallyWhenSessionOpenExists() throws IOException {
		DispatchMcpTokenService tokens = mock(DispatchMcpTokenService.class);
		when(tokens.issue(any())).thenReturn("awdispatch_signed");
		transport = new WsDispatchTransport(sessionRegistry, redisManager, nodeIdentity, null, tokens);
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(ws.getBasicRemote()).thenReturn(basic);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));

        transport.dispatch(dispatch(99L, 5L), pkg());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(basic).sendText(cap.capture());
        String sent = cap.getValue();
        assertTrue(sent.contains("\"dispatchId\":99"));
        assertTrue(sent.contains("\"downloadUrl\":\"https://oss/dl\""));
        assertTrue(sent.contains("\"executorId\":5"));
        assertTrue(sent.contains("\"tenantId\":100"));
        assertTrue(sent.contains("\"workitemId\":500"));
        assertTrue(sent.contains("\"sdlcStepId\":400164"));
        assertTrue(sent.contains("\"attempt\":1"));
        assertTrue(sent.contains("\"checksum\":\"sha256:deadbeef"));
        assertTrue(sent.contains("\"checksumScope\":\"zip_archive\""));
        assertTrue(sent.contains("\"packageId\":"));
        assertTrue(sent.contains("\"packageRefreshPath\":\"/api/daemon/dispatches/99/package-url\""));
        assertTrue(sent.contains("\"artifactUploadPath\":\"/api/daemon/dispatches/99/artifacts\""));
        assertTrue(sent.contains("\"checkpointUploadPath\":\"/api/daemon/dispatches/99/checkpoint\""));
		assertTrue(sent.contains("\"dispatchMcpToken\":\"awdispatch_signed\""));
        InOrder order = inOrder(tokens, basic);
        order.verify(tokens).issue(any());
        order.verify(basic).sendText(anyString());
        verify(redisManager, never()).publish(anyString(), anyString());
    }

    @Test
    void includesNativeSessionAndCheckpointRecoveryDescriptor() {
        DispatchCheckpointService checkpoints = mock(DispatchCheckpointService.class);
        DispatchDO dispatch = dispatch(99L, 5L);
        when(checkpoints.descriptor(dispatch)).thenReturn(new ResumeDescriptor(
                "RECOVERY", 88L, "codex", "thread-88", "https://oss/checkpoint",
                "sha256:abc", 7L, java.util.List.of(
                        new ResumeCheckpointCandidate("https://oss/checkpoint", "sha256:abc", 7L),
                        new ResumeCheckpointCandidate("https://oss/previous", "sha256:def", 6L))));
        transport = new WsDispatchTransport(sessionRegistry, redisManager, nodeIdentity, checkpoints);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        transport.dispatch(dispatch, pkg());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq("node:dispatch:broadcast"), cap.capture());
        String sent = cap.getValue();
        assertTrue(sent.contains("\"resumeMode\":\"RECOVERY\""));
        assertTrue(sent.contains("\"resumeFromDispatchId\":88"));
        assertTrue(sent.contains("\"resumeSessionId\":\"thread-88\""));
        assertTrue(sent.contains("\"resumeCheckpointSeq\":7"));
        assertTrue(sent.contains("\"resumeCheckpointCandidates\""));
        assertTrue(sent.contains("\"checkpointSeq\":6"));
    }

    @Test
    void publishesToBroadcastWhenNotLocal() {
        DispatchMcpTokenService tokens = mock(DispatchMcpTokenService.class);
        when(tokens.issue(any())).thenReturn("awdispatch_remote_signed");
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, tokens);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        transport.dispatch(dispatch(99L, 5L), pkg());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq("node:dispatch:broadcast"), cap.capture());
        assertTrue(cap.getValue().contains("\"dispatchId\":99"));
        assertTrue(cap.getValue().contains("\"executorId\":5"));
        assertTrue(cap.getValue().contains(
                "\"dispatchMcpToken\":\"awdispatch_remote_signed\""));
        InOrder order = inOrder(tokens, redisManager);
        order.verify(tokens).issue(any());
        order.verify(redisManager).publish(eq("node:dispatch:broadcast"), anyString());
    }

    @Test
    void closedSessionFallsBackToPublish() {
        Session ws = mock(Session.class);
        when(ws.isOpen()).thenReturn(false);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));

        transport.dispatch(dispatch(99L, 5L), pkg());

        verify(redisManager).publish(eq("node:dispatch:broadcast"), anyString());
    }
}
