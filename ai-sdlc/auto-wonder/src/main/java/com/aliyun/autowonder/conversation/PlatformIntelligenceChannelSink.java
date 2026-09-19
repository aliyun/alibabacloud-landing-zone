package com.aliyun.autowonder.conversation;

import org.springframework.stereotype.Component;

/** Internal callers read persisted results; no IM delivery or browser interaction is required. */
@Component
public class PlatformIntelligenceChannelSink implements ConversationChannelSink {
    public static final String CHANNEL = "PLATFORM_INTERNAL";

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public void deliverReply(AgentConversationDO conv, String replyMarkdown, String sourceExternalMsgId) {
        // AgentConversationService persists the reply before calling the sink.
    }
}
