package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.prd.dto.RequirementAcceptanceReport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Executor write-back boundary for acceptance. These assert the trust rules, not the domain logic
 * (covered by {@link PrdAcceptanceServiceTest}): only an authenticated, non-fenced workitem dispatch
 * may report, the workspace comes from the dispatch rather than the body, and a domain rejection is
 * surfaced as a 400 rather than a silent success.
 */
class DaemonPrdAcceptanceControllerTest {

    private static final List<RequirementAcceptanceReport> REPORTS = List.of(new RequirementAcceptanceReport());

    @Test
    void unauthenticatedRequestIsRejected() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        when(authenticator.authenticate(1, "bad")).thenReturn(DaemonUploadAuthenticator.AuthResult.fail());
        DaemonPrdAcceptanceController controller =
                new DaemonPrdAcceptanceController(authenticator, mock(PrdAcceptanceService.class));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.report(1, "bad", REPORTS).getStatusCode());
    }

    @Test
    void aScheduledRunDispatchMayNotReportAcceptance() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        when(authenticator.authenticate(2, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 99, 30, null,
                        ExecutionSourceType.SCHEDULED_TASK_RUN));
        DaemonPrdAcceptanceController controller =
                new DaemonPrdAcceptanceController(authenticator, mock(PrdAcceptanceService.class));

        assertEquals(HttpStatus.FORBIDDEN, controller.report(2, "token", REPORTS).getStatusCode());
    }

    @Test
    void aFencedDispatchMayNotReportAcceptance() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        when(authenticator.authenticate(1, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 20, 30, null,
                        ExecutionSourceType.WORKITEM));
        when(authenticator.isMutationFenced(1)).thenReturn(true);
        DaemonPrdAcceptanceController controller =
                new DaemonPrdAcceptanceController(authenticator, mock(PrdAcceptanceService.class));

        assertEquals(HttpStatus.FORBIDDEN, controller.report(1, "token", REPORTS).getStatusCode());
    }

    @Test
    void anActiveWorkitemDispatchReportsUsingTheDispatchWorkspace() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        when(authenticator.authenticate(1, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 20, 30, null,
                        ExecutionSourceType.WORKITEM));
        when(authenticator.isMutationFenced(1)).thenReturn(false);
        PrdAcceptanceService service = mock(PrdAcceptanceService.class);
        // The workspace (10) and reporter (agentId 30) come from the authenticated dispatch.
        when(service.report(anyLong(), anyLong(), anyList(), anyLong())).thenReturn(1);
        DaemonPrdAcceptanceController controller = new DaemonPrdAcceptanceController(authenticator, service);

        var response = controller.report(1, "token", REPORTS);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void aDomainRejectionIsSurfacedAsABadRequestNotASilentSuccess() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        when(authenticator.authenticate(1, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 20, 30, null,
                        ExecutionSourceType.WORKITEM));
        when(authenticator.isMutationFenced(1)).thenReturn(false);
        PrdAcceptanceService service = mock(PrdAcceptanceService.class);
        when(service.report(anyLong(), anyLong(), anyList(), anyLong()))
                .thenThrow(new BizException(ErrorCode.PRD_ACCEPTANCE_EVIDENCE_REQUIRED));
        DaemonPrdAcceptanceController controller = new DaemonPrdAcceptanceController(authenticator, service);

        var response = controller.report(1, "token", REPORTS);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
