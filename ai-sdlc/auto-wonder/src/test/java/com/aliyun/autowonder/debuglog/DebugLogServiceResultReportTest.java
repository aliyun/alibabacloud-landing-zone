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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.capability;
import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.namer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogServiceResultReportTest {

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

    private DispatchDO dispatch(String status) {
        DispatchDO d = new DispatchDO();
        d.setId(900L);
        d.setTenantId(100L);
        d.setSourceType("WORKITEM");
        d.setWorkitemId(200L);
        d.setAgentId(400L);
        d.setAgentVersionId(410L);
        d.setExecutorId(5L);
        d.setStatus(status);
        d.setDebugLogEnabled(true);
        d.setGmtCreate(new Date(1_000L));
        return d;
    }

    private JSONObject report(String status) {
        return JSON.parseObject("{\"status\":\"" + status + "\",\"channel\":\"DIRECT\","
                + "\"sizeBytes\":123,\"sha256\":\"" + "a".repeat(64) + "\",\"truncated\":false}");
    }

    private void stubRoleCode() {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(410L);
        version.setTenantId(100L);
        version.setRoleCode("DevAgent");
        when(agentVersionDao.findById(410L)).thenReturn(version);
    }

    private void stubExistingRow(String rowStatus) {
        DebugLogDO row = new DebugLogDO();
        row.setId(12L);
        row.setStatus(rowStatus);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(row);
    }

    @Test
    void uploadedReportUpdatesExistingRow() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);

        service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED"));

        verify(debugLogDao).updateOnResult(12L, "UPLOADED", "DIRECT", 123L, "a".repeat(64),
                false, "SUCCEEDED", null);
        verify(debugLogDao, never()).insert(any());
    }

    @Test
    void failedReportStoresErrorTruncatedTo1024Chars() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("FAILED"));
        stubExistingRow(DebugLogStatus.PENDING);
        JSONObject failed = JSON.parseObject("{\"status\":\"FAILED\",\"channel\":\"DIRECT\","
                + "\"error\":\"" + "e".repeat(1100) + "\"}");

        service().recordTaskResultReport(100L, 5L, 900L, failed);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(debugLogDao).updateOnResult(eq(12L), eq("FAILED"), eq("DIRECT"), isNull(), isNull(),
                isNull(), eq("FAILED"), error.capture());
        assertEquals(1024, error.getValue().length());
    }

    @Test
    void errorTruncationNeverSplitsSurrogatePairs() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("FAILED"));
        stubExistingRow(DebugLogStatus.PENDING);
        // 1100 个 astral code point（Java length 2200）：必须按 code point 截到 1024，不切半代理对。
        JSONObject failed = JSON.parseObject("{\"status\":\"FAILED\",\"channel\":\"RELAY\","
                + "\"error\":\"" + "\uD83E\uDD16".repeat(1100) + "\"}");

        service().recordTaskResultReport(100L, 5L, 900L, failed);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(debugLogDao).updateOnResult(eq(12L), eq("FAILED"), eq("RELAY"), isNull(), isNull(),
                isNull(), eq("FAILED"), error.capture());
        String value = error.getValue();
        // error_message VARCHAR(1024) 按 code point 计数：截断后恰好 1024 个 code point，
        // 且最后一个 UTF-16 单元不是悬空 high surrogate。
        assertEquals(1024, value.codePointCount(0, value.length()));
        assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
        assertTrue(Character.isLowSurrogate(value.charAt(value.length() - 1)));
    }

    @Test
    void errorWithinColumnWidthIsNotTruncatedEvenIfUtf16Longer() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("FAILED"));
        stubExistingRow(DebugLogStatus.PENDING);
        // 600 个 astral code point → Java length 1200 > 1024，但列宽按 code point 计，不得截断。
        JSONObject failed = JSON.parseObject("{\"status\":\"FAILED\",\"channel\":\"DIRECT\","
                + "\"error\":\"" + "\uD83E\uDD16".repeat(600) + "\"}");

        service().recordTaskResultReport(100L, 5L, 900L, failed);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(debugLogDao).updateOnResult(eq(12L), eq("FAILED"), eq("DIRECT"), isNull(), isNull(),
                isNull(), eq("FAILED"), error.capture());
        assertEquals(600, error.getValue().codePointCount(0, error.getValue().length()));
        assertEquals(1200, error.getValue().length());
    }

    @Test
    void foreignExecutorOrTenantReportIsIgnored() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));

        service().recordTaskResultReport(100L, 6L, 900L, report("UPLOADED"));
        service().recordTaskResultReport(101L, 5L, 900L, report("UPLOADED"));

        verifyNoInteractions(debugLogDao);
    }

    @Test
    void reportForNonDebugDispatchIsIgnored() {
        DispatchDO d = dispatch("SUCCEEDED");
        d.setDebugLogEnabled(null);
        when(dispatchDao.findById(900L)).thenReturn(d);

        service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED"));

        verifyNoInteractions(debugLogDao);
    }

    @Test
    void unknownStatusReportIsIgnoredWithReasonToken() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, report("MAYBE")));

        verifyNoInteractions(debugLogDao);
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "reason=DEBUG_LOG_REPORT_BAD_STATUS");
    }

    @Test
    void missingDispatchIsIgnored() {
        when(dispatchDao.findById(900L)).thenReturn(null);

        service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED"));

        verifyNoInteractions(debugLogDao);
    }

    @Test
    void reportWithoutRowInsertsTerminalRound() {
        DispatchDO self = dispatch("SUCCEEDED");
        when(dispatchDao.findById(900L)).thenReturn(self);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode();

        service().recordTaskResultReport(100L, 5L, 900L, report("FAILED"));

        ArgumentCaptor<DebugLogDO> cap = ArgumentCaptor.forClass(DebugLogDO.class);
        verify(debugLogDao).insert(cap.capture());
        DebugLogDO row = cap.getValue();
        assertEquals(DebugLogStatus.FAILED, row.getStatus());
        assertEquals("DIRECT", row.getUploadChannel());
        assertEquals("SUCCEEDED", row.getDispatchStatus());
        assertEquals(1, row.getRunNo());
        assertEquals("debug/200/DevAgent-run-1.log.gz", row.getObjectKey());
        assertEquals(123L, row.getSizeBytes());
    }

    @Test
    void reportWhileDispatchNotTerminalAndNoRowWarnsAndSkips() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("RUNNING"));
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED")));

        verify(debugLogDao, never()).insert(any());
        verify(debugLogDao, never()).updateOnResult(any(), any(), any(), any(), any(), any(),
                any(), any());
        // 协调者决策 S9-4：非终态跳过必须 warn 带 reason token（不是静默 info）。
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900",
                "reason=DEBUG_LOG_REPORT_DISPATCH_NOT_TERMINAL");
    }

    @Test
    void reportOnExistingRowKeepsProvisionalStatusWhenNotTerminal() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("PAUSED"));
        stubExistingRow(DebugLogStatus.PENDING);

        service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED"));

        verify(debugLogDao).updateOnResult(12L, "UPLOADED", "DIRECT", 123L, "a".repeat(64),
                false, null, null);
    }

    @Test
    void duplicateReportConvergedByUploadedGuardLogsInfoNotWarn() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.UPLOADED);
        // SQL 守卫 `status <> 'UPLOADED'` 命中 → rows==0：重复投递已收敛，非错误。
        when(debugLogDao.updateOnResult(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        List<String> infos = captureAt(Level.INFO,
                () -> service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED")));

        assertTrue(infos.stream().anyMatch(message ->
                        message.contains("dispatchId=900")
                                && message.contains("reason=DEBUG_LOG_RESULT_UPDATE_NO_ROW")),
                "rows==0 收敛必须留 info 痕迹：" + infos);
        assertTrue(infos.stream().noneMatch(message -> message.contains("reason=DEBUG_LOG_INSERT_RACE")),
                "正常 update 路径不得误报 insert race");
    }

    // Issue 1：结果路径补插竞态（recordTaskResultReport 内的 DuplicateKeyException 兜底）。

    @Test
    void insertRaceOnResultPathFallsBackToUpdateOnWinnerRow() {
        DispatchDO self = dispatch("SUCCEEDED");
        when(dispatchDao.findById(900L)).thenReturn(self);
        DebugLogDO winner = new DebugLogDO();
        winner.setId(13L);
        winner.setStatus(DebugLogStatus.PENDING);
        // 首次 findByDispatchId 返回 null（触发补插），竞态后重读返回 winner。
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null, winner);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode();
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_dispatch"))
                .when(debugLogDao).insert(any(DebugLogDO.class));

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED")));

        verify(debugLogDao).updateOnResult(13L, "UPLOADED", "DIRECT", 123L, "a".repeat(64),
                false, "SUCCEEDED", null);
        // 与签发路径 upsertPending 一致的可诊断留痕：reason token + winnerId，恰 1 条。
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "winnerId=13",
                "reason=DEBUG_LOG_INSERT_RACE");
    }

    @Test
    void insertRaceOnResultPathWithoutReadableWinnerWarnsAndReturns() {
        DispatchDO self = dispatch("SUCCEEDED");
        when(dispatchDao.findById(900L)).thenReturn(self);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode();
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_dispatch"))
                .when(debugLogDao).insert(any(DebugLogDO.class));

        // best-effort 结果路径：读不到 winner 也不 rethrow（router 层已有 catch-all），
        // 直接留 INSERT_RACE_UNREADABLE 痕迹；captureAt 不抛即证明方法正常返回。
        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED")));

        verify(debugLogDao, never()).updateOnResult(any(), any(), any(), any(), any(), any(),
                any(), any());
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900",
                "reason=DEBUG_LOG_INSERT_RACE_UNREADABLE");
    }

    // Issue 3：channel 白名单（upload_channel VARCHAR(16)，超长会让整条 update 抛异常）。

    @Test
    void legalChannelPassesThroughWithoutBadChannelWarn() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, report("UPLOADED")));

        verify(debugLogDao).updateOnResult(12L, "UPLOADED", "DIRECT", 123L, "a".repeat(64),
                false, "SUCCEEDED", null);
        assertTrue(warns.stream().noneMatch(m -> m.contains("DEBUG_LOG_REPORT_BAD_CHANNEL")),
                "合法 channel 不得触发 BAD_CHANNEL warn：" + warns);
    }

    @Test
    void illegalChannelStoredAsNullWithSingleWarn() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);
        String badChannel = "x".repeat(20);
        JSONObject bad = JSON.parseObject("{\"status\":\"UPLOADED\",\"channel\":\"" + badChannel
                + "\",\"sizeBytes\":123,\"sha256\":\"" + "a".repeat(64) + "\",\"truncated\":false}");

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, bad));

        // 非法 channel → upload_channel 存 null，其余字段照常收敛。
        verify(debugLogDao).updateOnResult(eq(12L), eq("UPLOADED"), isNull(), eq(123L),
                eq("a".repeat(64)), eq(false), eq("SUCCEEDED"), isNull());
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "channel=" + badChannel,
                "reason=DEBUG_LOG_REPORT_BAD_CHANNEL");
    }

    // S9 复审顺手项：>32 字符 channel 的截断分支 + code-point 安全 + 控制字符剥离（防日志注入）。

    @Test
    void channelLongerThan32CharsIsTruncatedInWarnLog() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);
        JSONObject bad = JSON.parseObject("{\"status\":\"UPLOADED\",\"channel\":\""
                + "x".repeat(40) + "\"}");

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, bad));

        verify(debugLogDao).updateOnResult(eq(12L), eq("UPLOADED"), isNull(), isNull(), isNull(),
                isNull(), eq("SUCCEEDED"), isNull());
        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "channel=" + "x".repeat(32),
                "reason=DEBUG_LOG_REPORT_BAD_CHANNEL");
        assertFalse(warns.get(0).contains("x".repeat(33)),
                "超过 32 字符的 channel 必须截断后入日志：" + warns.get(0));
    }

    @Test
    void channelTruncationIsCodePointSafe() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);
        // 40 个 astral code point（Java length 80 > 32）：按 code point 截到 32，不切半代理对。
        JSONObject bad = JSON.parseObject("{\"status\":\"UPLOADED\",\"channel\":\""
                + "\uD83E\uDD16".repeat(40) + "\"}");

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, bad));

        assertEquals(1, warns.size());
        String message = warns.get(0);
        assertTokens(message, "dispatchId=900", "reason=DEBUG_LOG_REPORT_BAD_CHANNEL");
        String logged = message.substring(message.indexOf("channel=") + "channel=".length(),
                message.indexOf(" reason="));
        assertEquals(32, logged.codePointCount(0, logged.length()),
                "裸 substring(0,32) 只会留下 16 个 astral 字符：" + logged);
        assertFalse(Character.isHighSurrogate(logged.charAt(logged.length() - 1)),
                "截断不得以悬空 high surrogate 结尾");
    }

    @Test
    void channelControlCharsAreReplacedBeforeLogging() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);
        // JSON 转义的 \n\r\t：解析后是真实控制字符，入日志前必须逐个替换为 '-'（防日志注入）。
        JSONObject bad = JSON.parseObject(
                "{\"status\":\"UPLOADED\",\"channel\":\"RELAY\\nX\\rY\\tZ\"}");

        List<String> warns = captureAt(Level.WARN,
                () -> service().recordTaskResultReport(100L, 5L, 900L, bad));

        assertEquals(1, warns.size());
        assertTokens(warns.get(0), "dispatchId=900", "channel=RELAY-X-Y-Z",
                "reason=DEBUG_LOG_REPORT_BAD_CHANNEL");
        assertFalse(warns.get(0).contains("\n"), "日志不得携带注入的换行");
        assertFalse(warns.get(0).contains("\r"), "日志不得携带注入的回车");
        assertFalse(warns.get(0).contains("\t"), "日志不得携带注入的制表符");
    }

    // S10 Important#1：sha256 入库前消毒。sha256 是 VARCHAR(80)，脏值会让整条 updateOnResult 抛
    // DataIntegrityViolation，把本已收敛的 status 一起丢（与 upload_channel 白名单同一类风险）。

    @ParameterizedTest(name = "[{index}] reported={0}")
    @MethodSource("sha256Vectors")
    void reportSha256IsSanitizedBeforePersisting(String reported, String expected) {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);

        service().recordTaskResultReport(100L, 5L, 900L, reportWithSha256(reported));

        ArgumentCaptor<String> sha = ArgumentCaptor.forClass(String.class);
        verify(debugLogDao).updateOnResult(eq(12L), eq("UPLOADED"), eq("DIRECT"), eq(123L),
                sha.capture(), eq(false), eq("SUCCEEDED"), isNull());
        assertEquals(expected, sha.getValue());
        // 消毒不得牵动其他字段的收敛：status 照常落库（脏 sha256 不该拖垮主收尾）。
        assertTrue(sha.getValue() == null
                        || sha.getValue().codePointCount(0, sha.getValue().length()) <= 80,
                "入库 sha256 不得超过列宽 80：" + sha.getValue());
    }

    @Test
    void oversizedAstralSha256IsTruncatedByCodePointOnReportPath() {
        when(dispatchDao.findById(900L)).thenReturn(dispatch("SUCCEEDED"));
        stubExistingRow(DebugLogStatus.PENDING);
        // 100 个 astral code point（Java length 200 > 80）：按 code point 截到 80，不切半代理对。
        service().recordTaskResultReport(100L, 5L, 900L,
                reportWithSha256("\uD83E\uDD16".repeat(100)));

        ArgumentCaptor<String> sha = ArgumentCaptor.forClass(String.class);
        verify(debugLogDao).updateOnResult(eq(12L), eq("UPLOADED"), eq("DIRECT"), eq(123L),
                sha.capture(), eq(false), eq("SUCCEEDED"), isNull());
        String value = sha.getValue();
        assertEquals(80, value.codePointCount(0, value.length()),
                "裸 substring(0,80) 只会留下 40 个 astral 字符：" + value);
        assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)),
                "截断不得以悬空 high surrogate 结尾");
    }

    private JSONObject reportWithSha256(String sha256) {
        JSONObject report = new JSONObject();
        report.put("status", "UPLOADED");
        report.put("channel", "DIRECT");
        report.put("sizeBytes", 123);
        report.put("sha256", sha256);
        report.put("truncated", false);
        return report;
    }

    /**
     * sha256 消毒向量，两个消费点共用（TASK_RESULT 报告路径与中转 filesMetadata 路径，
     * {@code DebugLogServiceRelayTest} 以全限定名引用本源）：
     *
     * <ol>
     *   <li>裸 64 位 hex 原样透传，大小写都不归一（与签发端点 {@code SHA256_HEX} 校验后的透传行为一致）。</li>
     *   <li>{@code sha256:} 前缀且剥掉后是裸 hex → 剥前缀（先例 dispatch_recovery_checkpoint.sha256
     *       的 VARCHAR(80) 列注释：可能吸收前缀形态）。</li>
     *   <li>超长 → 按 code point 截到列宽 80；控制字符 → 替换为 '-'（{@code loggedChannel} 同款手法）。</li>
     *   <li>null / 空白 → null（列可空，且 {@code updateOnResult} 对 null 保留既有值）。</li>
     *   <li>带前缀但剥掉后仍非法 → 整体走兜底消毒，不做二次猜测。</li>
     * </ol>
     */
    static Stream<Arguments> sha256Vectors() {
        return Stream.of(
                Arguments.of("a".repeat(64), "a".repeat(64)),
                Arguments.of("A".repeat(64), "A".repeat(64)),
                Arguments.of("sha256:" + "b".repeat(64), "b".repeat(64)),
                Arguments.of("z".repeat(120), "z".repeat(80)),
                Arguments.of("abc\ndef", "abc-def"),
                Arguments.of(null, null),
                Arguments.of("   ", null),
                Arguments.of("sha256:zzz", "sha256:zzz"));
    }

    private static void assertTokens(String message, String... tokens) {
        for (String token : tokens) {
            assertTrue(message.contains(token), () -> "日志缺少 " + token + "：" + message);
        }
    }

    /** 仿 {@code DebugLogServiceIssueTest} 的 log4j2 捕获器：收 DebugLogService 指定级别及以上的日志，返回恰为该级别的格式化消息。 */
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
            super("debuglog-result-report-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
