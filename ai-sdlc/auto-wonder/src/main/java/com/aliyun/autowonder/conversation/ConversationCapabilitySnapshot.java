package com.aliyun.autowonder.conversation;

public record ConversationCapabilitySnapshot(Long agentVersionId, String downloadUrl,
        String sha256, String capabilityHash, String mcpToken) {
}
