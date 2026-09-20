package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.ResumeDescriptor;
import com.aliyun.autowonder.dispatch.ResumeCheckpointCandidate;
import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
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
import java.util.Map;

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
        d.setAgentId(7L);
        d.setAgentVersionId(8L);
        d.setIdempotencyKey("dispatch-identity");
        d.setAttempt(1);
        return d;
    }

    private TaskPackageResult pkg() {
        return pkg(true);
    }

    private TaskPackageResult pkg(boolean requiresHookProtocol) {
		return pkg(requiresHookProtocol, false);
	}

	private TaskPackageResult pkg(boolean requiresHookProtocol, boolean requiresToolHookProtocol) {
        return new TaskPackageResult("oss-ref", "abc123", 2048L, "https://oss/dl",
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                "autowonder-server", "sha256:key", "signed-envelope", "ed25519",
                "public-key", "2026-08-07T04:00:00Z", true, false, false,
                requiresHookProtocol, requiresToolHookProtocol);
    }

    private TaskPackageResult unsignedHookPkg() {
        return new TaskPackageResult("oss-ref", "abc123", 2048L, "https://oss/dl",
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                null, null, null, null, null, null,
                true, false, false, true);
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
        assertTrue(sent.contains("\"agentId\":7"));
        assertTrue(sent.contains("\"agentVersionId\":8"));
        assertTrue(sent.contains("\"idempotencyKey\":\"dispatch-identity\""));
        assertTrue(sent.contains("\"attempt\":1"));
        assertTrue(sent.contains("\"checksum\":\"sha256:deadbeef"));
        assertTrue(sent.contains("\"checksumScope\":\"zip_archive\""));
        assertTrue(sent.contains("\"issuer\":\"autowonder-server\""));
        assertTrue(sent.contains("\"signatureAlgorithm\":\"ed25519\""));
        assertTrue(sent.contains("\"allowCommit\":true"));
        assertTrue(sent.contains("\"allowPush\":false"));
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
    void warnsButDispatchesWhenHookProtocolMissing() {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.TASK_PACKAGE_SIGNATURE_V1))
                .thenReturn(true);
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.TASK_PACKAGE_HOOKS_V1))
                .thenReturn(false);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        assertDoesNotThrow(() -> transport.dispatch(dispatch(99L, 5L), pkg()));

        verify(redisManager).publish(eq("node:dispatch:broadcast"), anyString());
    }

    @Test
    void rejectsOnlyToolHookPackagesWhenExecutorLacksToolHookProtocol() {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.TASK_PACKAGE_SIGNATURE_V1))
                .thenReturn(true);
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.TASK_PACKAGE_HOOKS_V1))
                .thenReturn(true);
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.TASK_PACKAGE_TOOL_HOOKS_V1))
                .thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transport.dispatch(dispatch(99L, 5L), pkg(true, true)));

        assertTrue(error.getMessage().contains("blocking tool hooks"));
        verify(redisManager, never()).publish(anyString(), anyString());
    }

    @Test
    void dispatchesUnsignedHookPackageWithoutProtocolNegotiation() {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        assertDoesNotThrow(() -> transport.dispatch(dispatch(99L, 5L), unsignedHookPkg()));

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq("node:dispatch:broadcast"), frame.capture());
        verifyNoInteractions(presence);
        assertFalse(frame.getValue().contains("signatureRef"));
        assertFalse(frame.getValue().contains("signatureAlgorithm"));
        assertTrue(frame.getValue().contains("\"checksumScope\":\"zip_archive\""));
    }

    @Test
    void legacyExecutorWithoutAnyProtocolStillReceivesPackageWithoutToolHooks() {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        assertDoesNotThrow(() -> transport.dispatch(dispatch(99L, 5L), pkg(false)));

        verify(redisManager).publish(eq("node:dispatch:broadcast"), anyString());
        verify(presence, never()).supportsProtocolFeature(
                5L, WsDispatchTransport.TASK_PACKAGE_TOOL_HOOKS_V1);
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

    @Test
    void scheduledDispatchKeepsLegacyWorkitemIdWithoutAddingSourceTypeToWire() {
        DispatchDO scheduled = dispatch(99L, 5L);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        scheduled.setWorkitemId(41001L);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        transport.dispatch(scheduled, pkg());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq("node:dispatch:broadcast"), json.capture());
        com.alibaba.fastjson.JSONObject frame = com.alibaba.fastjson.JSON.parseObject(json.getValue());
        assertEquals(41001L, frame.getLongValue("workitemId"));
        assertFalse(frame.containsKey("sourceType"));
    }

    @Test
    void debugLogDirectiveIsSentOnlyToDebugCapableExecutors() throws IOException {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(ws.getBasicRemote()).thenReturn(basic);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.DEBUG_LOG_V1))
                .thenReturn(true);
        DispatchDO d = dispatch(99L, 5L);
        d.setDebugLogEnabled(true);

        transport.dispatch(d, pkg());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(basic).sendText(cap.capture());
        com.alibaba.fastjson.JSONObject frame = com.alibaba.fastjson.JSON.parseObject(cap.getValue());
        com.alibaba.fastjson.JSONObject debugLog = frame.getJSONObject("debugLog");
        assertNotNull(debugLog);
        assertTrue(debugLog.getBooleanValue("enabled"));
        assertEquals(209715200L, debugLog.getLongValue("maxBytes"));
    }

    @Test
    void debugLogDirectiveIsOmittedWithoutProtocolFeature() throws IOException {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(ws.getBasicRemote()).thenReturn(basic);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.DEBUG_LOG_V1))
                .thenReturn(false);
        DispatchDO d = dispatch(99L, 5L);
        d.setDebugLogEnabled(true);

        transport.dispatch(d, pkg());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(basic).sendText(cap.capture());
        assertFalse(cap.getValue().contains("debugLog"));
    }

    @Test
    void debugLogDirectiveIsOmittedWhenFlagFrozenOff() throws IOException {
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(ws.getBasicRemote()).thenReturn(basic);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));
        DispatchDO d = dispatch(99L, 5L);
        d.setDebugLogEnabled(null);

        transport.dispatch(d, pkg());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(basic).sendText(cap.capture());
        assertFalse(cap.getValue().contains("debugLog"));
        verify(presence, never()).supportsProtocolFeature(5L, WsDispatchTransport.DEBUG_LOG_V1);
    }

    @Test
    void debugLogCapabilityProbeFailureDoesNotBlockDispatch() throws IOException {
        // best-effort 不变量：debugLog 组装任何异常不得影响 TASK_DISPATCH 帧下发
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(
                sessionRegistry, redisManager, nodeIdentity, null, null, presence);
        Session ws = mock(Session.class);
        RemoteEndpoint.Basic basic = mock(RemoteEndpoint.Basic.class);
        when(ws.getBasicRemote()).thenReturn(basic);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L))
                .thenReturn(new ExecutorSession(5L, 10L, 100L, ws));
        when(presence.supportsProtocolFeature(5L, WsDispatchTransport.DEBUG_LOG_V1))
                .thenThrow(new IllegalStateException("redis unavailable"));
        DispatchDO d = dispatch(99L, 5L);
        d.setDebugLogEnabled(true);

        assertDoesNotThrow(() -> transport.dispatch(d, pkg()));

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(basic).sendText(cap.capture());
        String sent = cap.getValue();
        assertFalse(sent.contains("debugLog"));
        assertTrue(sent.contains("\"dispatchId\":99"));
    }

    @Test
    void resolvesAndSendsLatestCompleteEnvironmentSnapshotOnEveryDispatch() {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(sessionRegistry, redisManager, nodeIdentity, null, null,
                presence, null, resolver);
        when(resolver.resolve(100L, 8L))
                .thenReturn(Map.of("ALPHA", "first", "ZETA", "last"), Map.of("ALPHA", "updated"));
        when(presence.supportsProtocolFeature(
                5L, WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1)).thenReturn(true);
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        transport.dispatch(dispatch(99L, 5L), pkg(false));
        transport.dispatch(dispatch(100L, 5L), pkg(false));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager, times(2)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL),
                payload.capture());
        assertEquals(Map.of("ALPHA", "first", "ZETA", "last"),
                com.alibaba.fastjson.JSON.parseObject(payload.getAllValues().get(0))
                        .getJSONObject("environmentVariables").getInnerMap());
        assertEquals("updated", com.alibaba.fastjson.JSON.parseObject(payload.getAllValues().get(1))
                .getJSONObject("environmentVariables").getString("ALPHA"));
        verify(resolver, times(2)).resolve(100L, 8L);
    }

    @Test
    void rejectsNonEmptySnapshotWhenExecutorDoesNotSupportEnvironmentProtocol() {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(sessionRegistry, redisManager, nodeIdentity, null, null,
                presence, null, resolver);
        when(resolver.resolve(100L, 8L)).thenReturn(Map.of("TOKEN", "secret-value"));

        ExecutorProtocolCompatibilityException error = assertThrows(
                ExecutorProtocolCompatibilityException.class,
                () -> transport.dispatch(dispatch(99L, 5L), pkg(false)));

        assertEquals(WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1,
                error.getRequiredFeature());
        assertFalse(error.getMessage().contains("secret-value"));
        verify(redisManager, never()).publish(anyString(), anyString());
    }

    @Test
    void sendsExplicitEmptySnapshotToLegacyExecutor() {
        AgentEnvironmentVariableResolver resolver = mock(AgentEnvironmentVariableResolver.class);
        PresenceManager presence = mock(PresenceManager.class);
        transport = new WsDispatchTransport(sessionRegistry, redisManager, nodeIdentity, null, null,
                presence, null, resolver);
        when(resolver.resolve(100L, 8L)).thenReturn(Map.of());
        when(sessionRegistry.findByExecutorId(5L)).thenReturn(null);

        transport.dispatch(dispatch(99L, 5L), pkg(false));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        assertTrue(payload.getValue().contains("\"environmentVariables\":{}"));
        verify(presence, never()).supportsProtocolFeature(
                5L, WsDispatchTransport.AGENT_ENVIRONMENT_VARIABLES_V1);
    }
}
