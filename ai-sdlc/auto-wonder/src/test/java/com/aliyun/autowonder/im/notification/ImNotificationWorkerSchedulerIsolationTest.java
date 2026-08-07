package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.im.ImProvider;
import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImNotificationWorkerSchedulerIsolationTest {

    @Test
    void imTaskSchedulerHasPoolSizeTwo() {
        ImSchedulerConfiguration config = new ImSchedulerConfiguration();
        ThreadPoolTaskScheduler scheduler = config.imTaskScheduler();
        try {
            assertEquals(2, scheduler.getPoolSize());
            assertEquals("im-scheduler-", scheduler.getThreadNamePrefix());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void registrarRegistersPollAndRecoveryTasks() {
        ImNotificationProperties properties = new ImNotificationProperties();
        properties.setPollDelayMs(2000L);
        properties.setRecoveryDelayMs(3000L);

        Fixture fixture = new Fixture();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("im-test-");
        scheduler.initialize();

        try {
            ImSchedulerConfiguration.ImScheduledTaskRegistrar registrar =
                    new ImSchedulerConfiguration.ImScheduledTaskRegistrar(scheduler, fixture.worker, properties);
            registrar.afterPropertiesSet();

            Set<ScheduledTask> tasks = registrar.getScheduledTasks();
            assertEquals(2, tasks.size());

            boolean foundPoll = false;
            boolean foundRecover = false;
            for (ScheduledTask task : tasks) {
                FixedDelayTask fdt = (FixedDelayTask) task.getTask();
                long intervalMs = fdt.getInterval();
                if (intervalMs == 2000L) {
                    foundPoll = true;
                } else if (intervalMs == 3000L) {
                    foundRecover = true;
                }
            }
            assertTrue(foundPoll, "should register pollNew task with pollDelayMs");
            assertTrue(foundRecover, "should register recoverStale task with recoveryDelayMs");

            registrar.destroy();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void imSchedulerIsNotBlockedBySlowTaskOnDefaultScheduler() throws Exception {
        ThreadPoolTaskScheduler defaultScheduler = new ThreadPoolTaskScheduler();
        defaultScheduler.setPoolSize(1);
        defaultScheduler.initialize();

        ThreadPoolTaskScheduler imScheduler = new ThreadPoolTaskScheduler();
        imScheduler.setPoolSize(2);
        imScheduler.setThreadNamePrefix("im-scheduler-");
        imScheduler.initialize();

        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingDone = new CountDownLatch(1);

        try {
            defaultScheduler.schedule(() -> {
                blockingStarted.countDown();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                blockingDone.countDown();
            }, new java.util.Date());

            assertTrue(blockingStarted.await(2, TimeUnit.SECONDS));

            CountDownLatch imTaskRan = new CountDownLatch(1);
            imScheduler.schedule(() -> imTaskRan.countDown(), new java.util.Date());

            assertTrue(imTaskRan.await(2, TimeUnit.SECONDS),
                    "IM scheduler task should run within 2s despite default scheduler being blocked");
            assertFalse(blockingDone.await(100, TimeUnit.MILLISECONDS),
                    "default scheduler task should still be sleeping");
        } finally {
            defaultScheduler.shutdown();
            imScheduler.shutdown();
        }
    }

    @Test
    void batchOfMultipleEnvelopesProcessesConcurrently() throws Exception {
        int count = 4;
        CountDownLatch allStarted = new CountDownLatch(count);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(count);

        Fixture fixture = new Fixture();
        UserImIdentityDO identity = identity("staff-001");
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity);
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            allStarted.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS),
                    "release latch should fire before timeout");
            allDone.countDown();
            return null;
        }).when(fixture.provider).send(org.mockito.ArgumentMatchers.any());

        List<ImNotificationEnvelope> envelopes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            envelopes.add(new ImNotificationEnvelope(
                    (System.currentTimeMillis() - 100) + "-" + i, task("key-" + i), 1L));
        }

        Thread poller = new Thread(() -> fixture.worker.processAll(envelopes));
        poller.start();

        assertTrue(allStarted.await(3, TimeUnit.SECONDS),
                "all 4 sends should start concurrently within 3s");
        release.countDown();
        assertTrue(allDone.await(5, TimeUnit.SECONDS),
                "all sends should complete after release");
    }

    @Test
    void singleEnvelopeProcessesSynchronouslyOnCallerThread() throws Exception {
        Fixture fixture = new Fixture();
        UserImIdentityDO identity = identity("staff-001");
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity);
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        AtomicLong callerThreadId = new AtomicLong(Thread.currentThread().getId());
        AtomicLong sendThreadId = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocation -> {
            sendThreadId.set(Thread.currentThread().getId());
            return null;
        }).when(fixture.provider).send(org.mockito.ArgumentMatchers.any());

        ImNotificationEnvelope envelope = new ImNotificationEnvelope(
                (System.currentTimeMillis() - 50) + "-0", task("single-key"), 1L);
        fixture.worker.processAll(List.of(envelope));

        assertEquals(callerThreadId.get(), sendThreadId.get(),
                "single envelope should run on caller thread, not send executor");
    }

    @Test
    void processAllWithEmptyListDoesNothing() {
        Fixture fixture = new Fixture();
        fixture.worker.processAll(List.of());
        fixture.worker.processAll(null);
    }

    private static ImNotificationTask task(String notificationKey) {
        return new ImNotificationTask(
                notificationKey,
                100L,
                7L,
                42L,
                9L,
                "USER",
                3L,
                "张三",
                "rid-test",
                "测试工单");
    }

    private static UserImIdentityDO identity(String externalUserId) {
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(9L);
        identity.setProvider("DINGTALK");
        identity.setExternalUserId(externalUserId);
        return identity;
    }

    private static final class Fixture {
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final UserImIdentityService identityService = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService channelConfigService = mock(PlatformImChannelConfigService.class);
        final ImProvider provider = mock(ImProvider.class);
        final ImNotificationMessageContextResolver contextResolver = mock(ImNotificationMessageContextResolver.class);
        final ImNotificationProperties properties = new ImNotificationProperties();
        final ImNotificationWorker worker;

        Fixture() {
            properties.setMaxAttempts(3);
            when(contextResolver.resolve(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ImNotificationMessageContext(
                            "测试组织", "待处理", "https://test.example.com", 7L));
            when(provider.provider()).thenReturn("DINGTALK");
            worker = new ImNotificationWorker(
                    queue,
                    new ImNotificationFormatter(),
                    identityService,
                    channelConfigService,
                    new ImProviderRegistry(List.of(provider)),
                    properties,
                    contextResolver);
        }
    }
}
