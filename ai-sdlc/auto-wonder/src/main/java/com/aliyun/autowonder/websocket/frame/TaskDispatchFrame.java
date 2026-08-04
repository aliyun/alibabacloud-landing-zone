package com.aliyun.autowonder.websocket.frame;

import com.aliyun.autowonder.dispatch.ResumeCheckpointCandidate;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TaskDispatchFrame extends OutboundFrame {
    private Long dispatchId;
    private Long executorId;
    private Long tenantId;
    private Long workitemId;
    private Long sdlcStepId;
    private Integer attempt;
    private String downloadUrl;
    private String md5;
    private Long size;
    private String packageId;
    private String checksum;
    private String checksumAlgorithm;
    private String checksumScope;
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

    public TaskDispatchFrame() {
        setType("TASK_DISPATCH");
    }
}
