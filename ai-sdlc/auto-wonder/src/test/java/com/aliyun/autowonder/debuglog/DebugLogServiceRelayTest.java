package com.aliyun.autowonder.debuglog;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.capability;
import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.namer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebugLogServiceRelayTest {

    private DebugLogDao debugLogDao;
    private DispatchDao dispatchDao;
    private AgentVersionDao agentVersionDao;

    @BeforeEach
    void setUp() {
        debugLogDao = mock(DebugLogDao.class);
        dispatchDao = mock(DispatchDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
    }

    private DebugLogService service() {
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        return new DebugLogService(debugLogDao, dispatchDao, mock(SquadDao.class),
                namer(dispatchDao, agentVersionDao, mock(ScheduledTaskRunDao.class)),
                mock(ObjectStorage.class), props, capability(V037MapperMode.SOURCE_AWARE));
    }

    private DispatchDO dispatch() {
        DispatchDO d = new DispatchDO();
        d.setId(900L);
        d.setTenantId(100L);
        d.setSourceType("WORKITEM");
        d.setWorkitemId(200L);
        d.setAgentId(400L);
        d.setAgentVersionId(410L);
        d.setExecutorId(5L);
        d.setStatus("SUCCEEDED");
        d.setDebugLogEnabled(true);
        d.setGmtCreate(new Date(1_000L));
        return d;
    }

    private void stubRoleCode() {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(410L);
        version.setTenantId(100L);
        version.setRoleCode("DevAgent");
        when(agentVersionDao.findById(410L)).thenReturn(version);
    }

    @Test
    void relayTargetIsNullWhenDispatchNotDebugEnabled() {
        DispatchDO d = dispatch();
        d.setDebugLogEnabled(false);
        when(dispatchDao.findById(900L)).thenReturn(d);

        assertNull(service().relayTarget(100L, 900L));
    }

    @Test
    void relayTargetIsNullForForeignTenantOrMissingDispatch() {
        when(dispatchDao.findById(900L)).thenReturn(null);
        assertNull(service().relayTarget(100L, 900L));

        when(dispatchDao.findById(900L)).thenReturn(dispatch());
        assertNull(service().relayTarget(101L, 900L));
    }

    @Test
    void relayTargetComputesCanonicalKeyIdenticalToDirectIssue() {
        DispatchDO self = dispatch();
        when(dispatchDao.findById(900L)).thenReturn(self);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode();

        DebugLogService.RelayTarget target = service().relayTarget(100L, 900L);

        assertEquals("debug/200/DevAgent-run-1.log.gz", target.objectKey());
        assertEquals(1, target.runNo());
    }

    @Test
    void relayUploadInsertsUploadedRowWithMetadataShaAndSize() {
        DispatchDO self = dispatch();
        when(dispatchDao.findById(900L)).thenReturn(self);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        JSONObject meta = JSON.parseObject("{\"path\":\"debug/DevAgent-900.log.gz\","
                + "\"sha256\":\"" + "a".repeat(64) + "\",\"sizeBytes\":7}");

        service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz", 1, 7L, meta);

        ArgumentCaptor<DebugLogDO> cap = ArgumentCaptor.forClass(DebugLogDO.class);
        verify(debugLogDao).insert(cap.capture());
        DebugLogDO row = cap.getValue();
        assertEquals(DebugLogStatus.UPLOADED, row.getStatus());
        assertEquals("RELAY", row.getUploadChannel());
        assertEquals(7L, row.getSizeBytes());
        assertEquals("a".repeat(64), row.getSha256());
        assertEquals("SUCCEEDED", row.getDispatchStatus());
        assertEquals(1, row.getRunNo());
        assertEquals("debug/200/DevAgent-run-1.log.gz", row.getObjectKey());
        assertEquals(false, row.getTruncated());
    }

    @Test
    void relayUploadUpdatesExistingRowWithoutClobberingTruncation() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch());
        DebugLogDO row = new DebugLogDO();
        row.setId(12L);
        row.setStatus(DebugLogStatus.PENDING);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(row);
        JSONObject meta = JSON.parseObject("{\"sha256\":\"" + "a".repeat(64) + "\"}");

        service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz", 1, 7L, meta);

        verify(debugLogDao).updateOnResult(12L, DebugLogStatus.UPLOADED, "RELAY", 7L,
                "a".repeat(64), null, "SUCCEEDED", null);
        verify(debugLogDao, never()).insert(any());
    }

    @Test
    void relayUploadIsIgnoredForNonDebugDispatch() {
        DispatchDO d = dispatch();
        d.setDebugLogEnabled(null);
        when(dispatchDao.findById(900L)).thenReturn(d);

        service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz", 1, 7L, null);

        verify(debugLogDao, never()).insert(any());
        verify(debugLogDao, never()).updateOnResult(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void relayUploadOnRunningDispatchRecordsProvisionalStatus() {
        DispatchDO d = dispatch();
        d.setStatus("RUNNING");
        when(dispatchDao.findById(900L)).thenReturn(d);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);

        service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz", 1, 7L, null);

        ArgumentCaptor<DebugLogDO> cap = ArgumentCaptor.forClass(DebugLogDO.class);
        verify(debugLogDao).insert(cap.capture());
        // 设计文档 §4.6：中转先行登记，dispatch_status/truncated 以 TASK_RESULT 回报收尾
        assertEquals("RUNNING", cap.getValue().getDispatchStatus());
        assertNull(cap.getValue().getSha256());
    }

    // 补插竞态（S10 适配，S9 Issue 1 同款）：竞态分支必须留 reason token 痕迹，不得静默。

    @Test
    void relayInsertRaceFallsBackToUpdateOnWinnerRow() {
        DispatchDO self = dispatch();
        when(dispatchDao.findById(900L)).thenReturn(self);
        DebugLogDO winner = new DebugLogDO();
        winner.setId(13L);
        winner.setStatus(DebugLogStatus.PENDING);
        // 首次 findByDispatchId 返回 null（触发补插），竞态后重读返回 winner。
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("uk_dispatch")).when(debugLogDao).insert(any(DebugLogDO.class));

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz",
                        1, 7L, null));

        // 与结果路径同款收敛：winner 行改走 updateOnResult（dispatchStatus 传 null 保留既有值）。
        verify(debugLogDao).updateOnResult(13L, DebugLogStatus.UPLOADED, "RELAY", 7L, null,
                null, null, null);
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "winnerId=13",
                "reason=DEBUG_LOG_INSERT_RACE");
    }

    @Test
    void relayInsertRaceWithoutReadableWinnerWarnsAndReturns() {
        DispatchDO self = dispatch();
        when(dispatchDao.findById(900L)).thenReturn(self);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_dispatch")).when(debugLogDao).insert(any(DebugLogDO.class));

        // best-effort 中转登记路径：读不到 winner 也不 rethrow（controller 侧另有 catch-all，
        // rethrow 只会变成一条泛化 warn），留 INSERT_RACE_UNREADABLE 痕迹后正常返回。
        List<String> warns = captureAt(Level.WARN,
                () -> service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz",
                        1, 7L, null));

        verify(debugLogDao, never()).updateOnResult(any(), any(), any(), any(), any(), any(),
                any(), any());
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900",
                "reason=DEBUG_LOG_INSERT_RACE_UNREADABLE");
    }

    // S10 Important#1：中转 filesMetadata 的 sha256 是第二个消费点，同一套消毒规则。

    @ParameterizedTest(name = "[{index}] reported={0}")
    @MethodSource("com.aliyun.autowonder.debuglog.DebugLogServiceResultReportTest#sha256Vectors")
    void relaySha256IsSanitizedBeforePersisting(String reported, String expected) {
        when(dispatchDao.findById(900L)).thenReturn(dispatch());
        DebugLogDO row = new DebugLogDO();
        row.setId(12L);
        row.setStatus(DebugLogStatus.PENDING);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(row);
        JSONObject meta = new JSONObject();
        meta.put("sha256", reported);

        service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz", 1, 7L, meta);

        ArgumentCaptor<String> sha = ArgumentCaptor.forClass(String.class);
        verify(debugLogDao).updateOnResult(eq(12L), eq(DebugLogStatus.UPLOADED), eq("RELAY"),
                eq(7L), sha.capture(), isNull(), eq("SUCCEEDED"), isNull());
        assertEquals(expected, sha.getValue());
        assertTrue(sha.getValue() == null
                        || sha.getValue().codePointCount(0, sha.getValue().length()) <= 80,
                "入库 sha256 不得超过列宽 80：" + sha.getValue());
    }

    @Test
    void relayUploadWithoutMetadataEntryStoresNullSha256() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch());
        DebugLogDO row = new DebugLogDO();
        row.setId(12L);
        row.setStatus(DebugLogStatus.PENDING);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(row);

        service().recordRelayUpload(100L, 900L, "debug/200/DevAgent-run-1.log.gz", 1, 7L, null);

        verify(debugLogDao).updateOnResult(eq(12L), eq(DebugLogStatus.UPLOADED), eq("RELAY"),
                eq(7L), isNull(), isNull(), eq("SUCCEEDED"), isNull());
    }

    private static void assertTokens(String message, String... tokens) {
        for (String token : tokens) {
            assertTrue(message.contains(token), () -> "日志缺少 " + token + "：" + message);
        }
    }

    /** 仿 {@code DebugLogServiceResultReportTest} 的 log4j2 捕获器：收 DebugLogService 指定级别的格式化消息。 */
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

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("debuglog-relay-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
