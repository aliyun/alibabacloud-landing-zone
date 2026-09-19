package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.capability;
import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.namer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogReconciliationTaskTest {

    private static final String LOCK_KEY = "debuglog:reconciliation:lock";
    private static final long LOCK_TTL_MS = 5 * 60_000L;

    @Test
    void skipsWhenLockNotAcquired() {
        DebugLogService service = mock(DebugLogService.class);
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(eq(LOCK_KEY), anyString(), anyLong())).thenReturn(false);

        new DebugLogReconciliationTask(service, redis).reconcile();

        verifyNoInteractions(service);
        // M1（WorkitemScheduledStartScannerTest 先例）：没拿到锁就不得释放别人的锁。
        verify(redis, never()).releaseLock(anyString(), anyString());
    }

    @Test
    void runsUnderLockAndReleasesIt() {
        DebugLogService service = mock(DebugLogService.class);
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(eq(LOCK_KEY), anyString(), anyLong())).thenReturn(true);

        new DebugLogReconciliationTask(service, redis).reconcile();

        verify(service).reconcilePendingOnce();
        // M1：捕获 acquire 的 owner token，release 必须以同一 token 释放（防误删他人锁）。
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(redis).tryAcquireLock(eq(LOCK_KEY), owner.capture(), eq(LOCK_TTL_MS));
        verify(redis).releaseLock(LOCK_KEY, owner.getValue());
    }

    @Test
    void swallowsServiceFailureAndStillReleasesLock() {
        DebugLogService service = mock(DebugLogService.class);
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(eq(LOCK_KEY), anyString(), anyLong())).thenReturn(true);
        doThrow(new RuntimeException("db down")).when(service).reconcilePendingOnce();

        assertDoesNotThrow(() -> new DebugLogReconciliationTask(service, redis).reconcile());

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(redis).tryAcquireLock(eq(LOCK_KEY), owner.capture(), eq(LOCK_TTL_MS));
        verify(redis).releaseLock(LOCK_KEY, owner.getValue());
    }

    /**
     * I1 端到端钉住 sweep 日志行：定时任务持锁跑一轮真实 {@code reconcilePendingOnce}，
     * scanned=0 的空扫也必须留下 idle 心跳（运维需要「任务确实跑过」的证据，
     * DispatchCompensationTask 无条件汇总先例）；storage 不被触碰。
     */
    @Test
    void scheduledRunLogsSweepHeartbeatEvenWhenIdle() {
        DebugLogDao debugLogDao = mock(DebugLogDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of());
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DebugLogService realService = new DebugLogService(debugLogDao, dispatchDao,
                mock(SquadDao.class),
                namer(dispatchDao, mock(AgentVersionDao.class), mock(ScheduledTaskRunDao.class)),
                storage, props, capability(V037MapperMode.SOURCE_AWARE));
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(eq(LOCK_KEY), anyString(), anyLong())).thenReturn(true);

        List<String> infos = captureServiceInfos(
                () -> new DebugLogReconciliationTask(realService, redis).reconcile());

        assertEquals(1, infos.size(), infos.toString());
        assertEquals("debug log reconciliation swept scanned=0 uploaded=0 failed=0 skipped=0 "
                + "reason=DEBUG_LOG_RECONCILE_SWEEP", infos.get(0));
        verifyNoInteractions(storage);
    }

    /** 仿 {@code DebugLogServiceReconcileTest} 的 log4j2 捕获器：收 DebugLogService 的 INFO。 */
    private static List<String> captureServiceInfos(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(DebugLogService.class);
        Level previousLevel = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.events.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("debuglog-reconciliation-task-test", null,
                    PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
