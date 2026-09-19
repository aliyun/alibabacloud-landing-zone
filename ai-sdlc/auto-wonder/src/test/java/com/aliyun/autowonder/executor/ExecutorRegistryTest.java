package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutorRegistryTest {

    @Test
    void currentDispatchSnapshotRejectsPreviousWebsocketSession() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        ExecutorDispatchSnapshot stale = new ExecutorDispatchSnapshot(
                "old", 10, true, true, java.util.Set.of(55L), java.util.Set.of(55L),
                java.util.Set.of(), java.util.Set.of(), null, 123L);
        when(redis.get(ExecutorDispatchSnapshot.key(10L))).thenReturn(stale);
        when(redis.getString("exec:session:10")).thenReturn("current");

        assertTrue(registry.currentDispatchSnapshot(10L).isEmpty());
    }

    @Test
    void currentDispatchSnapshotReturnsCompleteCurrentSession() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        ExecutorDispatchSnapshot current = new ExecutorDispatchSnapshot(
                "current", 10, true, true, java.util.Set.of(55L), java.util.Set.of(55L, 56L),
                java.util.Set.of(77L), java.util.Set.of(), null, 123L);
        when(redis.get(ExecutorDispatchSnapshot.key(10L))).thenReturn(current);
        when(redis.getString("exec:session:10")).thenReturn("current");

        assertEquals(current, registry.currentDispatchSnapshot(10L).orElseThrow());
    }

    @Test
    void authoritativeOwnedSetProvesReleaseOnlyWhenInventoryIsReady() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.getString("exec:session:10")).thenReturn("current");
        when(redis.get(ExecutorDispatchSnapshot.key(10L))).thenReturn(
                new ExecutorDispatchSnapshot("current", 10, true, false, java.util.Set.of(),
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null, 1L),
                new ExecutorDispatchSnapshot("current", 10, true, true, java.util.Set.of(),
                        java.util.Set.of(55L), java.util.Set.of(), java.util.Set.of(), null, 2L),
                new ExecutorDispatchSnapshot("current", 10, true, true, java.util.Set.of(),
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null, 3L));

        assertTrue(registry.isDispatchOwnedOrUnknown(10L, 55L));
        assertTrue(registry.isDispatchOwnedOrUnknown(10L, 55L));
        assertFalse(registry.isDispatchOwnedOrUnknown(10L, 55L));
    }

    @Test
    void heartbeatPersistsExactRunningDispatchMembershipIncludingEmptySet() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);

        registry.updateRunningDispatches(10L, java.util.List.of(55L, 56L));
        registry.updateRunningDispatches(10L, java.util.List.of());
        when(redis.get(ExecutorRegistry.runningDispatchesKey(10L))).thenReturn(java.util.List.of());

        verify(redis).set(ExecutorRegistry.runningDispatchesKey(10L),
                (java.io.Serializable) java.util.List.of(55L, 56L), 60);
        verify(redis).set(ExecutorRegistry.runningDispatchesKey(10L),
                (java.io.Serializable) java.util.List.of(), 60);
        assertTrue(registry.hasNoReportedRunningDispatches(10L));
    }

    @Test
    void missingRunningDispatchesClearsLeaseAndRemainsUnknown() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        String key = ExecutorRegistry.runningDispatchesKey(10L);

        registry.updateRunningDispatches(10L, null);

        verify(redis).del(key);
        verify(redis, never()).set(eq(key), any(), anyInt());
        assertFalse(registry.hasNoReportedRunningDispatches(10L));
    }

    @Test
    void dispatchIsReleasedWhenLiveHeartbeatNoLongerListsIt() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.get(ExecutorRegistry.runningDispatchesKey(10L)))
                .thenReturn(java.util.List.of(56L));

        assertFalse(registry.isDispatchActive(10L, 55L));
        assertTrue(registry.isDispatchActive(10L, 56L));
    }

    @Test
    void oneSecondFailoverMarkerMakesTransportOnlineExecutorTemporarilyUnavailable() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.exists(ExecutorRegistry.onlineKey(10L))).thenReturn(true);
        stubCurrentSnapshot(redis, 10L);
        when(redis.exists(ExecutorRegistry.providerCooldownKey(10L))).thenReturn(true);
        when(redis.getString(ExecutorRegistry.providerCooldownKey(10L)))
                .thenReturn("failover:agent_error.provider_quota_limit");

        assertTrue(registry.isOnline(10L));
        assertFalse(registry.isAvailable(10L));
    }

    @Test
    void quotaFailureCreatesOnlyOneSecondFailoverMarker() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);

        registry.markProviderUnavailable(10L, "agent_error.provider_quota_limit");

        verify(redis).setWithExpire(ExecutorRegistry.providerCooldownKey(10L),
                "failover:agent_error.provider_quota_limit", 1L);
    }

    @Test
    void successfulProviderCallClearsCooldown() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);

        registry.markProviderAvailable(10L);

        verify(redis).del(ExecutorRegistry.providerCooldownKey(10L));
    }

    @Test
    void transientProviderFailureUsesSameOneSecondFailoverMarker() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);

        registry.markProviderUnavailable(10L, "agent_error.provider_server_error");

        verify(redis).setWithExpire(ExecutorRegistry.providerCooldownKey(10L),
                "failover:agent_error.provider_server_error", 1L);
    }

    @Test
    void runtimeRecoveryDoesNotCreateProviderCooldown() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);

        registry.markProviderUnavailable(10L, "runtime_recovery");

        verify(redis, never()).setWithExpire(anyString(), anyString(), anyLong());
    }

    @Test
    void legacyLongLivedCooldownIsClearedAndExecutorRemainsAvailable() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        String cooldownKey = ExecutorRegistry.providerCooldownKey(10L);
        when(redis.exists(ExecutorRegistry.onlineKey(10L))).thenReturn(true);
        stubCurrentSnapshot(redis, 10L);
        when(redis.exists(cooldownKey)).thenReturn(true);
        when(redis.getString(cooldownKey)).thenReturn("agent_error.provider_quota_limit");

        assertTrue(registry.isAvailable(10L));
        verify(redis).del(cooldownKey);
    }

    @Test
    void closedCurrentSessionIsImmediatelyOffline() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.exists(ExecutorRegistry.onlineKey(10L))).thenReturn(true);
        stubCurrentSnapshot(redis, 10L);
        when(redis.exists(ExecutorDispatchSnapshot.closedSessionKey(10L, "current")))
                .thenReturn(true);

        assertFalse(registry.isOnline(10L));
    }

    @Test
    void isDeletedChecksTombstoneKey() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.exists(ExecutorRegistry.deletedKey(10L))).thenReturn(true);

        assertTrue(registry.isDeleted(10L));
        verify(redis).exists("exec:deleted:10");
    }

    @Test
    void isAvailableReturnsFalseWhenTombstoned() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.exists(ExecutorRegistry.deletedKey(10L))).thenReturn(true);

        assertFalse(registry.isAvailable(10L));
        verify(redis, never()).exists(ExecutorRegistry.onlineKey(10L));
    }

    @Test
    void reportsNoRunningDispatchesOnlyForKnownEmptyCollection() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);
        when(redis.get(ExecutorRegistry.runningDispatchesKey(1L))).thenReturn(java.util.List.of());
        when(redis.get(ExecutorRegistry.runningDispatchesKey(2L))).thenReturn(null);
        when(redis.get(ExecutorRegistry.runningDispatchesKey(3L))).thenReturn(java.util.List.of(99L));
        when(redis.get(ExecutorRegistry.runningDispatchesKey(4L))).thenReturn("unknown");
        when(redis.get(ExecutorRegistry.runningDispatchesKey(5L))).thenThrow(new RuntimeException("redis unavailable"));

        assertTrue(registry.hasNoReportedRunningDispatches(1L));
        assertFalse(registry.hasNoReportedRunningDispatches(2L));
        assertFalse(registry.hasNoReportedRunningDispatches(3L));
        assertFalse(registry.hasNoReportedRunningDispatches(4L));
        assertFalse(registry.hasNoReportedRunningDispatches(5L));
    }

    private static void stubCurrentSnapshot(RedisManager redis, long executorId) {
        when(redis.getString("exec:session:" + executorId)).thenReturn("current");
        when(redis.get(ExecutorDispatchSnapshot.key(executorId))).thenReturn(
                new ExecutorDispatchSnapshot("current", 10, true, true,
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                        java.util.Set.of(), null, 1L));
    }
}
