package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchTransport;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.ResumeDescriptor;
import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.dispatch.ExecutorProtocolFeatures;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.websocket.frame.DebugLogDirective;
import com.aliyun.autowonder.websocket.frame.TaskDispatchFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WsDispatchTransport implements DispatchTransport {

    private static final Logger log = LoggerFactory.getLogger(WsDispatchTransport.class);
    public static final String BROADCAST_CHANNEL = "node:dispatch:broadcast";
    static final String TASK_PACKAGE_SIGNATURE_V1 = "TASK_PACKAGE_SIGNATURE_V1";
    static final String TASK_PACKAGE_HOOKS_V1 = "TASK_PACKAGE_HOOKS_V1";
    static final String TASK_PACKAGE_TOOL_HOOKS_V1 = "TASK_PACKAGE_TOOL_HOOKS_V1";
    static final String DEBUG_LOG_V1 = "DEBUG_LOG_V1";
    public static final String AGENT_ENVIRONMENT_VARIABLES_V1 =
            ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1;
    /** 单轮 debug 日志硬上限 200MB（协议契约缺省值，runtime 超限截断并上报 truncated=true）。 */
    static final long DEBUG_LOG_MAX_BYTES = 209_715_200L;

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;
    private final NodeIdentity nodeIdentity;
    private final DispatchCheckpointService checkpointService;
    private final DispatchMcpTokenService dispatchMcpTokenService;
    private final PresenceManager presenceManager;
    private final SecretCrypto secretCrypto;
    private final AgentEnvironmentVariableResolver environmentVariableResolver;

    @Autowired
    public WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService, PresenceManager presenceManager,
            SecretCrypto secretCrypto,
            AgentEnvironmentVariableResolver environmentVariableResolver) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
        this.nodeIdentity = nodeIdentity;
        this.checkpointService = checkpointService;
        this.dispatchMcpTokenService = dispatchMcpTokenService;
        this.presenceManager = presenceManager;
        this.secretCrypto = secretCrypto;
        this.environmentVariableResolver = environmentVariableResolver;
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, dispatchMcpTokenService,
                null, null, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService, PresenceManager presenceManager) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, dispatchMcpTokenService,
                presenceManager, null, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity) {
        this(sessionRegistry, redisManager, nodeIdentity, null, null, null, null, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, null, null, null, null);
    }

    @Override
    public void dispatch(DispatchDO dispatch, TaskPackageResult taskPackage) {
        log.info("dispatch sending dispatchId={} executorId={} pkgSize={}",
                dispatch.getId(), dispatch.getExecutorId(), taskPackage.getSize());
        requireTaskPackageProtocol(dispatch, taskPackage);
        TaskDispatchFrame frame = buildFrame(dispatch, taskPackage);
        log.info("dispatch urls dispatchId={} executorId={} downloadUrl={} packageRefreshPath={}"
                        + " artifactUploadPath={} checkpointUploadPath={} resumeMode={} resumeCheckpointUrl={}",
                dispatch.getId(), dispatch.getExecutorId(), frame.getDownloadUrl(),
                frame.getPackageRefreshPath(), frame.getArtifactUploadPath(),
                frame.getCheckpointUploadPath(), frame.getResumeMode(), frame.getResumeCheckpointUrl());
        String frameJson = JSON.toJSONString(frame);

        ExecutorSession es = sessionRegistry.findByExecutorId(dispatch.getExecutorId());
        if (es != null && es.getSession().isOpen()) {
            sendLocal(es, frameJson, dispatch.getId());
            return;
        }
        publishRemote(dispatch.getExecutorId(), frameJson, dispatch.getId());
    }

    private void requireTaskPackageProtocol(DispatchDO dispatch, TaskPackageResult taskPackage) {
        if (presenceManager == null) {
            return;
        }
        long executorId = dispatch.getExecutorId();
        if (taskPackage.isRequiresToolHookProtocol()
                && !presenceManager.supportsProtocolFeature(executorId, TASK_PACKAGE_TOOL_HOOKS_V1)) {
            throw new IllegalStateException("Executor does not support blocking tool hooks");
        }
        if (taskPackage.getSignature() == null) {
            return;
        }
        if (!presenceManager.supportsProtocolFeature(executorId, TASK_PACKAGE_SIGNATURE_V1)) {
            log.warn("executor {} does not declare TASK_PACKAGE_SIGNATURE_V1; dispatching without enforcement",
                    executorId);
            return;
        }
        if (taskPackage.isRequiresHookProtocol()
                && !presenceManager.supportsProtocolFeature(executorId, TASK_PACKAGE_HOOKS_V1)) {
            log.warn("executor {} does not declare TASK_PACKAGE_HOOKS_V1; dispatching without hook enforcement",
                    executorId);
        }
    }

    private void sendLocal(ExecutorSession es, String frameJson, long dispatchId) {
        try {
            es.sendText(frameJson);
            log.info("dispatch sent local dispatchId={} executorId={}", dispatchId, es.getExecutorId());
        } catch (Exception e) {
            log.error("WS send failed dispatchId={} executorId={}", dispatchId, es.getExecutorId(), e);
            throw new IllegalStateException("WebSocket dispatch send failed", e);
        }
    }

    private void publishRemote(long executorId, String frameJson, long dispatchId) {
        try {
            redisManager.publish(BROADCAST_CHANNEL, frameJson);
            log.info("dispatch sent remote dispatchId={} executorId={}", dispatchId, executorId);
        } catch (Exception e) {
            log.error("cross-node publish failed dispatchId={} executorId={}", dispatchId, executorId, e);
            throw new IllegalStateException("Cross-node dispatch publish failed", e);
        }
    }

    private TaskDispatchFrame buildFrame(DispatchDO dispatch, TaskPackageResult pkg) {
        TaskDispatchFrame f = new TaskDispatchFrame();
        f.setDispatchId(dispatch.getId());
        f.setExecutorId(dispatch.getExecutorId());
        f.setTenantId(dispatch.getTenantId());
        f.setWorkitemId(dispatch.getWorkitemId());
        f.setIdempotencyKey(dispatch.getIdempotencyKey());
        f.setAgentId(dispatch.getAgentId());
        f.setAgentVersionId(dispatch.getAgentVersionId());
        f.setSdlcStepId(dispatch.getSdlcStepId());
        f.setAttempt(dispatch.getAttempt());
        f.setDownloadUrl(pkg.getDownloadUrl());
        f.setMd5(pkg.getMd5());
        f.setSize(pkg.getSize());
        f.setPackageId("pkg_" + dispatch.getId());
        f.setChecksum("sha256:" + pkg.getSha256());
        f.setChecksumAlgorithm("sha256");
        f.setChecksumScope("zip_archive");
        f.setIssuer(pkg.getIssuer());
        f.setSignatureRef(pkg.getSignatureRef());
        f.setSignature(pkg.getSignature());
        f.setSignatureAlgorithm(pkg.getSignatureAlgorithm());
        f.setSignaturePublicKey(pkg.getSignaturePublicKey());
        f.setExpiresAt(pkg.getExpiresAt());
        f.setAllowCommit(pkg.isAllowCommit());
        f.setAllowPush(pkg.isAllowPush());
        f.setAllowNetwork(pkg.isAllowNetwork());
        f.setPackageRefreshPath("/api/daemon/dispatches/" + dispatch.getId() + "/package-url");
        f.setArtifactUploadPath("/api/daemon/dispatches/" + dispatch.getId() + "/artifacts");
        f.setCheckpointUploadPath("/api/daemon/dispatches/" + dispatch.getId() + "/checkpoint");
        applyDebugLogDirective(f, dispatch);
        if (dispatchMcpTokenService != null) {
            f.setDispatchMcpToken(dispatchMcpTokenService.issue(dispatch));
        }
        if (pkg.getMcpSecretRefs() != null && !pkg.getMcpSecretRefs().isEmpty()) {
            if (secretCrypto == null) {
                throw new IllegalStateException("MCP 私密配置需要密文存储支持");
            }
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (String ref : pkg.getMcpSecretRefs().keySet()) {
                values.put(ref, secretCrypto.decrypt(ref));
            }
            f.setMcpSecrets(values);
        }
        ResumeDescriptor resume = checkpointService != null ? checkpointService.descriptor(dispatch) : null;
        if (resume != null) {
            f.setResumeMode(resume.mode());
            f.setResumeSessionBehavior(resume.sessionBehavior());
            f.setResumeFromDispatchId(resume.sourceDispatchId());
            f.setResumeProvider(resume.provider());
            f.setResumeSessionId(resume.providerSessionId());
            f.setResumeCheckpointUrl(resume.checkpointDownloadUrl());
            f.setResumeCheckpointSha256(resume.checkpointSha256());
            f.setResumeCheckpointSeq(resume.checkpointSeq());
            f.setResumeCheckpointCandidates(resume.checkpointCandidates());
        }
        java.util.Map<String, String> environmentVariables = environmentVariableResolver == null
                ? java.util.Map.of()
                : environmentVariableResolver.resolve(dispatch.getTenantId(), dispatch.getAgentVersionId());
        requireEnvironmentVariableProtocol(dispatch.getExecutorId(), environmentVariables);
        f.setEnvironmentVariables(environmentVariables);
        return f;
    }

    private void requireEnvironmentVariableProtocol(long executorId,
            java.util.Map<String, String> environmentVariables) {
        if (environmentVariables.isEmpty()) {
            return;
        }
        if (presenceManager == null || !presenceManager.supportsProtocolFeature(
                executorId, AGENT_ENVIRONMENT_VARIABLES_V1)) {
            throw new ExecutorProtocolCompatibilityException(AGENT_ENVIRONMENT_VARIABLES_V1);
        }
    }

    /**
     * best-effort：仅当 dispatch 冻结开关为 TRUE 且 executor 心跳声明了 DEBUG_LOG_V1 时下发
     * debugLog 段（老 runtime 无该键，行为不变）。能力探测/组装的任何异常只降级为不下发 + warn，
     * 绝不影响 TASK_DISPATCH 帧下发。
     */
    private void applyDebugLogDirective(TaskDispatchFrame f, DispatchDO dispatch) {
        if (!Boolean.TRUE.equals(dispatch.getDebugLogEnabled()) || presenceManager == null) {
            return;
        }
        try {
            if (presenceManager.supportsProtocolFeature(dispatch.getExecutorId(), DEBUG_LOG_V1)) {
                DebugLogDirective debugLog = new DebugLogDirective();
                debugLog.setEnabled(true);
                debugLog.setMaxBytes(DEBUG_LOG_MAX_BYTES);
                f.setDebugLog(debugLog);
            }
        } catch (RuntimeException e) {
            log.warn("debug log directive skipped dispatchId={} executorId={} reason=DEBUG_LOG_NEGOTIATE_ERROR",
                    dispatch.getId(), dispatch.getExecutorId(), e);
        }
    }
}
