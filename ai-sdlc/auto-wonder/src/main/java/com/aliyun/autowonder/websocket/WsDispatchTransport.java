package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchTransport;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.ResumeDescriptor;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
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

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;
    private final NodeIdentity nodeIdentity;
    private final DispatchCheckpointService checkpointService;
    private final DispatchMcpTokenService dispatchMcpTokenService;

    @Autowired
    public WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService,
            DispatchMcpTokenService dispatchMcpTokenService) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
        this.nodeIdentity = nodeIdentity;
        this.checkpointService = checkpointService;
        this.dispatchMcpTokenService = dispatchMcpTokenService;
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity) {
        this(sessionRegistry, redisManager, nodeIdentity, null, null);
    }

    WsDispatchTransport(SessionRegistry sessionRegistry, RedisManager redisManager,
            NodeIdentity nodeIdentity, DispatchCheckpointService checkpointService) {
        this(sessionRegistry, redisManager, nodeIdentity, checkpointService, null);
    }

    @Override
    public void dispatch(DispatchDO dispatch, TaskPackageResult taskPackage) {
        log.info("dispatch sending dispatchId={} executorId={} pkgSize={}",
                dispatch.getId(), dispatch.getExecutorId(), taskPackage.getSize());
        String frameJson = buildFrame(dispatch, taskPackage);

        ExecutorSession es = sessionRegistry.findByExecutorId(dispatch.getExecutorId());
        if (es != null && es.getSession().isOpen()) {
            sendLocal(es, frameJson, dispatch.getId());
            return;
        }
        publishRemote(dispatch.getExecutorId(), frameJson, dispatch.getId());
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

    private String buildFrame(DispatchDO dispatch, TaskPackageResult pkg) {
        TaskDispatchFrame f = new TaskDispatchFrame();
        f.setDispatchId(dispatch.getId());
        f.setExecutorId(dispatch.getExecutorId());
        f.setTenantId(dispatch.getTenantId());
        f.setWorkitemId(dispatch.getWorkitemId());
        f.setSdlcStepId(dispatch.getSdlcStepId());
        f.setAttempt(dispatch.getAttempt());
        f.setDownloadUrl(pkg.getDownloadUrl());
        f.setMd5(pkg.getMd5());
        f.setSize(pkg.getSize());
        f.setPackageId("pkg_" + dispatch.getId());
        f.setChecksum("sha256:" + pkg.getSha256());
        f.setChecksumAlgorithm("sha256");
        f.setChecksumScope("zip_archive");
        f.setPackageRefreshPath("/api/daemon/dispatches/" + dispatch.getId() + "/package-url");
        f.setArtifactUploadPath("/api/daemon/dispatches/" + dispatch.getId() + "/artifacts");
        f.setCheckpointUploadPath("/api/daemon/dispatches/" + dispatch.getId() + "/checkpoint");
        if (dispatchMcpTokenService != null) {
            f.setDispatchMcpToken(dispatchMcpTokenService.issue(dispatch));
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
        return JSON.toJSONString(f);
    }
}
