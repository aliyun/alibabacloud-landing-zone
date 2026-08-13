package com.aliyun.autowonder.workspace;

public interface WorkspaceCleanupTransport {
    void send(WorkspaceCleanupCandidate candidate);
}
