package com.aliyun.autowonder.conversation;

import java.util.Map;

public record ConversationCapabilitySnapshot(Long agentVersionId, String downloadUrl,
        String sha256, String capabilityHash, String mcpToken, Map<String, String> mcpSecrets) {
}
