package com.aliyun.autowonder.conversation;

import java.util.Map;

public record ConversationCapabilitySnapshot(Long agentVersionId, String downloadUrl,
        String sha256, String capabilityHash, String mcpToken, Map<String, String> mcpSecrets,
        Integer conversationActorProtocolVersion, String officialSystemPrompt) {
    public ConversationCapabilitySnapshot(Long agentVersionId, String downloadUrl,
            String sha256, String capabilityHash, String mcpToken, Map<String, String> mcpSecrets) {
        this(agentVersionId, downloadUrl, sha256, capabilityHash, mcpToken, mcpSecrets, null, null);
    }

    public ConversationCapabilitySnapshot(Long agentVersionId, String downloadUrl,
            String sha256, String capabilityHash, String mcpToken, Map<String, String> mcpSecrets,
            Integer conversationActorProtocolVersion) {
        this(agentVersionId, downloadUrl, sha256, capabilityHash, mcpToken, mcpSecrets,
                conversationActorProtocolVersion, null);
    }

    public ConversationCapabilitySnapshot(Long agentVersionId, String downloadUrl,
            String sha256, String capabilityHash, String mcpToken, Map<String, String> mcpSecrets,
            String officialSystemPrompt) {
        this(agentVersionId, downloadUrl, sha256, capabilityHash, mcpToken, mcpSecrets,
                null, officialSystemPrompt);
    }
}
