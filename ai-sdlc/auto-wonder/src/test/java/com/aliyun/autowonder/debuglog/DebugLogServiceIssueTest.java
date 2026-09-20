package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.capability;
import static com.aliyun.autowonder.debuglog.DebugLogServiceEnablementTest.namer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogServiceIssueTest {

    private DebugLogDao debugLogDao;
    private DispatchDao dispatchDao;
    private AgentVersionDao agentVersionDao;
    private ScheduledTaskRunDao scheduledTaskRunDao;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        debugLogDao = mock(DebugLogDao.class);
        dispatchDao = mock(DispatchDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        scheduledTaskRunDao = mock(ScheduledTaskRunDao.class);
        storage = mock(ObjectStorage.class);
        // 默认走「刷新命中一行」的正常路径；rows==0 的场景由专门用例覆写为 0。
        when(debugLogDao.updateOnIssue(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
    }

    private DebugLogService service() {
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        return new DebugLogService(debugLogDao, dispatchDao, mock(SquadDao.class),
                namer(dispatchDao, agentVersionDao, scheduledTaskRunDao), storage, props,
                capability(V037MapperMode.SOURCE_AWARE));
    }

    private DispatchDO workitemDispatch(long id, long agentId, Date gmtCreate) {
        DispatchDO d = new DispatchDO();
        d.setId(id);
        d.setTenantId(100L);
        d.setSourceType("WORKITEM");
        d.setWorkitemId(200L);
        d.setAgentId(agentId);
        d.setAgentVersionId(410L);
        d.setExecutorId(5L);
        d.setStatus(DispatchStatus.SUCCEEDED);
        d.setDebugLogEnabled(true);
        d.setGmtCreate(gmtCreate);
        return d;
    }

    private DispatchDO self() {
        return workitemDispatch(900L, 400L, new Date(1_000L));
    }

    private void stubRoleCode(String roleCode) {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(410L);
        version.setTenantId(100L);
        version.setRoleCode(roleCode);
        when(agentVersionDao.findById(410L)).thenReturn(version);
    }

    @Test
    void issueComputesRunNoFromAgentScopedCreationOrderAndPresignsCanonicalKey() {
        DispatchDO self = self();
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(
                workitemDispatch(800L, 400L, new Date(900L)),
                workitemDispatch(850L, 500L, new Date(950L)),
                self));
        stubRoleCode("DevAgent");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut("test-artifact-bucket", "debug/200/DevAgent-run-2.log.gz",
                Duration.ofMinutes(20))).thenReturn("https://oss/put?sig=1");

        DebugLogService.IssueResult result = service().issueUpload(self, 123L,
                "a".repeat(64), false, "SUCCEEDED");

        assertEquals("debug/200/DevAgent-run-2.log.gz", result.objectKey());
        assertEquals("https://oss/put?sig=1", result.uploadUrl());
        assertFalse(result.alreadyUploaded());
        assertNotNull(result.expiresAt());
        ArgumentCaptor<DebugLogDO> cap = ArgumentCaptor.forClass(DebugLogDO.class);
        verify(debugLogDao).insert(cap.capture());
        DebugLogDO row = cap.getValue();
        assertEquals(100L, row.getTenantId());
        assertEquals("WORKITEM", row.getSourceType());
        assertEquals(200L, row.getSourceId());
        assertEquals(900L, row.getDispatchId());
        assertEquals(400L, row.getAgentId());
        assertEquals(410L, row.getAgentVersionId());
        assertEquals(2, row.getRunNo());
        assertEquals("SUCCEEDED", row.getDispatchStatus());
        assertEquals("debug/200/DevAgent-run-2.log.gz", row.getObjectKey());
        assertEquals(123L, row.getSizeBytes());
        assertEquals("a".repeat(64), row.getSha256());
        assertEquals(false, row.getTruncated());
        assertEquals(DebugLogStatus.PENDING, row.getStatus());
        assertNull(row.getUploadChannel());
    }

    @Test
    void issueReturnsExistingKeyWithoutPresignWhenAlreadyUploaded() {
        DebugLogDO uploaded = new DebugLogDO();
        uploaded.setId(11L);
        uploaded.setDispatchId(900L);
        uploaded.setStatus(DebugLogStatus.UPLOADED);
        uploaded.setObjectKey("debug/200/DevAgent-run-1.log.gz");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(uploaded);

        DebugLogService.IssueResult result = service().issueUpload(self(), 123L, null, false,
                "SUCCEEDED");

        assertEquals("debug/200/DevAgent-run-1.log.gz", result.objectKey());
        assertNull(result.uploadUrl());
        assertNull(result.expiresAt());
        assertTrue(result.alreadyUploaded());
        verifyNoInteractions(storage);
        verify(debugLogDao, never()).insert(any());
        verify(debugLogDao, never()).updateOnIssue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void issueRefreshesPendingRowInsteadOfInserting() {
        DispatchDO self = self();
        DebugLogDO pending = new DebugLogDO();
        pending.setId(12L);
        pending.setDispatchId(900L);
        pending.setStatus(DebugLogStatus.PENDING);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(pending);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode("DevAgent");
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put?sig=2");

        DebugLogService.IssueResult result = service().issueUpload(self, 456L, null, true, "FAILED");

        assertFalse(result.alreadyUploaded());
        assertEquals("debug/200/DevAgent-run-1.log.gz", result.objectKey());
        verify(debugLogDao).updateOnIssue(12L, 1, "debug/200/DevAgent-run-1.log.gz", 456L, null,
                true, "FAILED");
        verify(debugLogDao, never()).insert(any());
    }

    @Test
    void insertRaceFallsBackToUpdateOnWinnerRow() {
        DispatchDO self = self();
        DebugLogDO winner = new DebugLogDO();
        winner.setId(13L);
        winner.setDispatchId(900L);
        winner.setStatus(DebugLogStatus.PENDING);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null, winner);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode("DevAgent");
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_dispatch"))
                .when(debugLogDao).insert(any(DebugLogDO.class));
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put?sig=3");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, 1L, null, false, "SUCCEEDED")));

        assertFalse(result.get().alreadyUploaded());
        verify(debugLogDao).updateOnIssue(13L, 1, "debug/200/DevAgent-run-1.log.gz", 1L, null,
                false, "SUCCEEDED");
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=DEBUG_LOG_INSERT_RACE",
                "winnerId=13");
    }

    @Test
    void insertRaceWithoutReadableWinnerRowPropagates() {
        DispatchDO self = self();
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        stubRoleCode("DevAgent");
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_dispatch"))
                .when(debugLogDao).insert(any(DebugLogDO.class));

        // 竞态兜底读不到 winner 行时不得静默吞掉：签发失败要让调用方（Task 7 端点）看得见。
        assertThrows(DuplicateKeyException.class,
                () -> service().issueUpload(self, 1L, null, false, "SUCCEEDED"));
        verifyNoInteractions(storage);
    }

    @Test
    void scheduledRunKeysEmbedTaskAndRunIds() {
        DispatchDO self = self();
        self.setSourceType("SCHEDULED_TASK_RUN");
        self.setWorkitemId(77L);
        when(dispatchDao.listBySource(100L, "SCHEDULED_TASK_RUN", 77L)).thenReturn(List.of(self));
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(77L);
        run.setScheduledTaskId(12L);
        when(scheduledTaskRunDao.findById(100L, 77L)).thenReturn(run);
        stubRoleCode("QA");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put?sig=4");

        DebugLogService.IssueResult result = service().issueUpload(self, 1L, null, false,
                "CANCELED");

        assertEquals("debug/scheduled-12-run-77/QA-run-1.log.gz", result.objectKey());
    }

    @Test
    void missingRoleCodeFallsBackToAgentIdInKey() {
        DispatchDO self = self();
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        when(agentVersionDao.findById(410L)).thenReturn(null);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/200/agent-400-run-1.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=AGENT_VERSION_NOT_FOUND");
    }

    @Test
    void runNoSortsSiblingsByCreationTimeThenId() {
        DispatchDO self = self();
        DispatchDO sameMillisEarlierId = workitemDispatch(899L, 400L, new Date(1_000L));
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L))
                .thenReturn(List.of(self, sameMillisEarlierId));
        stubRoleCode("DevAgent");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        DebugLogService.IssueResult result = service().issueUpload(self, null, null, false,
                "SUCCEEDED");

        assertEquals("debug/200/DevAgent-run-2.log.gz", result.objectKey());
    }

    @ParameterizedTest(name = "[{index}] roleCode={0}")
    @MethodSource("roleCodeVectors")
    void sanitizeRoleCodeNormalizesToKeySafeCharset(String roleCode, String expected) {
        assertEquals(expected, DebugLogObjectNamer.sanitizeRoleCode(roleCode, 400L));
    }

    /**
     * roleCode 归一化向量（规则现由 {@link DebugLogObjectNamer#sanitizeRoleCode} 承载，S10 抽出，
     * 语义与向量逐字未变）。服务端 objectKey 侧的对照实现是 runtime 的
     * {@code runtime/roundlog/naming.go}（SanitizeRoleCode + sanitizeSegment）：两侧共享
     * 字符集 {@code [A-Za-z0-9_-]} 与 64 rune 上限，其余为设计文档 §5.3 记录的已知分歧：
     *
     * <ol>
     *   <li>空白判定——Java 用 {@code String.strip()}/{@code isBlank()}（{@code Character.isWhitespace}），
     *       NBSP(U+00A0) 不算空白，故被替换成 '-'；Go 用 {@code strings.TrimSpace}
     *       （{@code unicode.IsSpace}），NBSP 被修剪掉。U+3000 两侧一致视为空白。</li>
     *   <li>astral 粒度——Java 保留既有 UTF-16 语义，emoji 产出两个 '-'（objectKey 只要求字符集归一，
     *       双 dash 无害，且改动会扰动已登记 key 的稳定性）；Go 按 rune 产出一个 '-'。</li>
     *   <li>空回退——服务端 {@code agent-{agentId}}（objectKey 权威且需自解释），runtime 本地文件名
     *       {@code agent}（agentId 由 LogFileName 另起一段，避免 agent-77-77 重复）。</li>
     * </ol>
     */
    static Stream<Arguments> roleCodeVectors() {
        return Stream.of(
                Arguments.of("Dev Agent", "Dev-Agent"),
                Arguments.of("Dev/Agent", "Dev-Agent"),
                Arguments.of("QA_1-x", "QA_1-x"),
                Arguments.of("  Dev  ", "Dev"),
                Arguments.of("\u3000Dev\u3000", "Dev"),
                Arguments.of(null, "agent-400"),
                Arguments.of("   ", "agent-400"),
                Arguments.of("\u3000\u3000", "agent-400"),
                Arguments.of("\u00A0Dev\u00A0", "-Dev-"),
                Arguments.of("中文", "--"),
                Arguments.of("\uD83E\uDD16", "--"),
                Arguments.of("Dev\uD83E\uDD16x", "Dev--x"),
                Arguments.of("a".repeat(90), "a".repeat(64)),
                Arguments.of("b".repeat(63) + "中" + "tail", "b".repeat(63) + "-"),
                Arguments.of("c".repeat(64) + "\uD83E\uDD16", "c".repeat(64)));
    }

    @Test
    void refreshUpdateMatchingNoRowWarnsButStillPresigns() {
        DispatchDO self = self();
        DebugLogDO pending = new DebugLogDO();
        pending.setId(12L);
        pending.setDispatchId(900L);
        pending.setStatus(DebugLogStatus.PENDING);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(pending);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode("DevAgent");
        // status <> 'UPLOADED' 守卫落空：并发窗口里另一条链路已把行标成 UPLOADED。
        when(debugLogDao.updateOnIssue(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put?sig=6");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, 1L, null, false, "SUCCEEDED")));

        assertEquals("debug/200/DevAgent-run-1.log.gz", result.get().objectKey());
        assertFalse(result.get().alreadyUploaded());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=DEBUG_LOG_ISSUE_UPDATE_NO_ROW");
    }

    @Test
    void runNoFallsBackToSiblingCountAndWarnsWhenSelfIsNotListed() {
        DispatchDO self = self();
        // 含 null 元素：computeRunNo 必须跳过（mapper 返回稀疏列表时不得 NPE）。
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(Arrays.asList(
                workitemDispatch(800L, 400L, new Date(900L)),
                null,
                workitemDispatch(850L, 400L, new Date(950L))));
        stubRoleCode("DevAgent");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/200/DevAgent-run-3.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=RUN_NO_SELF_NOT_IN_SIBLINGS");
    }

    @Test
    void scheduledRunWarnsWhenRunRowIsMissing() {
        DispatchDO self = scheduledSelf();
        when(dispatchDao.listBySource(100L, "SCHEDULED_TASK_RUN", 77L)).thenReturn(List.of(self));
        when(scheduledTaskRunDao.findById(100L, 77L)).thenReturn(null);
        stubRoleCode("QA");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/scheduled-0-run-77/QA-run-1.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=SCHEDULED_TASK_ID_MISSING");
    }

    @Test
    void scheduledRunWarnsWhenTaskIdColumnIsNull() {
        DispatchDO self = scheduledSelf();
        when(dispatchDao.listBySource(100L, "SCHEDULED_TASK_RUN", 77L)).thenReturn(List.of(self));
        ScheduledTaskRunDO orphan = new ScheduledTaskRunDO();
        orphan.setId(77L);
        orphan.setScheduledTaskId(null);
        when(scheduledTaskRunDao.findById(100L, 77L)).thenReturn(orphan);
        stubRoleCode("QA");
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/scheduled-0-run-77/QA-run-1.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=SCHEDULED_TASK_ID_MISSING");
    }

    @Test
    void roleCodeTenantMismatchFallsBackToAgentIdAndWarns() {
        DispatchDO self = self();
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        AgentVersionDO foreign = new AgentVersionDO();
        foreign.setId(410L);
        foreign.setTenantId(999L);
        foreign.setRoleCode("DevAgent");
        when(agentVersionDao.findById(410L)).thenReturn(foreign);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/200/agent-400-run-1.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=ROLE_CODE_TENANT_MISMATCH");
    }

    @ParameterizedTest(name = "[{index}] roleCode={0}")
    @NullSource
    @ValueSource(strings = {"", "   ", "\u3000"})
    void blankRoleCodeFallsBackToAgentIdAndWarns(String roleCode) {
        DispatchDO self = self();
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        stubRoleCode(roleCode);
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/200/agent-400-run-1.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=ROLE_CODE_BLANK");
    }

    @Test
    void missingAgentVersionIdFallsBackToAgentIdAndWarns() {
        DispatchDO self = self();
        self.setAgentVersionId(null);
        when(dispatchDao.listBySource(100L, "WORKITEM", 200L)).thenReturn(List.of(self));
        when(debugLogDao.findByDispatchId(900L)).thenReturn(null);
        when(storage.presignPut(anyString(), anyString(), any())).thenReturn("https://oss/put");

        AtomicReference<DebugLogService.IssueResult> result = new AtomicReference<>();
        List<String> warns = captureWarns(() -> result.set(
                service().issueUpload(self, null, null, false, "SUCCEEDED")));

        assertEquals("debug/200/agent-400-run-1.log.gz", result.get().objectKey());
        assertEquals(1, warns.size());
        assertWarn(warns.get(0), "dispatchId=900", "reason=AGENT_VERSION_ID_MISSING");
        verifyNoInteractions(agentVersionDao);
    }

    private DispatchDO scheduledSelf() {
        DispatchDO self = self();
        self.setSourceType("SCHEDULED_TASK_RUN");
        self.setWorkitemId(77L);
        return self;
    }

    private static void assertWarn(String message, String... tokens) {
        for (String token : tokens) {
            assertTrue(message.contains(token), () -> "warn 缺少 " + token + "：" + message);
        }
    }

    /**
     * 仿 {@code ImServiceErrorLoggingTest} 的 log4j2 捕获器：收 debug 日志链路的 WARN。
     * naming/key 集群抽到 {@link DebugLogObjectNamer} 后，run_no/objectKey/roleCode 的回退告警由
     * Namer 自己的 logger 发出，故同一个 appender 挂到两个 logger 上——否则「恰 1 条 warn」的断言
     * 会退化成静默通过。
     */
    private static List<String> captureWarns(Runnable action) {
        List<Logger> loggers = List.of((Logger) LogManager.getLogger(DebugLogService.class),
                (Logger) LogManager.getLogger(DebugLogObjectNamer.class));
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        List<Level> previousLevels = new ArrayList<>();
        for (Logger logger : loggers) {
            previousLevels.add(logger.getLevel());
            logger.addAppender(appender);
            logger.setLevel(Level.WARN);
        }
        try {
            action.run();
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeAppender(appender);
                loggers.get(i).setLevel(previousLevels.get(i));
            }
            appender.stop();
        }
        return appender.events.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("debuglog-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
