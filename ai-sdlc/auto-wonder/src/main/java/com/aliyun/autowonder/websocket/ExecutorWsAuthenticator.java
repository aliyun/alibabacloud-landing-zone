package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExecutorWsAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ExecutorWsAuthenticator.class);

    private final ExecutorDao executorDao;
    private final TokenService tokenService;

    public ExecutorWsAuthenticator(ExecutorDao executorDao, TokenService tokenService) {
        this.executorDao = executorDao;
        this.tokenService = tokenService;
    }

    public AuthResult authenticate(long executorId, String plainToken) {
        ExecutorDO exec = executorDao.findById(executorId);
        if (exec == null || (exec.getIsDeleted() != null && exec.getIsDeleted() != 0)) {
            log.info("ws auth failed executorId={} reason=not_found_or_deleted", executorId);
            return AuthResult.fail();
        }
        if (!tokenService.validate(exec.getTokenRef(), plainToken)) {
            log.info("ws auth failed executorId={} reason=token_mismatch", executorId);
            return AuthResult.fail();
        }
        log.info("ws auth ok executorId={} agentId={} tenantId={}", exec.getId(), exec.getAgentId(), exec.getTenantId());
        return AuthResult.success(exec.getId(), exec.getAgentId(), exec.getTenantId());
    }

    @Getter
    public static class AuthResult {
        private final boolean success;
        private final long executorId;
        private final long agentId;
        private final long tenantId;

        private AuthResult(boolean success, long executorId, long agentId, long tenantId) {
            this.success = success;
            this.executorId = executorId;
            this.agentId = agentId;
            this.tenantId = tenantId;
        }

        static AuthResult success(long executorId, long agentId, long tenantId) {
            return new AuthResult(true, executorId, agentId, tenantId);
        }

        static AuthResult fail() {
            return new AuthResult(false, 0, 0, 0);
        }
    }
}
