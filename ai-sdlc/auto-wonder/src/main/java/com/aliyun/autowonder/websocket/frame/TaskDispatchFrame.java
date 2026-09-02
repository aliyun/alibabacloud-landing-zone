package com.aliyun.autowonder.websocket.frame;

import com.aliyun.autowonder.dispatch.ResumeCheckpointCandidate;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class TaskDispatchFrame extends OutboundFrame {
    private Long dispatchId;
    private Long executorId;
    private Long tenantId;
    private Long workitemId;
    private String idempotencyKey;
    private Long agentId;
    private Long agentVersionId;
    private Long sdlcStepId;
    private Integer attempt;
    private String downloadUrl;
    private String md5;
    private Long size;
    private String packageId;
    private String checksum;
    private String checksumAlgorithm;
    private String checksumScope;
    private String issuer;
    private String signatureRef;
    private String signature;
    private String signatureAlgorithm;
    private String signaturePublicKey;
    private String expiresAt;
    private Boolean allowCommit;
    private Boolean allowPush;
    private Boolean allowNetwork;
    private String packageRefreshPath;
    private String artifactUploadPath;
    private String checkpointUploadPath;
    private String resumeMode;
    private String resumeSessionBehavior;
    private Long resumeFromDispatchId;
    private String resumeProvider;
    private String resumeSessionId;
    private String resumeCheckpointUrl;
    private String resumeCheckpointSha256;
    private Long resumeCheckpointSeq;
    private List<ResumeCheckpointCandidate> resumeCheckpointCandidates;
    private String dispatchMcpToken;
    /** Task-scoped MCP values, keyed by opaque secret references. Never persisted in task packages. */
    private Map<String, String> mcpSecrets;

    public TaskDispatchFrame() {
        setType("TASK_DISPATCH");
    }
}
