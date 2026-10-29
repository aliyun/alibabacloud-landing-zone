package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.redis.AbstractRedisManager;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

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
    void workflowStopConfirmationRequiresFreshCurrentSessionInventory() {
        when(redisManager.getString("exec:session:1")).thenReturn("current");
        when(redisManager.get("exec:dispatch-snapshot:1")).thenReturn(new ExecutorDispatchSnapshot(
                "current", 5, true, true, Set.of(), Set.of(), Set.of(), Set.of(), null, 2000L));
        assertFalse(presenceManager.isConversationTurnInactiveAfter(1, 55, 2000));
        assertFalse(presenceManager.isConversationTurnInactiveAfter(1, 55, 2001));
        assertTrue(presenceManager.isConversationTurnInactiveAfter(1, 55, 1999));
        when(redisManager.getString("exec:session:1")).thenReturn("replacement");
        assertFalse(presenceManager.isConversationTurnInactiveAfter(1, 55, 1000));
        when(redisManager.getString("exec:session:1")).thenReturn("current");
        when(redisManager.get("exec:dispatch-snapshot:1")).thenReturn(new ExecutorDispatchSnapshot(
                "current", 5, true, true, Set.of(), Set.of(), Set.of(55L), Set.of(), null, 3000L));
        assertFalse(presenceManager.isConversationTurnInactiveAfter(1, 55, 1000));
        when(redisManager.get("exec:dispatch-snapshot:1")).thenReturn(new ExecutorDispatchSnapshot(
                "current", 5, true, true, Set.of(), Set.of(), null, Set.of(), null, 3000L));
        assertFalse(presenceManager.isConversationTurnInactiveAfter(1, 55, 1000));
    }

    @Test
    void conversationRuntimeCapabilitiesNormalizeForReadinessAndRouting() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.set(any(), any(), anyInt())).thenReturn(true);
        var current = new ExecutorDispatchSnapshot("session-current", 10, true, true,
                Set.of(), Set.of(), Set.of(), Set.of(), null, 123L);
        var advertised = java.util.List.of("CONVERSATION_ACP_INTERACTION_V1", "CONVERSATION_TURN_CANCEL");
        assertEquals(PresenceManager.SessionMutationResult.APPLIED,
                presenceManager.publishHeartbeat(1L, 10L, "session-current", current, advertised, "0.3.8", "auto"));
        verify(redisManager).set(eq("exec:dispatch-snapshot:1"),
                argThat(value -> value instanceof ExecutorDispatchSnapshot stored
                        && stored.protocolFeatures().contains("CONVERSATION_TURN_EVENT")), eq(90));
        verify(redisManager).replaceSetWithExpire(eq("exec:protocol-features:1"),
                argThat(features -> features.contains("CONVERSATION_TURN_EVENT")), eq(90L));
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
    void computingSubsetSurvivesPublicationWithoutChangingOwnership() {
        when(redisManager.getString("exec:session:1")).thenReturn("session-current");
        when(redisManager.set(eq("exec:dispatch-snapshot:1"), any(), anyInt())).thenReturn(true);
        var features = Set.of("CONVERSATION_COMPUTE_INVENTORY_V1");
        var snapshot = new ExecutorDispatchSnapshot("session-current", 2, true, true,
                Set.of(), Set.of(), Set.of(10L,20L), features, null, 1L, Set.of(10L));
        presenceManager.publishHeartbeat(1L, 10L, "session-current", snapshot, features, null, null);
        var stored = org.mockito.ArgumentCaptor.forClass(ExecutorDispatchSnapshot.class);
        verify(redisManager).set(eq("exec:dispatch-snapshot:1"), stored.capture(), anyInt());
        assertEquals(Set.of(10L), stored.getValue().capacityConversationTurnIds());
        assertEquals(Set.of(10L,20L), stored.getValue().runningConversationTurnIds());
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

    @Test
    void presenceStatesResolvesAnOnlineExecutorInOneBatch() {
        ExecutorDispatchSnapshot stored = snapshotOf("session-1", "EXECUTOR_RESTART_V1");
        // 快照是 JDK 序列化值，会话/在线/版本/模型由 String API 写入，MGET 拿到的是原始字节
        when(redisManager.mget(List.of(
                "exec:dispatch-snapshot:1", "exec:session:1", "exec:online:1",
                "exec:version:1", "exec:model:1")))
                .thenReturn(Arrays.asList(AbstractRedisManager.serialize(stored),
                        bytes("session-1"), bytes("node-abc"), bytes("0.2.155"), bytes("qoder3-coder-plus")));
        when(redisManager.mget(List.of("exec:closed-session:1:session-1")))
                .thenReturn(Collections.singletonList(null));

        PresenceManager.ExecutorPresence presence = presenceManager.presenceState(1L);

        assertTrue(presence.online());
        assertEquals(Set.of("EXECUTOR_RESTART_V1"), presence.protocolFeatures());
        assertEquals("0.2.155", presence.version());
        assertEquals("qoder3-coder-plus", presence.model());
    }

    @Test
    void presenceStatesRequiresAnUnclosedCurrentSessionAndTheOnlineMarker() {
        ExecutorDispatchSnapshot current = snapshotOf("s1", "F1");
        byte[] framed = AbstractRedisManager.serialize(current);
        when(redisManager.mget(anyList()))
                .thenReturn(Arrays.asList(
                        framed, bytes("s1"), bytes("node"), bytes("v1"), bytes("m1"),
                        framed, bytes("s2"), bytes("node"), bytes("v1"), bytes("m1"),
                        framed, bytes("s1"), null, bytes("v1"), bytes("m1"),
                        null, null, null, null, null))
                .thenReturn(Arrays.asList(bytes("1"), null, null));

        Map<Long, PresenceManager.ExecutorPresence> states =
                presenceManager.presenceStates(List.of(1L, 2L, 3L, 4L));

        assertFalse(states.get(1L).online(), "a closed session is not online");
        assertEquals(Set.of(), states.get(1L).protocolFeatures());
        assertFalse(states.get(2L).online(), "a replaced session is not online");
        assertEquals(Set.of(), states.get(2L).protocolFeatures());
        assertFalse(states.get(3L).online(), "without the online marker the executor is offline");
        assertEquals(Set.of("F1"), states.get(3L).protocolFeatures());
        assertFalse(states.get(4L).online());
        assertNull(states.get(4L).version());
        assertNull(states.get(4L).model());

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisManager, times(2)).mget(keys.capture());
        assertEquals(20, keys.getAllValues().get(0).size(), "all presence keys ride in one call");
        assertEquals(List.of("exec:closed-session:1:s1", "exec:closed-session:2:s2",
                "exec:closed-session:3:s1"), keys.getAllValues().get(1));
    }

    @Test
    void presenceStatesDeduplicatesRepeatedIds() {
        when(redisManager.mget(anyList()))
                .thenReturn(Collections.nCopies(5, null));

        assertEquals(1, presenceManager.presenceStates(List.of(1L, 1L)).size());
        verify(redisManager, times(1)).mget(anyList());
    }

    @Test
    void presenceStatesDegradesOnlyAnUndecodableSnapshot() {
        ExecutorDispatchSnapshot healthy = snapshotOf("s2", "F1");
        when(redisManager.mget(anyList()))
                .thenReturn(Arrays.asList(
                        corruptFrame(), bytes("s1"), bytes("node"), bytes("v1"), bytes("m1"),
                        AbstractRedisManager.serialize(healthy), bytes("s2"), bytes("node"),
                        bytes("v1"), bytes("m1")))
                .thenReturn(Collections.nCopies(2, null));

        Map<Long, PresenceManager.ExecutorPresence> states =
                presenceManager.presenceStates(List.of(1L, 2L));

        assertEquals(PresenceManager.ExecutorPresence.ABSENT, states.get(1L));
        assertFalse(states.get(1L).online());
        assertNull(states.get(1L).version(), "a degraded row does not leak half-read fields");
        assertTrue(states.get(2L).online(), "another row of the same batch is unaffected");
        assertEquals(Set.of("F1"), states.get(2L).protocolFeatures());
        assertEquals("v1", states.get(2L).version());
    }

    @Test
    void presenceStatesToleratesAShortReply() {
        ExecutorDispatchSnapshot stored = snapshotOf("s1", "F1");
        when(redisManager.mget(anyList()))
                .thenReturn(Arrays.asList(AbstractRedisManager.serialize(stored),
                        bytes("s1"), bytes("node"), bytes("v1")))
                .thenReturn(Collections.emptyList());

        Map<Long, PresenceManager.ExecutorPresence> states =
                presenceManager.presenceStates(List.of(1L, 2L));

        assertTrue(states.get(1L).online());
        assertEquals("v1", states.get(1L).version());
        assertNull(states.get(1L).model(), "a missing trailing value reads as absent");
        assertEquals(PresenceManager.ExecutorPresence.ABSENT, states.get(2L),
                "a reply shorter than the key list cannot be indexed past its end");
    }

    @Test
    void presenceStatesIgnoresAnUndecodableTextValue() {
        ExecutorDispatchSnapshot current = snapshotOf("s1", "F1");
        when(redisManager.mget(anyList()))
                .thenReturn(Arrays.asList(
                        AbstractRedisManager.serialize(current), corruptFrame(), bytes("node"),
                        corruptFrame(), corruptFrame()))
                .thenReturn(Collections.singletonList(null));

        PresenceManager.ExecutorPresence presence = presenceManager.presenceStates(List.of(1L)).get(1L);

        assertFalse(presence.online(), "an undecodable session cannot prove the current one");
        assertNull(presence.version());
        assertNull(presence.model());
    }

    @Test
    void presenceStatesTreatsANonSnapshotValueAsAnAbsentSnapshot() {
        // 快照键被别的类型占用时解码本身成功，但不是 ExecutorDispatchSnapshot，应视作无快照而非异常
        when(redisManager.mget(anyList()))
                .thenReturn(Arrays.asList(
                        AbstractRedisManager.serialize("not-a-snapshot"), bytes("s1"), bytes("node"),
                        bytes("v1"), bytes("m1")))
                .thenReturn(Collections.singletonList(null));

        PresenceManager.ExecutorPresence presence = presenceManager.presenceStates(List.of(1L)).get(1L);

        assertFalse(presence.online());
        assertEquals(Set.of(), presence.protocolFeatures());
        assertEquals("v1", presence.version());
        assertEquals("m1", presence.model());
    }

    @Test
    void presenceStatesSkipsRedisForAnEmptyOrNullIdList() {
        assertTrue(presenceManager.presenceStates(null).isEmpty());
        assertTrue(presenceManager.presenceStates(List.of()).isEmpty());
        assertTrue(presenceManager.presenceStates(Arrays.asList(null, null)).isEmpty());

        verifyNoInteractions(redisManager);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] corruptFrame() {
        // A well-formed IDL header with an unknown compression flag makes decoding throw.
        return new byte[]{'_', 'i', 'd', 'l', '1', 'X', '_', 'x'};
    }

    private static ExecutorDispatchSnapshot snapshotOf(String sessionId, String... features) {
        return new ExecutorDispatchSnapshot(sessionId, 10, true, true,
                Set.of(), Set.of(), Set.of(), Set.of(features), null, 123L);
    }

    private static ExecutorDispatchSnapshot snapshot(Set<Long> conversations,
            Set<String> features) {
        return new ExecutorDispatchSnapshot("session-current", 10, true, true,
                Set.of(), Set.of(), conversations, features, null, 123L);
    }
}
