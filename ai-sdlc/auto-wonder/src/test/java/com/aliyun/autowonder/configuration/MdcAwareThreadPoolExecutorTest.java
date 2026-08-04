package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcAwareThreadPoolExecutorTest {

    private MdcAwareThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void submitPropagatesMdcToWorkerThread() throws Exception {
        executor = new MdcAwareThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "mdc-test-thread"));
        MDC.put("requestId", "rid-thread");

        String seen = executor.submit(() -> MDC.get("requestId")).get(3, TimeUnit.SECONDS);

        assertEquals("rid-thread", seen);
    }

    @Test
    void workerThreadDoesNotLeakPreviousMdc() throws Exception {
        executor = new MdcAwareThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "mdc-test-thread"));
        MDC.put("requestId", "rid-first");
        assertEquals("rid-first", executor.submit(() -> MDC.get("requestId")).get(3, TimeUnit.SECONDS));

        MDC.clear();

        assertNull(executor.submit(() -> MDC.get("requestId")).get(3, TimeUnit.SECONDS));
    }

    @Test
    void executePropagatesMdc() throws Exception {
        executor = new MdcAwareThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "mdc-test-thread"));
        MDC.put("requestId", "rid-execute");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>();

        executor.execute(() -> {
            seen.set(MDC.get("requestId"));
            done.countDown();
        });

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals("rid-execute", seen.get());
    }

    @Test
    void submitRunnableWithResultPropagatesMdc() throws Exception {
        executor = new MdcAwareThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "mdc-test-thread"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        MDC.put("requestId", "rid-result");
        AtomicReference<String> seen = new AtomicReference<>();

        String result = executor.submit(() -> seen.set(MDC.get("requestId")), "done").get(3, TimeUnit.SECONDS);

        assertEquals("done", result);
        assertEquals("rid-result", seen.get());
    }

    @Test
    void submitRunnablePropagatesMdc() throws Exception {
        executor = new MdcAwareThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "mdc-test-thread"));
        MDC.put("requestId", "rid-runnable");
        AtomicReference<String> seen = new AtomicReference<>();

        executor.submit(() -> seen.set(MDC.get("requestId"))).get(3, TimeUnit.SECONDS);

        assertEquals("rid-runnable", seen.get());
    }
}
