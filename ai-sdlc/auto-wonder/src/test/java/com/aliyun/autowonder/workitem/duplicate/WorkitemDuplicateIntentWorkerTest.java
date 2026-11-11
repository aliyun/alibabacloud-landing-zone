package com.aliyun.autowonder.workitem.duplicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WorkitemDuplicateIntentWorkerTest {

    private WorkitemDuplicateService service;
    private WorkitemDuplicateIntentWorker worker;

    @BeforeEach
    void setUp() {
        service = mock(WorkitemDuplicateService.class);
        worker = new WorkitemDuplicateIntentWorker(service, 5_000L, 5_000L, 3_600_000L);
    }

    @AfterEach
    void tearDown() {
        worker.stop();
    }

    @Test
    void scanDelegatesToTheService() {
        worker.scan();

        verify(service).runOnce();
    }

    @Test
    void scanSurvivesServiceFailure() {
        doThrow(new IllegalStateException("db down")).when(service).runOnce();

        worker.scan();

        verify(service).runOnce();
    }

    @Test
    void cleanupDelegatesAndSurvivesFailure() {
        worker.cleanup();
        doThrow(new IllegalStateException("db down")).when(service).cleanupExpiredDrafts();
        worker.cleanup();

        verify(service, times(2)).cleanupExpiredDrafts();
    }

    @Test
    void expireDelegatesAndSurvivesFailure() {
        worker.expire();
        doThrow(new IllegalStateException("db down")).when(service).expireOnce();
        worker.expire();

        verify(service, times(2)).expireOnce();
    }

    @Test
    void startRunsExactlyTwoDetectionThreadsAndStopsThem() throws Exception {
        CountDownLatch advanced = new CountDownLatch(1);
        CountDownLatch expired = new CountDownLatch(1);
        doAnswer(invocation -> {
            advanced.countDown();
            return null;
        }).when(service).runOnce();
        doAnswer(invocation -> {
            expired.countDown();
            return null;
        }).when(service).expireOnce();

        worker.start();

        assertTrue(advanced.await(5, TimeUnit.SECONDS), "advance task must run");
        assertTrue(expired.await(5, TimeUnit.SECONDS), "expire task must run");
        assertTrue(worker.isRunning());
        assertTrue(detectionThreads() <= 2, "detection pool must stay at two threads");
        assertTrue(detectionThreads() >= 1, "at least the started worker threads must be counted");

        worker.stop();

        assertFalse(worker.isRunning());
        long deadline = System.currentTimeMillis() + 5_000L;
        while (detectionThreads() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(detectionThreads() == 0, "stop must terminate the detection threads");
    }

    @Test
    void startIsIdempotent() {
        worker.start();
        worker.start();

        assertTrue(worker.isRunning());
        assertTrue(detectionThreads() <= 2);
    }

    private int detectionThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(WorkitemDuplicateIntentWorker.THREAD_NAME_PREFIX)) {
                count++;
            }
        }
        return count;
    }
}
