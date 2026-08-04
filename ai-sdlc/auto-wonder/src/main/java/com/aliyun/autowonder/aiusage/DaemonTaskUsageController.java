package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.TaskUsageReportRequest;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/daemon")
public class DaemonTaskUsageController {

    private final DaemonUploadAuthenticator authenticator;
    private final DispatchAiUsageService usageService;

    public DaemonTaskUsageController(DaemonUploadAuthenticator authenticator, DispatchAiUsageService usageService) {
        this.authenticator = authenticator;
        this.usageService = usageService;
    }

    @PostMapping("/tasks/{taskId}/usage")
    public ResponseEntity<?> reportUsage(@PathVariable String taskId,
                                         @RequestParam(value = "dispatchId", required = false) String dispatchIdParam,
                                         @RequestParam(value = "token", required = false) String token,
                                         @RequestBody TaskUsageReportRequest request,
                                         HttpServletRequest httpRequest) {
        Long dispatchId = parseDispatchId(dispatchIdParam != null && !dispatchIdParam.isBlank() ? dispatchIdParam : taskId);
        if (dispatchId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid dispatch id"));
        }
        String authToken = token != null && !token.isBlank() ? token : bearerToken(httpRequest.getHeader("Authorization"));
        if (authToken == null || authToken.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, authToken);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        usageService.recordTaskUsage(auth.getTenantId(), dispatchId, request != null ? request.getUsage() : null);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    private Long parseDispatchId(String dispatchId) {
        try {
            return Long.parseLong(dispatchId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String prefix = "Bearer ";
        return authorization.startsWith(prefix) ? authorization.substring(prefix.length()) : authorization;
    }
}
