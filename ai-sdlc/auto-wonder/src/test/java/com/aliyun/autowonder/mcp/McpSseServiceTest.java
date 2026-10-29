package com.aliyun.autowonder.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.servlet.AsyncEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class McpSseServiceTest {
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    private final ScheduledFuture<?> expiry = mock(ScheduledFuture.class);
    private final AtomicReference<Runnable> beat = new AtomicReference<>();
    private final AtomicReference<Runnable> expire = new AtomicReference<>();
    private final McpSseService service = new McpSseService(scheduler);

    private MvcResult connect() throws Exception {
        doAnswer(invocation -> {
            beat.set(invocation.getArgument(0));
            assertEquals(Duration.ofSeconds(15), invocation.getArgument(2));
            return heartbeat;
        }).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        doAnswer(invocation -> {
            expire.set(invocation.getArgument(0));
            assertTrue(((Instant) invocation.getArgument(1)).isAfter(Instant.now().plusSeconds(290)));
            return expiry;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        return MockMvcBuilders.standaloneSetup(new McpController(mock(McpAccessTokenService.class),
                        mock(McpToolService.class), service)).build()
                .perform(get("/api/mcp").accept("text/event-stream")).andReturn();
    }

    @Test
    void heartbeatIsCommentAndHardLifetimeCancelsBothTasks() throws Exception {
        MvcResult result = connect();
        assertEquals(300_000L, result.getRequest().getAsyncContext().getTimeout());
        beat.get().run();
        assertEquals(":connected\n\n:heartbeat\n\n", result.getResponse().getContentAsString());
        expire.get().run();
        verify(heartbeat).cancel(false);
        verify(expiry).cancel(false);
        beat.get().run();
        assertEquals(":connected\n\n:heartbeat\n\n", result.getResponse().getContentAsString());
    }

    @Test
    void timeoutDisconnectAndCompletionCancelTasksIdempotently() throws Exception {
        for (String signal : new String[]{"timeout", "error", "completion"}) {
            clearInvocations(heartbeat, expiry);
            MockAsyncContext context = (MockAsyncContext) connect().getRequest().getAsyncContext();
            AsyncEvent event = new AsyncEvent(context, new IOException("disconnected"));
            for (var listener : context.getListeners()) {
                switch (signal) {
                    case "timeout" -> listener.onTimeout(event);
                    case "error" -> listener.onError(event);
                    default -> listener.onComplete(event);
                }
            }
            expire.get().run();
            verify(heartbeat).cancel(false);
            verify(expiry).cancel(false);
        }
    }

    @Test
    void shutdownCancelsOutstandingStreams() throws Exception {
        connect();
        service.shutdown();
        verify(heartbeat).cancel(false);
        verify(expiry).cancel(false);
    }

    @Test
    void disconnectedEmitterCannotAbortShutdownOfOtherStreams() throws Exception {
        doReturn(heartbeat).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        var disconnected = service.open();
        service.open();
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = java.lang.reflect.Proxy.newProxyInstance(handlerType.getClassLoader(),
                new Class<?>[]{handlerType}, (proxy, method, args) -> {
                    if (method.getName().equals("complete")) {
                        throw new NullPointerException("socket wrapper already released");
                    }
                    return null;
                });
        org.springframework.test.util.ReflectionTestUtils.setField(disconnected, "handler", handler);
        assertDoesNotThrow(service::shutdown);
        verify(heartbeat, times(2)).cancel(false);
        verify(expiry, times(2)).cancel(false);
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void partialSchedulingFailureCancelsAlreadyScheduledHeartbeat() throws Exception {
        doReturn(heartbeat).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        doThrow(new IllegalStateException("scheduler stopped")).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        service.open();
        verify(heartbeat).cancel(false);
    }

    @Test
    void shutdownPreventsNewStreamsFromEscapingCleanup() {
        service.shutdown();
        assertThrows(IllegalStateException.class, service::open);
        verifyNoInteractions(scheduler);
    }

    @Test
    void springOwnsSchedulerAndCancelsTasksOnContextClose() {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                McpSseService.SchedulerConfiguration.class, McpSseService.class);
        var ownedScheduler = context.getBean("mcpSseScheduler",
                org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
        var executor = ownedScheduler.getScheduledThreadPoolExecutor();
        try {
            context.getBean(McpSseService.class).open();
            assertEquals(2, executor.getQueue().size());
            assertTrue(executor.getRemoveOnCancelPolicy());
        } finally {
            context.close();
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.getQueue().isEmpty());
    }
}
