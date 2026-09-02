package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchTransport;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.ResumeDescriptor;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
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

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;
    private final NodeIdentity nodeIdentity;
    private final DispatchCheckpointService checkpointService;
    private final DispatchMcpTokenService dispatchMcpTokenService;
    private final PresenceManager presenceManager;
    private final SecretCrypto secretCrypto;

    @Autowired
    public WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService, PresenceManager presenceManager,
            SecretCrypto secretCrypto) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
        this.nodeIdentity = nodeIdentity;
        this.checkpointService = checkpointService;
        this.dispatchMcpTokenService = dispatchMcpTokenService;
        this.presenceManager = presenceManager;
        this.secretCrypto = secretCrypto;
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, dispatchMcpTokenService, null, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService, PresenceManager presenceManager) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, dispatchMcpTokenService, presenceManager, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity) {
        this(sessionRegistry, redisManager, nodeIdentity, null, null, null, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, null, null, null);
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
        return f;
    }
}
