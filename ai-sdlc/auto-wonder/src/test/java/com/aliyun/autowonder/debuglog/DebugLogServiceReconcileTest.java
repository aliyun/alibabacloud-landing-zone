package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDao;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.capability;
import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.namer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogServiceReconcileTest {

    private DebugLogDao debugLogDao;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        debugLogDao = mock(DebugLogDao.class);
        storage = mock(ObjectStorage.class);
    }

    private DebugLogService service(V037MapperMode mapperMode) {
        return serviceWithBucket("test-artifact-bucket", mapperMode);
    }

    private DebugLogService serviceWithBucket(String artifactBucket, V037MapperMode mapperMode) {
        OssProperties props = new OssProperties();
        props.setArtifactBucket(artifactBucket);
        DispatchDao dispatchDao = mock(DispatchDao.class);
        // naming/key 集群已抽到 DebugLogObjectNamer（S10）：对账不碰命名，按真实组件装配即可。
        return new DebugLogService(debugLogDao, dispatchDao, mock(SquadDao.class),
                namer(dispatchDao, mock(AgentVersionDao.class), mock(ScheduledTaskRunDao.class)),
                storage, props, capability(mapperMode));
    }

    private DebugLogDO pendingRow(long id, String objectKey) {
        DebugLogDO row = new DebugLogDO();
        row.setId(id);
        row.setStatus(DebugLogStatus.PENDING);
        row.setObjectKey(objectKey);
        return row;
    }

    @Test
    void marksUploadedWhenObjectExists() {
        DebugLogDO row = pendingRow(21L, "debug/200/DevAgent-run-1.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(row));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-1.log.gz")).thenReturn(true);
        when(debugLogDao.markReconciled(21L, DebugLogStatus.UPLOADED, null)).thenReturn(1);

        assertEquals(1, service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce());

        verify(debugLogDao).markReconciled(21L, DebugLogStatus.UPLOADED, null);
    }

    @Test
    void marksFailedWhenObjectMissing() {
        DebugLogDO row = pendingRow(22L, "debug/200/DevAgent-run-2.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(row));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-2.log.gz")).thenReturn(false);
        when(debugLogDao.markReconciled(eq(22L), eq(DebugLogStatus.FAILED),
                eq("PENDING_TIMEOUT: object missing after 24h"))).thenReturn(1);

        assertEquals(1, service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce());

        verify(debugLogDao).markReconciled(22L, DebugLogStatus.FAILED,
                "PENDING_TIMEOUT: object missing after 24h");
    }

    @Test
    void onlyScansRowsOlderThan24Hours() {
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of());
        long before = System.currentTimeMillis();

        service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce();

        org.mockito.ArgumentCaptor<Long> cutoff = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(debugLogDao).listPendingOlderThan(cutoff.capture(), eq(200));
        long expected = before - 24 * 60 * 60 * 1000L;
        org.junit.jupiter.api.Assertions.assertTrue(Math.abs(cutoff.getValue() - expected) < 60_000L,
                "cutoff must sit ~24h in the past, got " + cutoff.getValue());
    }

    @Test
    void continuesPastIndividualStorageFailures() {
        DebugLogDO bad = pendingRow(23L, "debug/200/DevAgent-run-3.log.gz");
        DebugLogDO good = pendingRow(24L, "debug/200/DevAgent-run-4.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(bad, good));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-3.log.gz"))
                .thenThrow(new RuntimeException("oss timeout"));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-4.log.gz")).thenReturn(true);
        when(debugLogDao.markReconciled(24L, DebugLogStatus.UPLOADED, null)).thenReturn(1);

        assertEquals(1, service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce());

        verify(debugLogDao).markReconciled(24L, DebugLogStatus.UPLOADED, null);
    }

    @Test
    void skippedEntirelyOnLegacySchema() {
        assertEquals(0, service(V037MapperMode.LEGACY).reconcilePendingOnce());

        verifyNoInteractions(debugLogDao, storage);
    }

    /** 单行失败必须留下可 grep 的痕迹（沿用 S6 I2 的 {@code reason=<TOKEN>} 形态），且不得收敛该行。 */
    @Test
    void individualRowFailureWarnsWithReasonToken() {
        DebugLogDO bad = pendingRow(23L, "debug/200/DevAgent-run-3.log.gz");
        bad.setDispatchId(900L);
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(bad));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-3.log.gz"))
                .thenThrow(new RuntimeException("oss timeout"));

        List<String> warns = captureWarns(() -> assertEquals(0,
                service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce()));

        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "debugLogId=23",
                "objectKey=debug/200/DevAgent-run-3.log.gz",
                "reason=DEBUG_LOG_RECONCILE_ROW_FAILED");
        verify(debugLogDao, never()).markReconciled(any(), any(), any());
    }

    /** best-effort：DB 侧单行失败同样不得中止整批（任务规则「任何单行失败不得中止整批」）。 */
    @Test
    void continuesPastIndividualDatabaseFailures() {
        DebugLogDO bad = pendingRow(25L, "debug/200/DevAgent-run-5.log.gz");
        DebugLogDO good = pendingRow(26L, "debug/200/DevAgent-run-6.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(bad, good));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-5.log.gz")).thenReturn(false);
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-6.log.gz")).thenReturn(true);
        when(debugLogDao.markReconciled(eq(25L), eq(DebugLogStatus.FAILED), anyString()))
                .thenThrow(new RuntimeException("mysql gone"));
        when(debugLogDao.markReconciled(26L, DebugLogStatus.UPLOADED, null)).thenReturn(1);

        assertEquals(1, service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce());

        verify(debugLogDao).markReconciled(26L, DebugLogStatus.UPLOADED, null);
    }

    /** 已 UPLOADED 的并发收敛（markReconciled 的 status='PENDING' 守卫落空）按非错误计入 0。 */
    @Test
    void rowSettledConcurrentlyCountsAsZeroWithoutWarning() {
        DebugLogDO row = pendingRow(27L, "debug/200/DevAgent-run-7.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(row));
        when(storage.exists("test-artifact-bucket/debug/200/DevAgent-run-7.log.gz")).thenReturn(true);
        when(debugLogDao.markReconciled(27L, DebugLogStatus.UPLOADED, null)).thenReturn(0);

        List<String> warns = captureWarns(() -> assertEquals(0,
                service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce()));

        assertTrue(warns.isEmpty(), "并发收敛不是错误，不得告警：" + warns);
    }

    /**
     * I1 对账可观测性：每轮恰一条 sweep 汇总 INFO（scanned/uploaded/failed/skipped breakdown），
     * 实际写 FAILED 的行逐条留 MARK_FAILED INFO；rows==0 的并发收敛不留 MARK_FAILED 痕迹、
     * 只计入 scanned；skipped 只数单行异常（ROW_FAILED warn 照旧，不在 INFO 里重复）。
     */
    @Test
    void sweepLogsBreakdownAndIndividualFailedRows() {
        DebugLogDO uploaded = pendingRow(31L, "debug/300/DevAgent-run-1.log.gz");
        DebugLogDO failed = pendingRow(32L, "debug/300/DevAgent-run-2.log.gz");
        failed.setDispatchId(902L);
        DebugLogDO raced = pendingRow(33L, "debug/300/DevAgent-run-3.log.gz");
        DebugLogDO boom = pendingRow(34L, "debug/300/DevAgent-run-4.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200)))
                .thenReturn(List.of(uploaded, failed, raced, boom));
        when(storage.exists("test-artifact-bucket/debug/300/DevAgent-run-1.log.gz")).thenReturn(true);
        when(storage.exists("test-artifact-bucket/debug/300/DevAgent-run-2.log.gz")).thenReturn(false);
        when(storage.exists("test-artifact-bucket/debug/300/DevAgent-run-3.log.gz")).thenReturn(true);
        when(storage.exists("test-artifact-bucket/debug/300/DevAgent-run-4.log.gz"))
                .thenThrow(new RuntimeException("oss timeout"));
        when(debugLogDao.markReconciled(31L, DebugLogStatus.UPLOADED, null)).thenReturn(1);
        when(debugLogDao.markReconciled(eq(32L), eq(DebugLogStatus.FAILED), anyString())).thenReturn(1);
        when(debugLogDao.markReconciled(33L, DebugLogStatus.UPLOADED, null)).thenReturn(0);

        List<String> infos = captureAt(Level.INFO, () -> assertEquals(2,
                service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce()));

        List<String> sweeps = infos.stream()
                .filter(message -> message.contains("reason=DEBUG_LOG_RECONCILE_SWEEP"))
                .toList();
        assertEquals(1, sweeps.size(), "每轮对账恰一条 sweep 汇总：" + infos);
        assertTokens(sweeps.get(0), "scanned=4", "uploaded=1", "failed=1", "skipped=1");
        List<String> marks = infos.stream()
                .filter(message -> message.contains("reason=DEBUG_LOG_RECONCILE_MARK_FAILED"))
                .toList();
        assertEquals(1, marks.size(), "只有实际写 FAILED 的行才留 MARK_FAILED 痕迹：" + infos);
        assertTokens(marks.get(0), "debugLogId=32", "dispatchId=902",
                "objectKey=debug/300/DevAgent-run-2.log.gz");
    }

    /** I1：scanned=0 的空扫也打心跳——运维需要「任务确实跑过」的证据（DispatchCompensationTask 先例）。 */
    @Test
    void idleSweepStillLogsHeartbeatLine() {
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of());

        List<String> infos = captureAt(Level.INFO, () -> assertEquals(0,
                service(V037MapperMode.SOURCE_AWARE).reconcilePendingOnce()));

        assertEquals(1, infos.size(), infos.toString());
        assertTokens(infos.get(0), "scanned=0", "uploaded=0", "failed=0", "skipped=0",
                "reason=DEBUG_LOG_RECONCILE_SWEEP");
    }

    /**
     * I2：oss.artifact-bucket 未配置（null/blank）时整轮放弃——bucket() 回落的字面量不匹配任何
     * 环境配置，OSS doesObjectExist 对 NoSuchBucket 返回 false 不抛（S3 404 同理），继续扫会把
     * 全部过期 PENDING 行批量误标 FAILED 且零诊断。
     */
    @Test
    void nullBucketSkipsSweepWithErrorLog() {
        assertBucketGuardRejects(null);
    }

    @Test
    void blankBucketSkipsSweepWithErrorLog() {
        assertBucketGuardRejects("   ");
    }

    private void assertBucketGuardRejects(String configuredBucket) {
        DebugLogDO row = pendingRow(41L, "debug/400/DevAgent-run-1.log.gz");
        when(debugLogDao.listPendingOlderThan(anyLong(), eq(200))).thenReturn(List.of(row));

        List<String> errors = captureAt(Level.ERROR, () -> assertEquals(0,
                serviceWithBucket(configuredBucket, V037MapperMode.SOURCE_AWARE)
                        .reconcilePendingOnce()));

        assertEquals(1, errors.size(), errors.toString());
        assertTokens(errors.get(0), "reason=DEBUG_LOG_RECONCILE_BUCKET_UNCONFIGURED");
        verifyNoInteractions(debugLogDao, storage);
    }

    private static void assertTokens(String message, String... tokens) {
        for (String token : tokens) {
            assertTrue(message.contains(token), () -> "日志缺少 " + token + "：" + message);
        }
    }

    /** 仿 {@code DebugLogServiceResultReportTest} 的 log4j2 捕获器：收 DebugLogService 指定级别的日志行。 */
    private static List<String> captureAt(Level level, Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(DebugLogService.class);
        Level previousLevel = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.events.stream()
                .filter(event -> event.getLevel() == level)
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private static List<String> captureWarns(Runnable action) {
        return captureAt(Level.WARN, action);
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("debuglog-reconcile-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
