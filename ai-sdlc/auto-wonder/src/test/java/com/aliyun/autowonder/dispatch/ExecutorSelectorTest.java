package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class ExecutorSelectorTest {

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
    }

    @Test
    void returnsNullWhenNoExecutorsRegistered() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of());
        assertNull(selector.select(1L));
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
        when(dispatchDao.countActiveByExecutor(10L)).thenReturn(3L);
        when(dispatchDao.countActiveByExecutor(20L)).thenReturn(0L);

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
        when(dispatchDao.countActiveByExecutor(10L)).thenReturn(0L);
        when(dispatchDao.countActiveByExecutor(20L)).thenReturn(0L);
        when(dispatchDao.countActiveByExecutor(30L)).thenReturn(0L);
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
        when(dispatchDao.countActiveByExecutor(10L)).thenReturn(0L);
        when(dispatchDao.countActiveByExecutor(20L)).thenReturn(8L);

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
        when(dispatchDao.countActiveByExecutor(10L)).thenReturn(9L);

        assertNull(selector.select(1L));
        assertEquals(10L, selector.selectForInteraction(1L, null));
    }

    @Test
    void singleSlotExecutorStillAcceptsFormalWork() {
        when(redisManager.smembers(ExecutorSelector.execsKey(1L))).thenReturn(Set.of("10"));
        when(executorRegistry.isAvailable(10L)).thenReturn(true);
        when(presenceManager.capacity(10L)).thenReturn(1);
        when(dispatchDao.countActiveByExecutor(10L)).thenReturn(0L);

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
}
