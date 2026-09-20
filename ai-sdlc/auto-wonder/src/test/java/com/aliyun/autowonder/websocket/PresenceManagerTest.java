package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Set;

class PresenceManagerTest {

    private RedisManager redisManager;
    private NodeIdentity nodeIdentity;
    private PresenceManager presenceManager;

    @BeforeEach
    void setUp() {
        redisManager = mock(RedisManager.class);
        nodeIdentity = mock(NodeIdentity.class);
        when(nodeIdentity.getNodeId()).thenReturn("node-abc");
        presenceManager = new PresenceManager(redisManager, nodeIdentity);
    }

    @Test
    void announceSessionPublishesCrossNodeReconciliation() {
        assertTrue(presenceManager.announceSession(1L, "session-new"));

        verify(redisManager).setString("exec:session:1", "session-new");
        verify(redisManager).publish(eq("node:dispatch:broadcast"),
                argThat(message -> message.contains("SESSION_REPLACED")
                        && message.contains("\"executorId\":1")));
    }

    @Test
    void staleSessionCannotPublishHeartbeatState() {
        when(redisManager.exists("exec:deleted:1")).thenReturn(false);
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        ExecutorDispatchSnapshot stale = new ExecutorDispatchSnapshot(
                "session-old", 10, true, true, Set.of(55L), Set.of(55L), Set.of(),
                Set.of(), null, 123L);

        assertEquals(PresenceManager.SessionMutationResult.STALE_SESSION,
                presenceManager.publishHeartbeat(1L, 10L, "session-old", stale,
                        java.util.List.of("dispatch_inventory_v1"), "1.0", "model"));

        verify(redisManager, never()).eval(anyString(), anyList(), anyList());
        verify(redisManager, never()).set(any(), any(), anyInt());
    }

    @Test
    void currentSessionPublishesHeartbeatWithoutCrossSlotLua() {
        when(redisManager.exists("exec:deleted:1")).thenReturn(false);
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.set(any(), any(), anyInt())).thenReturn(true);
        ExecutorDispatchSnapshot current = new ExecutorDispatchSnapshot(
                "session-current", 10, true, true, Set.of(55L), Set.of(55L), Set.of(),
                Set.of(), null, 123L);

        assertEquals(PresenceManager.SessionMutationResult.APPLIED,
                presenceManager.publishHeartbeat(1L, 10L, "session-current", current,
                        java.util.List.of("dispatch_inventory_v1"), "1.0", "model"));

        verify(redisManager, never()).eval(anyString(), anyList(), anyList());
        verify(redisManager).set(eq("exec:dispatch-snapshot:1"),
                argThat(value -> value instanceof ExecutorDispatchSnapshot stored
                        && stored.sessionId().equals("session-current")
                        && stored.protocolFeatures().equals(Set.of("dispatch_inventory_v1"))),
                eq(90));
        verify(redisManager).setWithExpire("exec:online:1", "node-abc", 90L);
        verify(redisManager).setWithExpire("exec:capacity:1", "10", 90L);
        verify(redisManager).replaceSetWithExpire("exec:protocol-features:1",
                java.util.List.of("dispatch_inventory_v1"), 90L);
        verify(redisManager, never()).setWithExpire(eq("exec:session:1"), anyString(), anyLong());
    }

    @Test
    void heartbeatCannotRestoreSessionOwnershipAfterReplacement() {
        when(redisManager.exists("exec:deleted:1")).thenReturn(false);
        when(redisManager.getString("exec:session:1"))
                .thenReturn("session-old", "session-new");
        when(redisManager.set(any(), any(), anyInt())).thenReturn(true);
        ExecutorDispatchSnapshot old = new ExecutorDispatchSnapshot(
                "session-old", 10, true, true, Set.of(), Set.of(), Set.of(), Set.of(), null, 123L);

        assertEquals(PresenceManager.SessionMutationResult.APPLIED,
                presenceManager.publishHeartbeat(1L, 10L, "session-old", old,
                        java.util.List.of("dispatch_inventory_v1"), "1.0", "model"));

        verify(redisManager, never()).setString(eq("exec:session:1"), anyString());
        verify(redisManager, never()).setWithExpire(eq("exec:session:1"), anyString(), anyLong());
    }

    @Test
    void deletedExecutorCannotPublishHeartbeatState() {
        when(redisManager.exists("exec:deleted:1")).thenReturn(true);
        ExecutorDispatchSnapshot current = new ExecutorDispatchSnapshot(
                "session-current", 10, true, true, Set.of(), Set.of(), Set.of(), Set.of(), null, 123L);

        assertEquals(PresenceManager.SessionMutationResult.DELETED,
                presenceManager.publishHeartbeat(1L, 10L, "session-current", current,
                        java.util.List.of("dispatch_inventory_v1"), "1.0", "model"));

        verify(redisManager, never()).eval(anyString(), anyList(), anyList());
        verify(redisManager, never()).set(any(), any(), anyInt());
    }

    @Test
    void staleSessionCannotUnregisterNewPresence() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");

        assertFalse(presenceManager.unregisterIfCurrent(1L, 10L, "session-old"));

        verify(redisManager, never()).del("exec:online:1");
    }

    @Test
    void currentSessionDisconnectLeavesTtlPresenceForReplacementSafety() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");

        assertTrue(presenceManager.unregisterIfCurrent(1L, 10L, "session-current"));

        verify(redisManager).setWithExpire("exec:closed-session:1:session-current", "1", 90L);
        verify(redisManager, never()).del("exec:online:1");
    }

    @Test
    void protocolErrorIsVisibleUntilAValidHeartbeatClearsIt() {
        when(redisManager.getString("exec:protocol-error:1:session-1"))
                .thenReturn("EXECUTOR_PROTOCOL_INCOMPATIBLE");
        when(redisManager.getString("exec:session:1")).thenReturn("session-1");

        assertTrue(presenceManager.recordProtocolError(1L, 10L, "session-1",
                "EXECUTOR_PROTOCOL_INCOMPATIBLE"));
        assertEquals("EXECUTOR_PROTOCOL_INCOMPATIBLE", presenceManager.currentProtocolError(1L));
        assertTrue(presenceManager.clearProtocolError(1L, 10L, "session-1"));

        verify(redisManager).setWithExpire("exec:protocol-error:1:session-1",
                "EXECUTOR_PROTOCOL_INCOMPATIBLE", 90L);
        verify(redisManager).sadd("agent:execs:10", "1");
        verify(redisManager).del("exec:protocol-error:1:session-1");
    }

    @Test
    void firstInvalidHeartbeatRegistersAgentDiagnosticMembership() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-1");

        assertTrue(presenceManager.recordProtocolError(1L, 10L, "session-1",
                "EXECUTOR_PROTOCOL_INCOMPATIBLE"));

        verify(redisManager).sadd("agent:execs:10", "1");
    }

    @Test
    void staleSessionCannotPublishProtocolErrorForReconnectedExecutor() {
        when(redisManager.getString("exec:session:1")).thenReturn("current-session");

        assertFalse(presenceManager.recordProtocolError(1L, 10L, "stale-session",
                "EXECUTOR_PROTOCOL_INCOMPATIBLE"));

        verify(redisManager, never()).setWithExpire(eq("exec:protocol-error:1:stale-session"),
                anyString(), anyLong());
    }

    @Test
    void staleSessionCannotClearProtocolErrorForReconnectedExecutor() {
        when(redisManager.getString("exec:session:1")).thenReturn("current-session");

        assertFalse(presenceManager.clearProtocolError(1L, 10L, "stale-session"));

        verify(redisManager, never()).del("exec:protocol-error:1:stale-session");
    }

    @Test
    void protocolErrorFromReplacedSessionIsIgnored() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-new");
        when(redisManager.smembers("agent:execs:10")).thenReturn(Set.of("1"));
        when(redisManager.getString("exec:protocol-error:1:session-old"))
                .thenReturn("EXECUTOR_PROTOCOL_INCOMPATIBLE");

        assertEquals(null, presenceManager.currentProtocolError(1L));
        assertEquals(null, presenceManager.currentAgentProtocolError(10L));
    }

    @Test
    void unregisterRemovesAllKeys() {
        presenceManager.unregister(1L, 10L);

        verify(redisManager).del("exec:online:1");
        verify(redisManager).del("exec:route:1");
        verify(redisManager).del("exec:capacity:1");
        verify(redisManager).del("exec:session:1");
        verify(redisManager).del("exec:version:1");
        verify(redisManager).del("exec:model:1");
        verify(redisManager).del("exec:dispatch-snapshot:1");
        verify(redisManager).srem("agent:execs:10", "1");
    }

    @Test
    void isExecutorOnlineReadsOnlinePresenceKey() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.exists("exec:online:1")).thenReturn(true);
        when(redisManager.get("exec:dispatch-snapshot:1"))
                .thenReturn(snapshot(Set.of(), Set.of()));

        assertTrue(presenceManager.isExecutorOnline(1L));
    }

    @Test
    void closedCurrentSessionIsImmediatelyOfflineWithoutDeletingReplacementState() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.exists("exec:online:1")).thenReturn(true);
        when(redisManager.exists("exec:closed-session:1:session-current")).thenReturn(true);

        assertFalse(presenceManager.isExecutorOnline(1L));
        verify(redisManager, never()).del("exec:online:1");
    }

    @Test
    void activeConversationTurnIdsReadsCurrentSessionSnapshot() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.get("exec:dispatch-snapshot:1"))
                .thenReturn(snapshot(Set.of(55L, 56L), Set.of()));

        assertEquals(Set.of(55L, 56L), presenceManager.activeConversationTurnIds(1L));
    }

    @Test
    void hasConversationTurnActivityReportRequiresCurrentSessionSnapshot() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.get("exec:dispatch-snapshot:1"))
                .thenReturn(snapshot(Set.of(), Set.of()));

        assertTrue(presenceManager.hasConversationTurnActivityReport(1L));
    }

    @Test
    void missingLegacyConversationReportRemainsUnknownAfterPublicationAndSerialization() throws Exception {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.set(eq("exec:dispatch-snapshot:1"), any(), anyInt())).thenReturn(true);
        var missing = new com.aliyun.autowonder.executor.ExecutorDispatchSnapshot(
                "session-current", 10, false, false, Set.of(), Set.of(), null,
                Set.of(), null, System.currentTimeMillis());
        presenceManager.publishHeartbeat(1L, 10L, "session-current", missing, null, null, null);
        var stored = org.mockito.ArgumentCaptor.forClass(
                com.aliyun.autowonder.executor.ExecutorDispatchSnapshot.class);
        verify(redisManager).set(eq("exec:dispatch-snapshot:1"), stored.capture(), anyInt());
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.ObjectOutputStream(bytes)) { out.writeObject(stored.getValue()); }
        Object restored;
        try (var in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            restored = in.readObject();
        }
        when(redisManager.get("exec:dispatch-snapshot:1")).thenReturn(restored);
        assertFalse(presenceManager.hasConversationTurnActivityReport(1L));
        assertTrue(presenceManager.activeConversationTurnIds(1L).isEmpty());
        verify(redisManager, never()).setWithExpire(eq("exec:conversation-turn-report:1"), anyString(), anyLong());
    }

    @Test
    void normalizeCapacityDefaultsAndClamps() {
        assertEquals(3, PresenceManager.normalizeCapacity(null));
        assertEquals(1, PresenceManager.normalizeCapacity("not-a-number"));
        assertEquals(1, PresenceManager.normalizeCapacity("0"));
        assertEquals(10, PresenceManager.normalizeCapacity("10"));
        assertEquals(50, PresenceManager.normalizeCapacity("500"));
    }

    @Test
    void capacityUsesLegacyThreeWhenMissingAndOneWhenInvalid() {
        when(redisManager.getString("exec:capacity:1")).thenReturn(null, "bad", "12");

        assertEquals(3, presenceManager.capacity(1L));
        assertEquals(1, presenceManager.capacity(1L));
        assertEquals(12, presenceManager.capacity(1L));
    }

    @Test
    void recordVersionWritesVersionKeyWithPresenceTtl() {
        presenceManager.recordVersion(1L, "0.2.152");

        verify(redisManager).setWithExpire("exec:version:1", "0.2.152", 90L);
    }

    @Test
    void recordVersionIgnoresBlankOrMissingVersion() {
        presenceManager.recordVersion(1L, null);
        presenceManager.recordVersion(1L, "");
        presenceManager.recordVersion(1L, "   ");

        verify(redisManager, never()).setWithExpire(eq("exec:version:1"), anyString(), anyLong());
    }

    @Test
    void recordVersionTruncatesOverlongVersion() {
        String overlong = "v".repeat(100);

        presenceManager.recordVersion(1L, overlong);

        verify(redisManager).setWithExpire(eq("exec:version:1"), argThat(v -> v.length() == 64), eq(90L));
    }

    @Test
    void currentVersionReadsVersionKey() {
        when(redisManager.getString("exec:version:1")).thenReturn("0.2.152");

        assertEquals("0.2.152", presenceManager.currentVersion(1L));
    }

    @Test
    void recordModelWritesModelKeyWithPresenceTtl() {
        presenceManager.recordModel(1L, "qoder3-coder-plus");

        verify(redisManager).setWithExpire("exec:model:1", "qoder3-coder-plus", 90L);
    }

    @Test
    void recordModelIgnoresBlankOrMissingModel() {
        presenceManager.recordModel(1L, null);
        presenceManager.recordModel(1L, "");
        presenceManager.recordModel(1L, "   ");

        verify(redisManager, never()).setWithExpire(eq("exec:model:1"), anyString(), anyLong());
    }

    @Test
    void recordModelTruncatesOverlongModel() {
        String overlong = "m".repeat(200);

        presenceManager.recordModel(1L, overlong);

        verify(redisManager).setWithExpire(eq("exec:model:1"), argThat(m -> m.length() == 128), eq(90L));
    }

    @Test
    void currentModelReadsModelKey() {
        when(redisManager.getString("exec:model:1")).thenReturn("qoder3-coder-plus");

        assertEquals("qoder3-coder-plus", presenceManager.currentModel(1L));
    }

    private static ExecutorDispatchSnapshot snapshot(Set<Long> conversations,
            Set<String> features) {
        return new ExecutorDispatchSnapshot("session-current", 10, true, true,
                Set.of(), Set.of(), conversations, features, null, 123L);
    }
}
