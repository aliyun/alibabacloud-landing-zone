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
        service = new AiSessionService(sessionDao, messageDao, sceneRegistry,
                redisManager, aiUsageService, repoDao);
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
}
