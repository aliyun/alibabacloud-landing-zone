package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ExecutorSelectorTest {

    private ExecutorDispatchSnapshot snapshot(int capacity, boolean ready,
            Set<Long> running, Set<Long> conversations) {
        return snapshot(capacity, true, ready, running, conversations);
    }

    private ExecutorDispatchSnapshot snapshot(int capacity, boolean authoritative, boolean ready,
            Set<Long> running, Set<Long> conversations) {
        return new ExecutorDispatchSnapshot("session", capacity, authoritative, ready, running, running,
                conversations, Set.of(), null, System.currentTimeMillis());
    }

    private RedisManager redisManager;
    private ExecutorRegistry executorRegistry;
    private PresenceManager presenceManager;
    private DispatchDao dispatchDao;
    private ExecutorSelector selector;

    @BeforeEach
    void setUp() {
        redisManager = mock(RedisManager.class);
        executorRegistry = mock(ExecutorRegistry.class);
        presenceManager = mock(PresenceManager.class);
        dispatchDao = mock(DispatchDao.class);
        selector = new ExecutorSelector(redisManager, executorRegistry, presenceManager, dispatchDao);
        when(executorRegistry.currentDispatchSnapshot(anyLong())).thenAnswer(invocation -> {
            long executorId = invocation.getArgument(0);
            return java.util.Optional.of(snapshot(presenceManager.capacity(executorId), true,
                    Set.of(), Set.of()));
        });
    }

    @Test
    void returnsNullWhenNoExecutorsRegistered() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of());
        assertNull(selector.select(1L));
    }

    @Test
    void stopPendingDoesNotQuarantineWholeExecutorAndUsesOnlyOneSlot() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.currentDispatchSnapshot(10L))
                .thenReturn(java.util.Optional.of(snapshot(10, true, Set.of(13327L), Set.of())));
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(13320L));

        assertEquals(10L, selector.select(1L));
    }

    @Test
    void inventoryMustBeReadyBeforeExecutorCanBeSelected() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.currentDispatchSnapshot(10L))
                .thenReturn(java.util.Optional.of(snapshot(10, false, Set.of(), Set.of())));
        when(presenceManager.supportsProtocolFeature(10L, "dispatch_inventory_v1"))
                .thenReturn(true);

        assertNull(selector.select(1L));
    }

    @Test
    void legacyRuntimeUsesReportedCapacityWithoutClaimingAuthoritativeInventory() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(presenceManager.supportsProtocolFeature(10L, "dispatch_inventory_v1"))
                .thenReturn(false);
        when(executorRegistry.currentDispatchSnapshot(10L))
                .thenReturn(java.util.Optional.of(snapshot(10, false, false,
                        Set.of(55L), Set.of())));
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(55L));

        assertEquals(10L, selector.select(1L));
        assertEquals(DispatchWaitingReason.NO_EXECUTOR_CAPACITY, selector.unavailableReason(1L));
    }

    @Test
    void executorWithoutAuthoritativeInventoryCannotBeSelected() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.currentDispatchSnapshot(10L)).thenReturn(java.util.Optional.empty());

        assertNull(selector.select(1L));
        assertEquals(DispatchWaitingReason.EXECUTOR_RECOVERING, selector.unavailableReason(1L));
    }

    @Test
    void emptyPresenceSetAndOfflineMemberAreBothRetryable() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of());
        assertEquals(DispatchWaitingReason.NO_EXECUTOR_ONLINE, selector.unavailableReason(1L));

        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isOnline(10L)).thenReturn(false);
        when(executorRegistry.isAvailable(10L)).thenReturn(false);
        assertEquals(DispatchWaitingReason.NO_EXECUTOR_ONLINE, selector.unavailableReason(1L));
    }

    @Test
    void explicitAgentProtocolErrorIsPermanentAfterIncompatibleRuntimeDisconnects() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of());
        when(presenceManager.currentAgentProtocolError(1L))
                .thenReturn("EXECUTOR_PROTOCOL_INCOMPATIBLE");

        assertEquals(DispatchWaitingReason.RUNTIME_INCOMPATIBLE,
                selector.unavailableReason(1L));
    }

    @Test
    void offlineStaleMemberDoesNotHideAgentProtocolError() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isOnline(10L)).thenReturn(false);
        when(executorRegistry.isAvailable(10L)).thenReturn(false);
        when(presenceManager.currentAgentProtocolError(1L))
                .thenReturn("EXECUTOR_PROTOCOL_INCOMPATIBLE");

        assertEquals(DispatchWaitingReason.RUNTIME_INCOMPATIBLE,
                selector.unavailableReason(1L));
    }

    @Test
    void selectorInternalErrorIsPermanentAndTruthful() {
        assertEquals(false, DispatchWaitingReason.SELECTION_INTERNAL_ERROR.retryable());
        assertEquals("SELECTION_INTERNAL_ERROR: 调度器内部错误，任务未派发",
                DispatchWaitingReason.SELECTION_INTERNAL_ERROR.error());
    }

    @Test
    void notReadyInventoryReportsRecoveryRatherThanCapacity() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.currentDispatchSnapshot(10L))
                .thenReturn(java.util.Optional.of(snapshot(10, false, Set.of(), Set.of())));
        when(presenceManager.supportsProtocolFeature(10L, "dispatch_inventory_v1"))
                .thenReturn(true);

        assertEquals(DispatchWaitingReason.EXECUTOR_RECOVERING, selector.unavailableReason(1L));
    }

    @Test
    void runningDispatchAndConversationTurnsShareRuntimeCapacity() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.currentDispatchSnapshot(10L))
                .thenReturn(java.util.Optional.of(snapshot(3, true, Set.of(55L), Set.of(77L))));
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(55L));

        assertNull(selector.select(1L));
        assertEquals(10L, selector.selectForInteraction(1L, null));
    }

    @Test
    void availabilityProbeHonorsCapacityWithoutAdvancingCursor() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("bad", "10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(2);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(1L));
        org.junit.jupiter.api.Assertions.assertFalse(selector.hasAvailableExecutor(1L));
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of());
        org.junit.jupiter.api.Assertions.assertTrue(selector.hasAvailableExecutor(1L));
        verify(redisManager, never()).exIncrBy(anyString(), anyLong(), anyLong());
    }

    @Test
    void returnsNullWhenNoneOnline() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(false);
        when(executorRegistry.isAvailable(20L)).thenReturn(false);
        assertNull(selector.select(1L));
    }

    @Test
    void picksFirstOnlineAscending() {
        Set<String> members = new LinkedHashSet<>();
        members.add("30");
        members.add("10");
        members.add("20");
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(members);
        when(executorRegistry.isAvailable(10L)).thenReturn(false);
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(executorRegistry.isAvailable(30L)).thenReturn(true);
        when(presenceManager.capacity(20L)).thenReturn(1);
        when(presenceManager.capacity(30L)).thenReturn(1);
        assertEquals(20L, selector.select(1L));
    }

    @Test
    void ignoresNonNumericMembers() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("abc", "40"));
        when(executorRegistry.isAvailable(40L)).thenReturn(true);
        when(presenceManager.capacity(40L)).thenReturn(1);
        assertEquals(40L, selector.select(1L));
    }

    @Test
    void skipsExecutorAtCapacity() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(3);
        when(presenceManager.capacity(20L)).thenReturn(2);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(1L, 2L, 3L));
        when(dispatchDao.listCapacityOccupyingIds(20L)).thenReturn(java.util.List.of());

        assertEquals(20L, selector.select(1L));
    }

    @Test
    void rotatesAcrossEligibleExecutorsInsteadOfAlwaysChoosingLowestId() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20", "30"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(executorRegistry.isAvailable(30L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(10);
        when(presenceManager.capacity(20L)).thenReturn(5);
        when(presenceManager.capacity(30L)).thenReturn(8);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of());
        when(dispatchDao.listCapacityOccupyingIds(20L)).thenReturn(java.util.List.of());
        when(dispatchDao.listCapacityOccupyingIds(30L)).thenReturn(java.util.List.of());
        when(redisManager.exIncrBy("agent:executor-round-robin:1", 1L, 604800L))
                .thenReturn(1L, 2L, 3L, 4L);

        assertEquals(10L, selector.select(1L));
        assertEquals(20L, selector.select(1L));
        assertEquals(30L, selector.select(1L));
        assertEquals(10L, selector.select(1L));
    }

    @Test
    void skipsTransportOnlineExecutorDuringProviderCooldown() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(false);
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(presenceManager.capacity(20L)).thenReturn(10);

        assertEquals(20L, selector.select(1L));
        verify(dispatchDao, never()).countActiveByExecutor(10L);
    }

    @Test
    void prefersSessionOwningExecutorWhenItHasCapacity() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(10);
        when(presenceManager.capacity(20L)).thenReturn(10);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of());
        when(dispatchDao.listCapacityOccupyingIds(20L)).thenReturn(java.util.List.of(1L,2L,3L,4L,5L,6L,7L,8L));

        assertEquals(20L, selector.select(1L, 20L));
    }

    @Test
    void fallsBackWhenSessionOwningExecutorIsUnavailable() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.isAvailable(20L)).thenReturn(false);
        when(presenceManager.capacity(10L)).thenReturn(10);

        assertEquals(10L, selector.select(1L, 20L));
    }

    @Test
    void reservesLastSlotForInteractionDispatches() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(10);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(1L,2L,3L,4L,5L,6L,7L,8L,9L));

        assertNull(selector.select(1L));
        assertEquals(10L, selector.selectForInteraction(1L, null));
    }

    @Test
    void singleSlotExecutorStillAcceptsFormalWork() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(1);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of());

        assertEquals(10L, selector.select(1L));
    }

    @Test
    void excludesTombstonedExecutorFromSelection() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(false);
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(presenceManager.capacity(20L)).thenReturn(10);

        assertEquals(20L, selector.select(1L));
        verify(dispatchDao, never()).countActiveByExecutor(10L);
    }

    @Test
    void rejectsTombstonedPreferredExecutor() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(executorRegistry.isAvailable(20L)).thenReturn(false);
        when(presenceManager.capacity(10L)).thenReturn(10);

        assertEquals(10L, selector.select(1L, 20L));
    }

    @Test
    void strictSelectionNeverFallsBackWhenSessionOwnerDropsOrFills() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(20L)).thenReturn(false);
        assertNull(selector.selectStrict(1L, 20L));
        verify(executorRegistry, never()).isAvailable(10L);
    }

    @Test
    void requiredFeatureSelectsSupportingExecutorAndSkipsUnsupportedPreferred() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(anyLong())).thenReturn(true);
        when(presenceManager.capacity(anyLong())).thenReturn(10);
        when(presenceManager.supportsProtocolFeature(10L, "FEATURE_V1")).thenReturn(true);

        assertEquals(10L, selector.select(1L, 20L, "FEATURE_V1"));
    }

    @Test
    void requiredFeatureFailsClearlyWhenCapacityEligibleExecutorsAreAllUnsupported() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(anyLong())).thenReturn(true);
        when(presenceManager.capacity(anyLong())).thenReturn(10);

        ExecutorProtocolCompatibilityException error = assertThrows(
                ExecutorProtocolCompatibilityException.class,
                () -> selector.select(1L, null, "FEATURE_V1"));

        assertEquals("FEATURE_V1", error.getRequiredFeature());
    }

    @Test
    void requiredFeatureKeepsNoCapacityAsOrdinaryUnavailableResult() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(1);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(99L));

        assertNull(selector.select(1L, null, "FEATURE_V1"));
        verify(presenceManager).supportsProtocolFeature(10L, "FEATURE_V1");
    }

    @Test
    void requiredFeatureReturnsUnavailableWhenSupportingRuntimeIsFull() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(anyLong())).thenReturn(true);
        when(presenceManager.capacity(anyLong())).thenReturn(1);
        when(dispatchDao.listCapacityOccupyingIds(10L)).thenReturn(java.util.List.of(99L));
        when(dispatchDao.listCapacityOccupyingIds(20L)).thenReturn(java.util.List.of());
        when(presenceManager.supportsProtocolFeature(10L, "FEATURE_V1")).thenReturn(true);

        assertNull(selector.select(1L, null, "FEATURE_V1"));
    }

    @Test
    void strictRequiredFeatureFailsInsteadOfFallingBack() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10", "20"));
        when(executorRegistry.isAvailable(20L)).thenReturn(true);
        when(presenceManager.capacity(20L)).thenReturn(10);

        assertThrows(ExecutorProtocolCompatibilityException.class,
                () -> selector.selectStrict(1L, 20L, "FEATURE_V1"));
        verify(executorRegistry, never()).isAvailable(10L);
    }
}
