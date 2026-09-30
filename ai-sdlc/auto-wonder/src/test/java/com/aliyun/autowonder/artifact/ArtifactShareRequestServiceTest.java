package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Date;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtifactShareRequestServiceTest {
    private final ArtifactShareRequestDao requests = mock(ArtifactShareRequestDao.class);
    private final ArtifactDao artifacts = mock(ArtifactDao.class);
    private final DispatchDao dispatches = mock(DispatchDao.class);
    private final WorkitemDao workitems = mock(WorkitemDao.class);
    private final ExternalArtifactShareService shares = mock(ExternalArtifactShareService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ArtifactShareRequestService service = new ArtifactShareRequestService(
            requests, artifacts, dispatches, workitems, shares, transactions);
    private final String digest = "a".repeat(64);
    private DispatchDO dispatch;
    private ArtifactShareRequestDO request;

    @BeforeEach
    void setup() {
        dispatch = new DispatchDO();
        dispatch.setId(6L); dispatch.setTenantId(100L); dispatch.setWorkitemId(99L);
        dispatch.setAgentId(7L); dispatch.setSourceType("WORKITEM"); dispatch.setStatus("RUNNING");
        when(dispatches.findById(6L)).thenReturn(dispatch);
        WorkitemDO workitem = new WorkitemDO(); workitem.setId(99L); workitem.setTenantId(100L);
        when(workitems.findById(99L)).thenReturn(workitem);
        request = new ArtifactShareRequestDO();
        request.setId(1L); request.setTenantId(100L); request.setWorkitemId(99L); request.setDispatchId(6L);
        request.setName("report.md"); request.setSha256(digest); request.setStatus("PENDING");
        when(requests.find(100L, 6L, "report.md")).thenReturn(request);
        when(requests.lock(1L)).thenReturn(request);
        when(requests.listPending(any())).thenReturn(List.of(1L));
        when(shares.expose(100L, 99L, 6L, null, "report.md", digest))
                .thenReturn(Map.of("artifactId", 301L, "artifactUrl", "https://example.com/share/301"));
        when(shares.requestUrl(any(), eq(1L))).thenReturn("https://example.com/share/requests/1");
    }

    @Test
    void preUploadRequestReturnsTheSameUrlBeforeAndAfterPublication() {
        Map<String, Object> result = service.request(100L, 99L, 6L, null, "artifacts/output/report.md", digest.toUpperCase());
        assertEquals("PENDING", result.get("status"));
        assertEquals("https://example.com/share/requests/1", result.get("artifactUrl"));
        verify(requests).insert(argThat(r -> r.getName().equals("report.md") && r.getSha256().equals(digest)));
        service.processPending(null);
        verify(requests).retryLater(1L);
        verify(shares, never()).expose(anyLong(), anyLong(), any(), any(), any(), any());
        when(shares.expose(100L, 99L, 6L, 301L, null, digest)).thenReturn(Map.of("artifactId", 301L));
        dispatch.setStatus("SUCCEEDED");
        Map<String, Object> completed = service.request(100L, 99L, 6L, null, "report.md", digest);
        assertEquals("SHARED", completed.get("status"));
        assertEquals(result.get("artifactUrl"), completed.get("artifactUrl"));
        assertFalse(completed.containsKey("commentId"));
    }

    @Test
    void repeatedSuccessEventsOnlyPublishTheSnapshot() {
        dispatch.setStatus("SUCCEEDED");
        service.processPending(6L);
        service.processPending(6L);
        service.processPending(null);
        assertEquals("SHARED", request.getStatus());
        assertEquals(301L, request.getArtifactId());
        assertNull(request.getCommentId());
        verify(shares).expose(100L, 99L, 6L, null, "report.md", digest);
        verify(requests).finish(request);
    }

    @Test
    void duplicateDeclarationCannotChangeDigest() {
        assertThrows(BizException.class, () -> service.request(100L, 99L, 6L, null, "report.md", "b".repeat(64)));
        verifyNoInteractions(shares);
    }

    @Test
    void failsClosedForOtherTenantWorkitemSourceAndInvalidInput() {
        assertThrows(BizException.class, () -> service.request(101L, 99L, 6L, null, "report.md", digest));
        assertThrows(BizException.class, () -> service.request(100L, 98L, 6L, null, "report.md", digest));
        for (String path : List.of("../secret", "/secret", "output/../secret", "a/./b", "a//b", "a\nb")) {
            assertThrows(BizException.class, () -> service.request(100L, 99L, 6L, null, path, digest));
        }
        assertThrows(BizException.class, () -> service.request(100L, 99L, 6L, null, "report.md", null));
        dispatch.setSourceType("SCHEDULED_TASK_RUN");
        assertThrows(BizException.class, () -> service.request(100L, 99L, 6L, null, "report.md", digest));
        verify(requests, never()).insert(any());
    }

    @Test
    void artifactIdMustBelongToTheSameDispatch() {
        ArtifactDO artifact = new ArtifactDO(); artifact.setWorkitemId(99L); artifact.setDispatchId(5L);
        when(artifacts.findWorkitemByTenantAndId(100L, 301L)).thenReturn(artifact);
        assertThrows(BizException.class, () -> service.request(100L, 99L, 6L, 301L, null, digest));
        verify(requests, never()).insert(any());
    }

    @Test
    void cancellationAndDigestMismatchNeverCreateComments() {
        dispatch.setStatus("CANCELED"); service.process(1L);
        assertEquals("REJECTED", request.getStatus());
        verifyNoInteractions(shares);
        request.setStatus("PENDING"); dispatch.setStatus("SUCCEEDED");
        when(shares.expose(100L, 99L, 6L, null, "report.md", digest))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID));
        service.process(1L);
        assertEquals("CONTENT_DIGEST_MISMATCH", request.getError());
    }

    @Test
    void missingArtifactWaitsForLateUploadThenRejectsWithoutHistoryFallback() {
        dispatch.setStatus("SUCCEEDED"); dispatch.setGmtModified(new Date());
        when(shares.expose(100L, 99L, 6L, null, "report.md", digest))
                .thenThrow(new BizException(ErrorCode.ARTIFACT_NOT_FOUND));
        service.process(1L); assertEquals("PENDING", request.getStatus());
        dispatch.setGmtModified(new Date(0));
        service.process(1L); assertEquals("REJECTED", request.getStatus());
    }

    @Test
    void snapshotFailureRollsBackAndLeavesRequestRetryable() {
        dispatch.setStatus("SUCCEEDED");
        when(shares.expose(100L, 99L, 6L, null, "report.md", digest))
                .thenThrow(new IllegalStateException("transient storage failure"));
        service.process(1L);
        verify(transactions).rollback(any());
        verify(requests).retryLater(1L);
        verify(requests, never()).finish(any());
        assertEquals("PENDING", request.getStatus());
    }
}
