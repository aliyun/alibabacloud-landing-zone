package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.executor.ExecutorRegistry;
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
    void registerWritesOnlineRouteAndExecsSet() {
        presenceManager.register(1L, 10L, 10);

        verify(redisManager).setWithExpire("exec:online:1", "node-abc", 90L);
        verify(redisManager).setWithExpire("exec:route:1", "node-abc", 90L);
        verify(redisManager).setWithExpire("exec:capacity:1", "10", 90L);
        verify(redisManager).sadd("agent:execs:10", "1");
    }

    @Test
    void announceSessionPublishesCrossNodeReconciliation() {
        presenceManager.announceSession(1L, "session-new");

        verify(redisManager).setWithExpire("exec:session:1", "session-new", 90L);
        verify(redisManager).publish(eq("node:dispatch:broadcast"),
                argThat(message -> message.contains("SESSION_REPLACED")
                        && message.contains("\"executorId\":1")));
    }

    @Test
    void refreshSessionExtendsOnlyAuthoritativeSession() {
        when(redisManager.getString("exec:session:1")).thenReturn("current");

        presenceManager.refreshSession(1L, "current");
        presenceManager.refreshSession(1L, "old");

        verify(redisManager, times(1)).setWithExpire("exec:session:1", "current", 90L);
    }

    @Test
    void unregisterRemovesAllKeys() {
        presenceManager.unregister(1L, 10L);

        verify(redisManager).del("exec:online:1");
        verify(redisManager).del("exec:route:1");
        verify(redisManager).del("exec:capacity:1");
        verify(redisManager).del("exec:session:1");
        verify(redisManager).srem("agent:execs:10", "1");
    }

    @Test
    void heartbeatRecreatesPresenceAndCapacity() {
        presenceManager.heartbeat(1L, 10L, 7);

        verify(redisManager).setWithExpire("exec:online:1", "node-abc", 90L);
        verify(redisManager).setWithExpire("exec:route:1", "node-abc", 90L);
        verify(redisManager).setWithExpire("exec:capacity:1", "7", 90L);
        verify(redisManager).sadd("agent:execs:10", "1");
    }

    @Test
    void heartbeatRecordsActiveConversationTurnsWhenReported() {
        presenceManager.heartbeat(1L, 10L, 7, java.util.List.of(55L, 56L));

        verify(redisManager).del("exec:conversation-turns:1");
        verify(redisManager).sadd("exec:conversation-turns:1", "55");
        verify(redisManager).sadd("exec:conversation-turns:1", "56");
        verify(redisManager).setExpire("exec:conversation-turns:1", 90L);
        verify(redisManager).setWithExpire("exec:conversation-turn-report:1", "1", 90L);
    }

    @Test
    void heartbeatRecordsEmptyConversationTurnReport() {
        presenceManager.heartbeat(1L, 10L, 7, java.util.List.of());

        verify(redisManager).del("exec:conversation-turns:1");
        verify(redisManager, never()).sadd(eq("exec:conversation-turns:1"), anyString());
        verify(redisManager).setWithExpire("exec:conversation-turn-report:1", "1", 90L);
    }

    @Test
    void isExecutorOnlineReadsOnlinePresenceKey() {
        when(redisManager.exists("exec:online:1")).thenReturn(true);

        assertTrue(presenceManager.isExecutorOnline(1L));
    }

    @Test
    void activeConversationTurnIdsParsesRedisSet() {
        when(redisManager.smembers("exec:conversation-turns:1"))
                .thenReturn(Set.of("55", "bad", "56"));

        assertEquals(Set.of(55L, 56L), presenceManager.activeConversationTurnIds(1L));
    }

    @Test
    void hasConversationTurnActivityReportReadsMarker() {
        when(redisManager.exists("exec:conversation-turn-report:1")).thenReturn(true);

        assertTrue(presenceManager.hasConversationTurnActivityReport(1L));
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
    void registerSkipsWhenTombstonePresent() {
        when(redisManager.exists(ExecutorRegistry.deletedKey(1L))).thenReturn(true);

        boolean result = presenceManager.register(1L, 10L, 5);

        assertFalse(result);
        verify(redisManager, never()).setWithExpire(eq("exec:online:1"), anyString(), anyLong());
        verify(redisManager, never()).setWithExpire(eq("exec:route:1"), anyString(), anyLong());
        verify(redisManager, never()).sadd(eq("agent:execs:10"), anyString());
    }

    @Test
    void heartbeatSkipsWhenTombstoned() {
        when(redisManager.exists(ExecutorRegistry.deletedKey(1L))).thenReturn(true);

        boolean result = presenceManager.heartbeat(1L, 10L, 5);

        assertFalse(result);
        verify(redisManager, never()).setWithExpire(eq("exec:online:1"), anyString(), anyLong());
    }

    @Test
    void heartbeatWithConversationTurnsSkipsWhenTombstoned() {
        when(redisManager.exists(ExecutorRegistry.deletedKey(1L))).thenReturn(true);

        boolean result = presenceManager.heartbeat(1L, 10L, 5, java.util.List.of(55L));

        assertFalse(result);
        verify(redisManager, never()).del("exec:conversation-turns:1");
    }

    @Test
    void registerProceedsWhenNoTombstone() {
        when(redisManager.exists(ExecutorRegistry.deletedKey(1L))).thenReturn(false);

        boolean result = presenceManager.register(1L, 10L, 5);

        assertTrue(result);
        verify(redisManager).setWithExpire("exec:online:1", "node-abc", 90L);
    }
}
