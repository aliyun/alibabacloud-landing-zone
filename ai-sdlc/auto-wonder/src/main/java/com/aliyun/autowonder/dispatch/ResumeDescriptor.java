package com.aliyun.autowonder.dispatch;

import java.util.List;

public record ResumeDescriptor(
        String mode,
        String sessionBehavior,
        Long sourceDispatchId,
        String provider,
        String providerSessionId,
        String checkpointDownloadUrl,
        String checkpointSha256,
        Long checkpointSeq,
        List<ResumeCheckpointCandidate> checkpointCandidates) {

    public ResumeDescriptor(String mode, Long sourceDispatchId, String provider,
            String providerSessionId, String checkpointDownloadUrl,
            String checkpointSha256, Long checkpointSeq) {
        this(mode, null, sourceDispatchId, provider, providerSessionId,
                checkpointDownloadUrl, checkpointSha256, checkpointSeq, List.of());
    }

    public ResumeDescriptor(String mode, Long sourceDispatchId, String provider,
            String providerSessionId, String checkpointDownloadUrl,
            String checkpointSha256, Long checkpointSeq,
            List<ResumeCheckpointCandidate> checkpointCandidates) {
        this(mode, null, sourceDispatchId, provider, providerSessionId,
                checkpointDownloadUrl, checkpointSha256, checkpointSeq, checkpointCandidates);
    }
}
