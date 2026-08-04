package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutorRegistryTest {

    @Test
    void heartbeatPersistsExactRunningDispatchMembershipIncludingEmptySet() {
        RedisManager redis = mock(RedisManager.class);
        ExecutorRegistry registry = new ExecutorRegistry(redis);

        registry.updateRunningDispatches(10L, java.util.List.of(55L, 56L));
        registry.updateRunningDispatches(10L, java.util.List.of());

        verify(redis).set(ExecutorRegistry.runningDispatchesKey(10L),
                (java.io.Serializable) java.util.List.of(55L, 56L), 60);
        verify(redis).set(ExecutorRegistry.runningDispatchesKey(10L),
                (java.io.Serializable) java.util.List.of(), 60);
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
        when(redis.exists(cooldownKey)).thenReturn(true);
        when(redis.getString(cooldownKey)).thenReturn("agent_error.provider_quota_limit");

        assertTrue(registry.isAvailable(10L));
        verify(redis).del(cooldownKey);
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
}
