package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.prd.dto.RequirementAcceptanceReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executor write-back for per-requirement acceptance results.
 *
 * <p>Authenticates with the executor token through {@link DaemonUploadAuthenticator}, the same trust
 * boundary as artifact uploads, so this controller carries no logged-in workspace context and is
 * listed in {@code WorkspaceAccessAnnotationCoverageTest#EXEMPT_CONTROLLERS}. The workspace and
 * workitem come from the authenticated dispatch, never from the request body, and business writes are
 * refused once the dispatch is fenced.
 */
@RestController
@RequestMapping("/api/daemon/dispatches/{dispatchId}/acceptance")
public class DaemonPrdAcceptanceController {

    private final DaemonUploadAuthenticator authenticator;
    private final PrdAcceptanceService acceptanceService;

    public DaemonPrdAcceptanceController(DaemonUploadAuthenticator authenticator,
                                         PrdAcceptanceService acceptanceService) {
        this.authenticator = authenticator;
        this.acceptanceService = acceptanceService;
    }

    @PostMapping
    public ResponseEntity<?> report(@PathVariable long dispatchId,
                                    @RequestParam("token") String token,
                                    @RequestBody List<RequirementAcceptanceReport> reports) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        if (auth.getSourceType() != ExecutionSourceType.WORKITEM
                || authenticator.isMutationFenced(dispatchId)) {
            return ResponseEntity.status(403).body(error("acceptance is writable only by an active workitem dispatch"));
        }
        try {
            int written = acceptanceService.report(auth.getTenantId(), dispatchId, reports, auth.getAgentId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dispatchId", dispatchId);
            body.put("reported", written);
            return ResponseEntity.ok(body);
        } catch (BizException e) {
            Map<String, Object> body = error(e.getMessage());
            body.put("code", e.getCode());
            body.put("messageKey", e.getMessageKey());
            return ResponseEntity.status(400).body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
