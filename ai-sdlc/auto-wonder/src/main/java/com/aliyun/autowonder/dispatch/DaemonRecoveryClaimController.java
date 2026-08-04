package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/daemon")
public class DaemonRecoveryClaimController {

    private static final Set<String> RECOVERABLE_STATUSES = Set.of(
            DispatchStatus.DISPATCHED,
            DispatchStatus.ACKED,
            DispatchStatus.RUNNING
    );

    private final DaemonUploadAuthenticator authenticator;
    private final DispatchDao dispatchDao;

    public DaemonRecoveryClaimController(DaemonUploadAuthenticator authenticator,
            DispatchDao dispatchDao) {
        this.authenticator = authenticator;
        this.dispatchDao = dispatchDao;
    }

    @PostMapping("/dispatches/{dispatchId}/recovery-claim")
    public ResponseEntity<?> claim(@PathVariable long dispatchId,
            @RequestParam("token") String token) {
        if (!authenticator.authenticate(dispatchId, token).isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null) {
            return ResponseEntity.notFound().build();
        }
        if (!RECOVERABLE_STATUSES.contains(dispatch.getStatus())
                || dispatch.getTenantId() == null || dispatch.getExecutorId() == null
                || dispatchDao.claimOwnedActive(dispatchId, dispatch.getTenantId(),
                        dispatch.getExecutorId()) != 1) {
            return ResponseEntity.status(409).body(Map.of(
                    "allowed", false,
                    "error", "dispatch is no longer recoverable"));
        }
        return ResponseEntity.ok(Map.of(
                "allowed", true,
                "status", dispatch.getStatus()));
    }
}
