package com.aliyun.autowonder.ai;

import com.aliyun.autowonder.ai.adapter.SceneAdapter;
import com.aliyun.autowonder.ai.adapter.SceneRegistry;
import com.aliyun.autowonder.ai.dto.ConfirmResultRequest;
import com.aliyun.autowonder.ai.dto.CreateSessionRequest;
import com.aliyun.autowonder.aiusage.AiUsageService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiSessionServiceTest {

    private AiSessionDao sessionDao;
    private AiMessageDao messageDao;
    private SceneRegistry sceneRegistry;
    private RedisManager redisManager;
    private AiUsageService aiUsageService;
    private RepoDao repoDao;
    private com.aliyun.autowonder.workspace.WorkspaceMemberDao workspaceMemberDao;
    private AiSessionService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        sessionDao = mock(AiSessionDao.class);
        messageDao = mock(AiMessageDao.class);
        sceneRegistry = mock(SceneRegistry.class);
        redisManager = mock(RedisManager.class);
        aiUsageService = mock(AiUsageService.class);
        repoDao = mock(RepoDao.class);
        workspaceMemberDao = mock(com.aliyun.autowonder.workspace.WorkspaceMemberDao.class);
        service = new AiSessionService(sessionDao, messageDao, sceneRegistry,
                redisManager, aiUsageService, repoDao,
                new com.aliyun.autowonder.access.WorkspaceDomainGuard(workspaceMemberDao));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void flushAfterCommit() {
        List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
        for (TransactionSynchronization sync : syncs) {
            sync.afterCommit();
        }
    }

    @Test
    void createSessionInsertsAndEnqueues() {
        doAnswer(inv -> { ((AiSessionDO) inv.getArgument(0)).setId(1L); return null; })
                .when(sessionDao).insert(any(AiSessionDO.class));
        SceneAdapter adapter = mock(SceneAdapter.class);
        when(adapter.scene()).thenReturn(AiConstants.Scene.CLARIFICATION);
        when(sceneRegistry.get(AiConstants.Scene.CLARIFICATION)).thenReturn(adapter);

        CreateSessionRequest req = new CreateSessionRequest();
        req.setScene(AiConstants.Scene.CLARIFICATION);
        req.setBizRefType("WORKITEM");
        req.setBizRefId(10L);
        req.setInput("请帮我澄清需求");

        Long id = service.create(req, 100L, 1L);
        flushAfterCommit();

        assertNotNull(id);
        verify(sessionDao).insert(any(AiSessionDO.class));
        verify(messageDao).insert(any(AiMessageDO.class));
        verify(redisManager).lpush(eq("ai:queue:global"), anyString());
    }

    @Test
    void createRepoScanSessionMarksRepoScanning() {
        doAnswer(inv -> { ((AiSessionDO) inv.getArgument(0)).setId(2L); return null; })
                .when(sessionDao).insert(any(AiSessionDO.class));
        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.REPO_SCAN)).thenReturn(adapter);
        RepoDO repo = new RepoDO();
        repo.setId(10L);
        repo.setTenantId(100L);
        repo.setScanStatus("UNSCANNED");
        repo.setVersion(3);
        when(repoDao.findById(10L)).thenReturn(repo);
        when(repoDao.updateScanStatus(10L, 100L, "SCANNING", 3, 1L)).thenReturn(1);

        CreateSessionRequest req = new CreateSessionRequest();
        req.setScene(AiConstants.Scene.REPO_SCAN);
        req.setBizRefType("REPO");
        req.setBizRefId(10L);
        req.setInput("扫描仓库");

        Long id = service.create(req, 100L, 1L);
        flushAfterCommit();

        assertEquals(2L, id);
        verify(repoDao).updateScanStatus(10L, 100L, "SCANNING", 3, 1L);
        verify(messageDao, never()).insert(any(AiMessageDO.class));
        verify(redisManager).lpush(eq("ai:queue:global"), eq("2"));
    }

    @Test
    void createRepoScanSessionIsBlockedBeforeAnyWriteWhenOperatorForbidsRepoDomain() {
        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.REPO_SCAN)).thenReturn(adapter);
        com.aliyun.autowonder.workspace.WorkspaceMemberDO member =
                new com.aliyun.autowonder.workspace.WorkspaceMemberDO();
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel("READ_WRITE");
        member.setReadOnlyDomains("[\"REPO\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 1L)).thenReturn(member);

        CreateSessionRequest req = new CreateSessionRequest();
        req.setScene(AiConstants.Scene.REPO_SCAN);
        req.setBizRefType("REPO");
        req.setBizRefId(10L);

        assertThrows(com.aliyun.autowonder.access.WorkspaceDomainReadOnlyException.class,
                () -> service.create(req, 100L, 1L));

        // 仓库、会话、队列均零写入。
        verify(repoDao, never()).findById(anyLong());
        verify(repoDao, never()).updateScanStatus(anyLong(), anyLong(), any(), any(), any());
        verify(sessionDao, never()).insert(any(AiSessionDO.class));
        verify(redisManager, never()).lpush(any(), any());

        // 解除限制后同一请求正常创建。
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 1L)).thenReturn(null);
        doAnswer(inv -> { ((AiSessionDO) inv.getArgument(0)).setId(3L); return null; })
                .when(sessionDao).insert(any(AiSessionDO.class));
        RepoDO repo = new RepoDO();
        repo.setId(10L);
        repo.setTenantId(100L);
        repo.setVersion(3);
        when(repoDao.findById(10L)).thenReturn(repo);
        when(repoDao.updateScanStatus(10L, 100L, "SCANNING", 3, 1L)).thenReturn(1);

        Long id = service.create(req, 100L, 1L);
        flushAfterCommit();

        assertEquals(3L, id);
        verify(sessionDao).insert(any(AiSessionDO.class));
        verify(redisManager).lpush(eq("ai:queue:global"), anyString());
    }

    @Test
    void createClarificationSessionMayReadReadOnlyRepoAndWorkitemDomains() {
        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.CLARIFICATION)).thenReturn(adapter);
        com.aliyun.autowonder.workspace.WorkspaceMemberDO member = new com.aliyun.autowonder.workspace.WorkspaceMemberDO();
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel("READ_WRITE");
        member.setReadOnlyDomains("[\"REPO\",\"WORKITEM\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 1L)).thenReturn(member);
        doAnswer(inv -> { ((AiSessionDO) inv.getArgument(0)).setId(4L); return null; }).when(sessionDao).insert(any(AiSessionDO.class));
        CreateSessionRequest req = new CreateSessionRequest();
        req.setScene(AiConstants.Scene.CLARIFICATION);
        req.setBizRefType("WORKITEM");
        req.setBizRefId(10L);

        assertEquals(4L, service.create(req, 100L, 1L));
        flushAfterCommit();
        verify(sessionDao).insert(any(AiSessionDO.class));
        verify(redisManager).lpush(eq("ai:queue:global"), anyString());
    }

    @Test
    void createSessionRejectsUnknownScene() {
        when(sceneRegistry.get("UNKNOWN")).thenReturn(null);

        CreateSessionRequest req = new CreateSessionRequest();
        req.setScene("UNKNOWN");

        BizException ex = assertThrows(BizException.class,
                () -> service.create(req, 100L, 1L));
        assertEquals(ErrorCode.AI_SCENE_NOT_SUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void confirmCallsAdapterPersist() {
        AiSessionDO session = new AiSessionDO();
        session.setId(1L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.CLARIFICATION);
        session.setStatus(AiConstants.Status.WAIT_USER);
        session.setVersion(2);
        when(sessionDao.findById(1L)).thenReturn(session);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.CLARIFICATION)).thenReturn(adapter);
        when(adapter.validateResult("{\"ok\":true}")).thenReturn(null);
        when(sessionDao.updateStatus(1L, 100L, AiConstants.Status.WAIT_USER,
                AiConstants.Status.COMPLETED, 2)).thenReturn(1);

        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setResultJson("{\"ok\":true}");

        service.confirm(1L, req, 100L);

        verify(adapter).persistConfirmedResult(session, "{\"ok\":true}");
        verify(sessionDao).updateStatus(1L, 100L, AiConstants.Status.WAIT_USER,
                AiConstants.Status.COMPLETED, 2);
    }

    @Test
    void confirmRejectsNonWaitUserSession() {
        AiSessionDO session = new AiSessionDO();
        session.setId(1L);
        session.setTenantId(100L);
        session.setStatus(AiConstants.Status.RUNNING);
        when(sessionDao.findById(1L)).thenReturn(session);

        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setResultJson("{}");

        BizException ex = assertThrows(BizException.class,
                () -> service.confirm(1L, req, 100L));
        assertEquals(ErrorCode.AI_SESSION_NOT_WAIT_USER.getCode(), ex.getCode());
    }

    @Test
    void cancelSessionUpdatesStatus() {
        AiSessionDO session = new AiSessionDO();
        session.setId(1L);
        session.setTenantId(100L);
        session.setStatus(AiConstants.Status.QUEUED);
        session.setVersion(0);
        when(sessionDao.findById(1L)).thenReturn(session);
        when(sessionDao.updateStatus(1L, 100L, AiConstants.Status.QUEUED,
                AiConstants.Status.CANCELED, 0)).thenReturn(1);

        service.cancel(1L, 100L);

        verify(sessionDao).updateStatus(1L, 100L, AiConstants.Status.QUEUED,
                AiConstants.Status.CANCELED, 0);
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ownedValidationCases() {
        var agent = new com.aliyun.autowonder.ai.adapter.AgentConfigGenAdapter();
        var memory = new com.aliyun.autowonder.ai.adapter.MemoryImportAdapter(null, null);
        var sdlc = new com.aliyun.autowonder.ai.adapter.SdlcGenAdapter(null, null, null);
        var repo = new com.aliyun.autowonder.ai.adapter.RepoScanAdapter(null, null, null);
        var clarification = new com.aliyun.autowonder.ai.adapter.ClarificationAdapter(null, null, null, null, null, null);
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(agent, "null", "null result", "结果为空", "The result is null"),
            org.junit.jupiter.params.provider.Arguments.of(agent, "{\"name\":7}", "name must be a string", "name 必须是字符串", "name must be a string"),
            org.junit.jupiter.params.provider.Arguments.of(agent, "{\"recommendations\":{\"skills\":7}}", "recommendations.skills must be an array", "recommendations.skills 必须是数组", "recommendations.skills must be an array"),
            org.junit.jupiter.params.provider.Arguments.of(memory, "null", "null result", "结果为空", "The result is null"),
            org.junit.jupiter.params.provider.Arguments.of(memory, "{}", "items array is empty", "items 数组为空", "The items array is empty"),
            org.junit.jupiter.params.provider.Arguments.of(memory, "{\"items\":[{}]}", "item[0] missing title", "item[0] 缺少 title", "item[0] is missing title"),
            org.junit.jupiter.params.provider.Arguments.of(sdlc, "null", "null result", "结果为空", "The result is null"),
            org.junit.jupiter.params.provider.Arguments.of(sdlc, "{}", "steps array is empty", "steps 数组为空", "The steps array is empty"),
            org.junit.jupiter.params.provider.Arguments.of(sdlc, "{\"steps\":[{}]}", "step 0 missing name or instructionMd", "步骤 0 缺少 name 或 instructionMd", "Step 0 is missing name or instructionMd"),
            org.junit.jupiter.params.provider.Arguments.of(repo, "{}", "missing or blank purpose field", "purpose 字段缺失或为空", "The purpose field is missing or blank"),
            org.junit.jupiter.params.provider.Arguments.of(repo, "{\"purpose\":\"<b>raw</b>\"}", "missing summaryMd field", "缺少 summaryMd 字段", "The summaryMd field is missing"),
            org.junit.jupiter.params.provider.Arguments.of(clarification, "{}", "missing clarificationMd field", "缺少 clarificationMd 字段", "The clarificationMd field is missing"),
            org.junit.jupiter.params.provider.Arguments.of(agent, "not-json", "invalid JSON: Unable to parse JSON", "JSON 无效：Unable to parse JSON", "Invalid JSON: Unable to parse JSON"),
            org.junit.jupiter.params.provider.Arguments.of(memory, "not-json", "invalid JSON: Unable to parse JSON", "JSON 无效：Unable to parse JSON", "Invalid JSON: Unable to parse JSON"),
            org.junit.jupiter.params.provider.Arguments.of(sdlc, "not-json", "invalid JSON: Unable to parse JSON", "JSON 无效：Unable to parse JSON", "Invalid JSON: Unable to parse JSON"),
            org.junit.jupiter.params.provider.Arguments.of(repo, "not-json", "invalid JSON: Unable to parse JSON", "JSON 无效：Unable to parse JSON", "Invalid JSON: Unable to parse JSON"),
            org.junit.jupiter.params.provider.Arguments.of(clarification, "not-json", "invalid JSON: Unable to parse JSON", "JSON 无效：Unable to parse JSON", "Invalid JSON: Unable to parse JSON")
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("ownedValidationCases")
    void confirmationProjectsOwnedValidationWithoutChangingLegacyDiagnostics(
            SceneAdapter adapter, String input, String raw, String zh, String en) {
        assertEquals(raw, adapter.validateResult(input));
        AiSessionDO session = new AiSessionDO();
        session.setId(1L); session.setTenantId(100L); session.setScene(adapter.scene());
        session.setStatus(AiConstants.Status.WAIT_USER); session.setVersion(2);
        when(sessionDao.findById(1L)).thenReturn(session);
        when(sceneRegistry.get(adapter.scene())).thenReturn(adapter);
        ConfirmResultRequest request = new ConfirmResultRequest(); request.setResultJson(input);
        BizException error = assertThrows(BizException.class, () -> service.confirm(1L, request, 100L));
        assertEquals(BizException.class, error.getClass());
        assertEquals(ErrorCode.AI_CONFIRM_VALIDATION_FAILED.getCode(), error.getCode());
        assertEquals(raw, error.getMessage()); assertEquals(raw, error.getLocalizedMessage()); assertNull(error.getCause());
        assertEquals(input, request.getResultJson());
        verify(sessionDao, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString(), anyInt());
        for (var locale : List.of(java.util.Locale.CHINA, java.util.Locale.US)) {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
            try {
                var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error);
                assertEquals(200, response.getStatusCodeValue());
                assertEquals(locale.equals(java.util.Locale.US) ? en : zh, response.getBody().getMessage());
            } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
        }
    }

    @Test
    void unknownAdapterSameLookingValidationStaysRaw() {
        SceneAdapter adapter = new SceneAdapter() {
            public String scene() { return "CUSTOM"; }
            public String buildSystemPrompt(AiSessionDO s) { return ""; }
            public String buildUserPrompt(AiSessionDO s, String input) { return input; }
            public String validateResult(String input) { return "null result"; }
            public void persistConfirmedResult(AiSessionDO s, String input) { fail("must not persist"); }
        };
        AiSessionDO session = new AiSessionDO(); session.setId(1L); session.setTenantId(100L);
        session.setScene("CUSTOM"); session.setStatus(AiConstants.Status.WAIT_USER);
        when(sessionDao.findById(1L)).thenReturn(session); when(sceneRegistry.get("CUSTOM")).thenReturn(adapter);
        var req = new ConfirmResultRequest(); req.setResultJson("null");
        BizException error = assertThrows(BizException.class, () -> service.confirm(1L, req, 100L));
        assertNull(error.getMessageKey());
        assertEquals("null result", com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.US));
    }

}
