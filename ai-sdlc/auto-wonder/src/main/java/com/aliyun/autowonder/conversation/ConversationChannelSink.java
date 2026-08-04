package com.aliyun.autowonder.conversation;

public interface ConversationChannelSink {
    /** 渠道标识,如 "DINGTALK"。 */
    String channel();

    void deliverReply(AgentConversationDO conv, String replyMarkdown, String sourceExternalMsgId);
}
