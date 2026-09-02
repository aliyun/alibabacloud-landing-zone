package com.aliyun.autowonder.workspace.event;

public record WorkspaceAccessRequestedEvent(long tenantId, long requestId, long requesterId,
                                            String requesterDisplayName, String requestedLevel,
                                            String workspaceName) {
}
