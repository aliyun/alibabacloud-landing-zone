package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator.DetailedAuthResult;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator.DetailedAuthStatus;
import com.aliyun.autowonder.dispatch.DispatchDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogUploadControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private DebugLogService debugLogService;
    private DebugLogUploadController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        debugLogService = mock(DebugLogService.class);
        controller = new DebugLogUploadController(authenticator, debugLogService);
    }

    private DispatchDO enabledDispatch(String status) {
        DispatchDO d = new DispatchDO();
        d.setId(900L);
        d.setTenantId(100L);
        d.setStatus(status);
        d.setDebugLogEnabled(true);
        return d;
    }

    private DebugLogUploadRequest request(String dispatchStatus) {
        DebugLogUploadRequest req = new DebugLogUploadRequest();
        req.setSizeBytes(123L);
        req.setSha256("a".repeat(64));
        req.setTruncated(false);
        req.setDispatchStatus(dispatchStatus);
        return req;
    }

    private void stubAuth(DetailedAuthStatus status, DispatchDO dispatch) {
        when(authenticator.authenticateDetailed(900L, "tok"))
                .thenReturn(new DetailedAuthResult(status, dispatch));
    }

    @Test
    void missingDispatchReturns404() {
        stubAuth(DetailedAuthStatus.DISPATCH_NOT_FOUND, null);

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

        assertEquals(404, resp.getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void badTokenReturns403() {
        stubAuth(DetailedAuthStatus.TOKEN_INVALID, enabledDispatch("SUCCEEDED"));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

        assertEquals(403, resp.getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void nonTerminalDispatchReturns409() {
        // 申报状态非终态（premature 请求）：非终态派发维持 409。
        // 申报合法终态的 RUNNING 收尾阶段签发已放行，见 closingPhase* 用例（工单 55510 修复）。
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("RUNNING"));

        assertEquals(409, resp.getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void dispatchWithoutFrozenFlagReturns422() {
        DispatchDO d = enabledDispatch("SUCCEEDED");
        d.setDebugLogEnabled(false);
        stubAuth(DetailedAuthStatus.OK, d);

        assertEquals(422, controller.requestUpload(900L, "tok", request("SUCCEEDED"))
                .getStatusCode().value());

        d.setDebugLogEnabled(null);
        assertEquals(422, controller.requestUpload(900L, "tok", request("SUCCEEDED"))
                .getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void nonTerminalReportedStatusReturns400() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));

        assertEquals(400, controller.requestUpload(900L, "tok", request("RUNNING"))
                .getStatusCode().value());
        assertEquals(400, controller.requestUpload(900L, "tok", request(null))
                .getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void missingRequestBodyReturns400() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));

        assertEquals(400, controller.requestUpload(900L, "tok", null).getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void malformedSha256Returns400() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        DebugLogUploadRequest req = request("SUCCEEDED");
        req.setSha256("xyz");

        assertEquals(400, controller.requestUpload(900L, "tok", req).getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void negativeSizeBytesReturns400AndZeroIsAccepted() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        DebugLogUploadRequest negative = request("SUCCEEDED");
        negative.setSizeBytes(-1L);

        assertEquals(400, controller.requestUpload(900L, "tok", negative)
                .getStatusCode().value());
        verifyNoInteractions(debugLogService);

        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("k", "https://oss/put",
                        Instant.parse("2026-09-04T12:34:56Z"), false));
        DebugLogUploadRequest zero = request("SUCCEEDED");
        zero.setSizeBytes(0L);

        assertEquals(200, controller.requestUpload(900L, "tok", zero).getStatusCode().value());
        org.mockito.Mockito.verify(debugLogService).issueUpload(any(DispatchDO.class), eq(0L),
                eq("a".repeat(64)), eq(false), eq("SUCCEEDED"));
    }

    @Test
    void issueReturnsSignedContractBody() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        when(debugLogService.issueUpload(any(DispatchDO.class), eq(123L), eq("a".repeat(64)),
                eq(false), eq("SUCCEEDED")))
                .thenReturn(new DebugLogService.IssueResult("debug/200/DevAgent-run-1.log.gz",
                        "https://oss/put", Instant.parse("2026-09-04T12:34:56Z"), false));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("debug/200/DevAgent-run-1.log.gz", body.get("objectKey"));
        assertEquals("https://oss/put", body.get("uploadUrl"));
        assertEquals("2026-09-04T12:34:56Z", body.get("expiresAt"));
        assertEquals(false, body.get("alreadyUploaded"));
    }

    @Test
    void alreadyUploadedReturnsNullUrlAndTrueFlag() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("debug/200/DevAgent-run-1.log.gz",
                        null, null, true));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNull(body.get("uploadUrl"));
        assertEquals(true, body.get("alreadyUploaded"));
    }

    @Test
    void blankSha256IsNormalizedToNull() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("k", "https://oss/put",
                        Instant.parse("2026-09-04T12:34:56Z"), false));
        DebugLogUploadRequest req = request("SUCCEEDED");
        req.setSha256("  ");

        assertEquals(200, controller.requestUpload(900L, "tok", req).getStatusCode().value());
        org.mockito.Mockito.verify(debugLogService).issueUpload(any(DispatchDO.class), eq(123L),
                eq(null), eq(false), eq("SUCCEEDED"));
    }

    @Test
    void issueFailureReturns503WithIssueFailedCodeWithoutLeaking() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        // issueUpload 先做 DB 操作再 presignPut：任何 RuntimeException（含 DataAccessException）
        // 都不得误归因为 storage_unavailable，统一 issue_failed（S7 评审跟进项 1）
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("db down"));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

        assertEquals(503, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("issue_failed", body.get("error"));
        assertTrue(body.size() == 1);
        assertFalse(body.containsKey("uploadUrl"));
    }

    @Test
    void nonTerminalGatePrecedesBodyValidation() {
        // 校验顺序契约钉死：409（未终态且不构成收尾签发）先于 400（请求体非法），不可重排。
        // 申报状态非终态使请求不构成收尾签发，非法 sha256 不得先于 409 暴露 400；
        // 申报合法终态的 RUNNING + 非法 sha256 走 400，见 closingPhaseStillValidatesBody。
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));
        DebugLogUploadRequest badSha = request("RUNNING");
        badSha.setSha256("xyz");

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", badSha);

        assertEquals(409, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("dispatch_not_terminal", body.get("error"));
        verifyNoInteractions(debugLogService);
    }

    @Test
    void closingPhaseIssuanceSucceedsWhileRunningBeforeTaskResult() {
        // 协议顺序回归（工单 55510）：runtime 先申请上传、后发 TASK_RESULT，
        // DB 终态恰恰由 TASK_RESULT 驱动 —— RUNNING 派发的收尾签发必须放行，
        // 且申报的本地终态（此处 FAILED）原样传入 issueUpload 落 PENDING 快照。
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));
        when(debugLogService.issueUpload(any(DispatchDO.class), eq(123L), eq("a".repeat(64)),
                eq(false), eq("FAILED")))
                .thenReturn(new DebugLogService.IssueResult("debug/200/DevAgent-run-1.log.gz",
                        "https://oss/put", Instant.parse("2026-09-21T12:34:56Z"), false));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("FAILED"));

        assertEquals(200, resp.getStatusCode().value());
        org.mockito.Mockito.verify(debugLogService).issueUpload(any(DispatchDO.class), eq(123L),
                eq("a".repeat(64)), eq(false), eq("FAILED"));
    }

    @Test
    void closingPhaseIssuanceCoversDispatchedAndAcked() {
        // ACK 在途 / 首个进度事件未到（如轮内零进度即失败）的竞态：
        // 已交付执行器的 DISPATCHED/ACKED 同样处于收尾阶段。
        for (String status : new String[]{"DISPATCHED", "ACKED"}) {
            stubAuth(DetailedAuthStatus.OK, enabledDispatch(status));
            when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                    .thenReturn(new DebugLogService.IssueResult("k", "https://oss/put",
                            Instant.parse("2026-09-21T12:34:56Z"), false));

            assertEquals(200, controller.requestUpload(900L, "tok", request("SUCCEEDED"))
                    .getStatusCode().value(), status);
        }
    }

    @Test
    void pauseFamilyNeverQualifiesForClosingPhaseIssuance() {
        // 验收标准 4：暂停路径不得被当作终态上传，一律 409
        for (String status : new String[]{"PAUSING", "PAUSED", "PAUSE_FAILED", "WAITING_FOR_PAUSE"}) {
            stubAuth(DetailedAuthStatus.OK, enabledDispatch(status));

            ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

            assertEquals(409, resp.getStatusCode().value(), status);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertEquals("dispatch_not_terminal", body.get("error"), status);
        }
        verifyNoInteractions(debugLogService);
    }

    @Test
    void notYetDispatchedStatusesNeverQualifyForClosingPhaseIssuance() {
        // 未交付执行器（PENDING/PACKAGING）的派发不存在可申报的本地执行结果，维持 409
        for (String status : new String[]{"PENDING", "PACKAGING"}) {
            stubAuth(DetailedAuthStatus.OK, enabledDispatch(status));

            assertEquals(409, controller.requestUpload(900L, "tok", request("SUCCEEDED"))
                    .getStatusCode().value(), status);
        }
        verifyNoInteractions(debugLogService);
    }

    @Test
    void closingPhaseRequiresTerminalDeclaredStatus() {
        // 申报非终态状态、null 申报、null 请求体：均不构成收尾签发，维持 409
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));

        assertEquals(409, controller.requestUpload(900L, "tok", request("RUNNING"))
                .getStatusCode().value());
        assertEquals(409, controller.requestUpload(900L, "tok", request(null))
                .getStatusCode().value());
        assertEquals(409, controller.requestUpload(900L, "tok", null)
                .getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void closingPhaseStillRequiresDebugEnabled() {
        // 收尾签发不放松打包时冻结的 debug 开关（验收标准 5）
        DispatchDO d = enabledDispatch("RUNNING");
        d.setDebugLogEnabled(false);
        stubAuth(DetailedAuthStatus.OK, d);

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("SUCCEEDED"));

        assertEquals(422, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("debug_log_disabled", body.get("error"));
        verifyNoInteractions(debugLogService);
    }

    @Test
    void closingPhaseStillValidatesBody() {
        // 收尾签发放行后，请求体校验（sha256/sizeBytes）照常生效
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));
        DebugLogUploadRequest badSha = request("FAILED");
        badSha.setSha256("xyz");
        assertEquals(400, controller.requestUpload(900L, "tok", badSha)
                .getStatusCode().value());

        DebugLogUploadRequest negative = request("FAILED");
        negative.setSizeBytes(-1L);
        assertEquals(400, controller.requestUpload(900L, "tok", negative)
                .getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void closingPhaseIssueFailureStillReturns503() {
        // 收尾路径上签发异常语义不变：503 单键 error，不泄存储细节
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("db down"));

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("FAILED"));

        assertEquals(503, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("issue_failed", body.get("error"));
    }

    @Test
    void closingPhaseThenTerminalReIssueConvergesIdempotently() {
        // 协议顺序回归（工单 55510）：TASK_RESULT 之前 RUNNING 收尾签发 →
        // TASK_RESULT 落库终态（此处 FAILED）后的重复签发走既有幂等路径（alreadyUploaded）
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("RUNNING"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("debug/200/DevAgent-run-1.log.gz",
                        "https://oss/put", Instant.parse("2026-09-21T12:34:56Z"), false));
        ResponseEntity<?> before = controller.requestUpload(900L, "tok", request("FAILED"));
        assertEquals(200, before.getStatusCode().value());

        stubAuth(DetailedAuthStatus.OK, enabledDispatch("FAILED"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("debug/200/DevAgent-run-1.log.gz",
                        null, null, true));
        ResponseEntity<?> after = controller.requestUpload(900L, "tok", request("FAILED"));

        assertEquals(200, after.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) after.getBody();
        assertEquals(true, body.get("alreadyUploaded"));
        assertNull(body.get("uploadUrl"));
    }

    @Test
    void debugDisabledGatePrecedesBodyValidation() {
        // 校验顺序契约钉死：422（未开启 debug）先于 400（非法 dispatchStatus），不可重排
        DispatchDO d = enabledDispatch("SUCCEEDED");
        d.setDebugLogEnabled(false);
        stubAuth(DetailedAuthStatus.OK, d);

        ResponseEntity<?> resp = controller.requestUpload(900L, "tok", request("RUNNING"));

        assertEquals(422, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("debug_log_disabled", body.get("error"));
        verifyNoInteractions(debugLogService);
    }

    @Test
    void sha256LengthBoundariesReturn400() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        DebugLogUploadRequest tooShort = request("SUCCEEDED");
        tooShort.setSha256("a".repeat(63));
        assertEquals(400, controller.requestUpload(900L, "tok", tooShort)
                .getStatusCode().value());

        DebugLogUploadRequest tooLong = request("SUCCEEDED");
        tooLong.setSha256("a".repeat(65));
        assertEquals(400, controller.requestUpload(900L, "tok", tooLong)
                .getStatusCode().value());
        verifyNoInteractions(debugLogService);
    }

    @Test
    void upperCaseSha256IsAcceptedAndPassedThroughUnchanged() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("k", "https://oss/put",
                        Instant.parse("2026-09-04T12:34:56Z"), false));
        String upper = "A".repeat(64);
        DebugLogUploadRequest req = request("SUCCEEDED");
        req.setSha256(upper);

        assertEquals(200, controller.requestUpload(900L, "tok", req).getStatusCode().value());
        // 正则 ^[0-9a-fA-F]{64}$ 允许大写；实现透传原值、不做小写归一，
        // 故 debug_log.sha256 按原样存大写 hex（V049 VARCHAR(80)，无 collation 归一诉求）
        org.mockito.Mockito.verify(debugLogService).issueUpload(any(DispatchDO.class), eq(123L),
                eq(upper), eq(false), eq("SUCCEEDED"));
    }

    @Test
    void nullTruncatedIsNormalizedToFalse() {
        stubAuth(DetailedAuthStatus.OK, enabledDispatch("SUCCEEDED"));
        when(debugLogService.issueUpload(any(DispatchDO.class), any(), any(), anyBoolean(), any()))
                .thenReturn(new DebugLogService.IssueResult("k", "https://oss/put",
                        Instant.parse("2026-09-04T12:34:56Z"), false));
        DebugLogUploadRequest req = request("SUCCEEDED");
        req.setTruncated(null);

        assertEquals(200, controller.requestUpload(900L, "tok", req).getStatusCode().value());
        // Boolean.TRUE.equals 语义：null → false 透传
        org.mockito.Mockito.verify(debugLogService).issueUpload(any(DispatchDO.class), eq(123L),
                eq("a".repeat(64)), eq(false), eq("SUCCEEDED"));
    }
}
