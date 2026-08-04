package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DaemonUploadAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(DaemonUploadAuthenticator.class);

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
        return AuthResult.success(d.getTenantId(), d.getWorkitemId(), d.getAgentId(), d.getResumeMode());
    }

    public static class AuthResult {
        private final boolean success;
        private final long tenantId;
        private final long workitemId;
        private final long agentId;
        private final String resumeMode;

        private AuthResult(boolean success, long tenantId, long workitemId, long agentId, String resumeMode) {
            this.success = success;
            this.tenantId = tenantId;
            this.workitemId = workitemId;
            this.agentId = agentId;
            this.resumeMode = resumeMode;
        }

        public static AuthResult success(long tenantId, long workitemId, long agentId) {
            return success(tenantId, workitemId, agentId, null);
        }

        public static AuthResult success(long tenantId, long workitemId, long agentId, String resumeMode) {
            return new AuthResult(true, tenantId, workitemId, agentId, resumeMode);
        }

        public static AuthResult fail() {
            return new AuthResult(false, 0, 0, 0, null);
        }

        public boolean isSuccess() { return success; }
        public long getTenantId() { return tenantId; }
        public long getWorkitemId() { return workitemId; }
        public long getAgentId() { return agentId; }
        public String getResumeMode() { return resumeMode; }
        public boolean isInteractionDispatch() {
            return "COMMENT_INTERACTION".equalsIgnoreCase(resumeMode)
                    || "SIDE_INTERACTION".equalsIgnoreCase(resumeMode)
                    || "CANONICAL_INTERACTION".equalsIgnoreCase(resumeMode);
        }
    }
}
