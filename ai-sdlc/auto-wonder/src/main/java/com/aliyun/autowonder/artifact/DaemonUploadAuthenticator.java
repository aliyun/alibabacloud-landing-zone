package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DaemonUploadAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(DaemonUploadAuthenticator.class);

    private com.aliyun.autowonder.dispatch.DispatchRecoveryService recovery;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecovery(com.aliyun.autowonder.dispatch.DispatchRecoveryService service) { recovery = service; }

    /** Checkpoints may still be uploaded to acknowledge stopping; business writes may not. */
    public boolean isMutationFenced(long dispatchId) {
        DispatchDO d = dispatchDao.findById(dispatchId);
        return d == null || "CANCELED".equals(d.getStatus()) || (recovery != null && recovery.fenced(d));
    }

    private final DispatchDao dispatchDao;
    private final ExecutorDao executorDao;
    private final TokenService tokenService;

    public DaemonUploadAuthenticator(DispatchDao dispatchDao, ExecutorDao executorDao,
                                     TokenService tokenService) {
        this.dispatchDao = dispatchDao;
        this.executorDao = executorDao;
        this.tokenService = tokenService;
    }

    public AuthResult authenticate(long dispatchId, String token) {
        DispatchDO d = dispatchDao.findById(dispatchId);
        if (d == null) {
            log.info("upload auth failed dispatchId={} reason=dispatch_not_found", dispatchId);
            return AuthResult.fail();
        }
        ExecutorDO e = executorDao.findById(d.getExecutorId());
        if (e == null || !tokenService.validate(e.getTokenRef(), token)) {
            log.info("upload auth failed dispatchId={} reason=executor_or_token_invalid", dispatchId);
            return AuthResult.fail();
        }
        log.info("upload auth ok dispatchId={} tenantId={} workitemId={}", dispatchId, d.getTenantId(), d.getWorkitemId());
        return AuthResult.success(d.getTenantId(), d.getWorkitemId(), d.getAgentId(), d.getResumeMode(),
                d.executionSourceType());
    }

    public enum DetailedAuthStatus { OK, DISPATCH_NOT_FOUND, TOKEN_INVALID }

    public record DetailedAuthResult(DetailedAuthStatus status, DispatchDO dispatch) {}

    /**
     * Same validation chain as {@link #authenticate} but separates "dispatch missing" from
     * "token invalid" so the debug-log issuing endpoint can answer 404 vs 403 per contract.
     */
    public DetailedAuthResult authenticateDetailed(long dispatchId, String token) {
        DispatchDO d = dispatchDao.findById(dispatchId);
        if (d == null) {
            log.info("detailed upload auth failed dispatchId={} reason=dispatch_not_found", dispatchId);
            return new DetailedAuthResult(DetailedAuthStatus.DISPATCH_NOT_FOUND, null);
        }
        ExecutorDO e = d.getExecutorId() == null ? null : executorDao.findById(d.getExecutorId());
        if (e == null || !tokenService.validate(e.getTokenRef(), token)) {
            log.info("detailed upload auth failed dispatchId={} reason=executor_or_token_invalid",
                    dispatchId);
            return new DetailedAuthResult(DetailedAuthStatus.TOKEN_INVALID, d);
        }
        return new DetailedAuthResult(DetailedAuthStatus.OK, d);
    }

    public static class AuthResult {
        private final boolean success;
        private final long tenantId;
        private final long workitemId;
        private final long agentId;
        private final String resumeMode;
        private final ExecutionSourceType sourceType;

        private AuthResult(boolean success, long tenantId, long workitemId, long agentId, String resumeMode,
                           ExecutionSourceType sourceType) {
            this.success = success;
            this.tenantId = tenantId;
            this.workitemId = workitemId;
            this.agentId = agentId;
            this.resumeMode = resumeMode;
            this.sourceType = sourceType;
        }

        public static AuthResult success(long tenantId, long workitemId, long agentId) {
            return success(tenantId, workitemId, agentId, null, ExecutionSourceType.WORKITEM);
        }

        public static AuthResult success(long tenantId, long workitemId, long agentId, String resumeMode) {
            return success(tenantId, workitemId, agentId, resumeMode, ExecutionSourceType.WORKITEM);
        }

        public static AuthResult success(long tenantId, long workitemId, long agentId, String resumeMode,
                                         ExecutionSourceType sourceType) {
            return new AuthResult(true, tenantId, workitemId, agentId, resumeMode,
                    sourceType == null ? ExecutionSourceType.WORKITEM : sourceType);
        }

        public static AuthResult fail() {
            return new AuthResult(false, 0, 0, 0, null, ExecutionSourceType.WORKITEM);
        }

        public boolean isSuccess() { return success; }
        public long getTenantId() { return tenantId; }
        public long getWorkitemId() { return workitemId; }
        public long getAgentId() { return agentId; }
        public String getResumeMode() { return resumeMode; }
        public ExecutionSourceType getSourceType() { return sourceType; }
        public boolean isInteractionDispatch() {
            return "COMMENT_INTERACTION".equalsIgnoreCase(resumeMode)
                    || "SIDE_INTERACTION".equalsIgnoreCase(resumeMode)
                    || "CANONICAL_INTERACTION".equalsIgnoreCase(resumeMode);
        }
    }
}
