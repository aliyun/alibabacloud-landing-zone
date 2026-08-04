package com.aliyun.autowonder.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ResumeCheckpointCandidate {
    private final String downloadUrl;
    private final String sha256;
    private final Long checkpointSeq;
}
