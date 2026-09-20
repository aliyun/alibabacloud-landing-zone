package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import com.aliyun.autowonder.websocket.ExecutorWsAuthenticator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/daemon/executors")
public class DaemonExecutorEnvironmentController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ExecutorWsAuthenticator authenticator;
    private final AgentDao agentDao;
    private final AgentEnvironmentVariableResolver environmentVariableResolver;

    public DaemonExecutorEnvironmentController(ExecutorWsAuthenticator authenticator,
            AgentDao agentDao,
            AgentEnvironmentVariableResolver environmentVariableResolver) {
        this.authenticator = authenticator;
        this.agentDao = agentDao;
        this.environmentVariableResolver = environmentVariableResolver;
    }

    @GetMapping("/{executorId}/environment-variables")
    public ResponseEntity<?> environmentVariables(@PathVariable long executorId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return ResponseEntity.status(401).build();
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        ExecutorWsAuthenticator.AuthResult auth = authenticator.authenticate(executorId, token);
        if (auth == null || !auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }

        AgentDO agent = agentDao.findById(auth.getAgentId());
        if (agent == null || !Long.valueOf(auth.getTenantId()).equals(agent.getTenantId())) {
            return ResponseEntity.status(409).build();
        }

        Map<String, String> environmentVariables = agent.getOnlineVersionId() == null
                ? Map.of()
                : environmentVariableResolver.resolve(auth.getTenantId(), agent.getOnlineVersionId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("environmentVariables", environmentVariables));
    }
}
