package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledTaskScannerLifecycleTest {
    @Test
    void twoScannersKeepTransactionalSingleDeliveryWhileSharedCleanupIsBlocked() throws Exception {
        Fixture fixture = new Fixture();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Fixture.class, () -> fixture);
            context.register(Config.class);
            context.refresh();
            try {
                assertTrue(fixture.cleanup.started.await(5, TimeUnit.SECONDS));
                when(fixture.guard.isScannerEnabled()).thenReturn(true);
                assertTrue(fixture.committed.await(5, TimeUnit.SECONDS), "scanner stalled behind cleanup");
                assertTrue(fixture.bothScanners.await(5, TimeUnit.SECONDS));
                assertEquals(1L, fixture.cleanup.release.getCount(), "cleanup must still be blocked");
                assertEquals(2, fixture.attempts.get(), "failed trigger rolls back and is retried once");
                assertEquals(1, fixture.jdbc.queryForObject("SELECT claimed FROM scan_cursor", Integer.class));
                assertEquals(List.of(true, true), List.copyOf(fixture.transactionActive));
                assertTrue(fixture.scannerThreads.stream()
                        .allMatch(thread -> thread.getName().startsWith("scheduled-task-scanner-")));
            } finally {
                fixture.cleanup.release.countDown();
            }
        }
        for (Thread thread : fixture.scannerThreads) {
            thread.join(1000);
            assertFalse(thread.isAlive(), "scanner thread survived context shutdown");
        }
    }

    static class Fixture {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:scanner-" + UUID.randomUUID(), "sa", "");
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        final ScheduledTaskDao tasks = mock(ScheduledTaskDao.class);
        final ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);
        final ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        final RedisManager redis = mock(RedisManager.class);
        final BlockingCleanup cleanup = new BlockingCleanup();
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch bothScanners = new CountDownLatch(2);
        final AtomicInteger attempts = new AtomicInteger();
        final Set<Thread> scannerThreads = ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<Boolean> transactionActive = new ConcurrentLinkedQueue<>();

        Fixture() {
            dataSource.setUrl(dataSource.getUrl() + ";DB_CLOSE_DELAY=-1");
            jdbc.execute("CREATE TABLE scan_cursor (claimed INT NOT NULL)");
            jdbc.update("INSERT INTO scan_cursor VALUES (0)");
            ScheduledTaskDO task = new ScheduledTaskDO();
            task.setId(9L); task.setWorkspaceId(1L); task.setVersion(0); task.setCreatorId(3L);
            task.setScheduleType("ONCE"); task.setStatus("ACTIVE");
            task.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
            task.setStartDeadlineSeconds(60);
            when(tasks.findDue(any(), anyInt())).thenAnswer(invocation ->
                    jdbc.queryForObject("SELECT claimed FROM scan_cursor", Integer.class) == 0
                            ? List.of(task) : List.of());
            when(tasks.claimNextFire(anyLong(), anyLong(), anyInt(), any(), any(), any(), anyString(), anyLong()))
                    .thenAnswer(invocation -> jdbc.update("UPDATE scan_cursor SET claimed=1 WHERE claimed=0"));
            AtomicReference<String> lock = new AtomicReference<>();
            when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
                if (scannerThreads.add(Thread.currentThread())) bothScanners.countDown();
                return lock.compareAndSet(null, invocation.getArgument(1));
            });
            doAnswer(invocation -> {
                lock.compareAndSet(invocation.getArgument(1), null);
                return null;
            }).when(redis).releaseLock(anyString(), anyString());
            when(trigger.fireScheduled(any(), any(), any())).thenAnswer(invocation -> {
                transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("injected trigger failure");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { committed.countDown(); }
                });
                return null;
            });
        }
    }

    static class BlockingCleanup {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Scheduled(fixedDelay = 10)
        public void cleanup() throws InterruptedException {
            started.countDown();
            release.await();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableTransactionManagement
    static class Config {
        @Bean PlatformTransactionManager transactionManager(Fixture fixture) {
            return new DataSourceTransactionManager(fixture.dataSource);
        }
        @Bean ThreadPoolTaskScheduler taskScheduler() { return new ThreadPoolTaskScheduler(); }
        @Bean BlockingCleanup cleanup(Fixture fixture) { return fixture.cleanup; }
        @Bean ScheduledTaskProperties properties() {
            ScheduledTaskProperties properties = new ScheduledTaskProperties();
            properties.setScanFixedDelayMs(20);
            return properties;
        }
        @Bean ScheduledTaskScheduler scannerOne(Fixture fixture, ScheduledTaskProperties properties) {
            return new ScheduledTaskScheduler(fixture.tasks, fixture.trigger, new ScheduledTaskSchedule(),
                    fixture.redis, properties, fixture.guard);
        }
        @Bean ScheduledTaskScheduler scannerTwo(Fixture fixture, ScheduledTaskProperties properties) {
            return new ScheduledTaskScheduler(fixture.tasks, fixture.trigger, new ScheduledTaskSchedule(),
                    fixture.redis, properties, fixture.guard);
        }
        @Bean ScheduledTaskScannerLifecycle lifecycleOne(
                @Qualifier("scannerOne") ScheduledTaskScheduler scanner, ScheduledTaskProperties properties) {
            return new ScheduledTaskScannerLifecycle(scanner, properties);
        }
        @Bean ScheduledTaskScannerLifecycle lifecycleTwo(
                @Qualifier("scannerTwo") ScheduledTaskScheduler scanner, ScheduledTaskProperties properties) {
            return new ScheduledTaskScannerLifecycle(scanner, properties);
        }
    }
}
