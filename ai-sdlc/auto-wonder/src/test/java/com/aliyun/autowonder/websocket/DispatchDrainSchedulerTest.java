package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.dispatch.DispatchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DispatchDrainSchedulerTest {

    @Test
    void coalescesConcurrentRequestsPerAgent() {
        DispatchService dispatchService = mock(DispatchService.class);
        Queue<Runnable> tasks = new ArrayDeque<>();
        Executor executor = tasks::add;
        DispatchDrainScheduler scheduler = new DispatchDrainScheduler(dispatchService, executor);

        scheduler.request(10L);
        scheduler.request(10L);
        assertEquals(1, tasks.size());

        tasks.remove().run();
        verify(dispatchService).drainPending(10L);

        scheduler.request(10L);
        assertEquals(1, tasks.size());
    }
}
