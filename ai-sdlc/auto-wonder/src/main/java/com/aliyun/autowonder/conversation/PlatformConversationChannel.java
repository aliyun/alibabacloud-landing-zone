package com.aliyun.autowonder.conversation;

/**
 * Chief Of Staff 平台管家对话的渠道标识。
 * agent_conversation 的 owner_user_id / title / archived_at / deleted_at 只在该渠道下有语义，
 * 其余渠道保持原行为，字段留空。
 */
public final class PlatformConversationChannel {

    public static final String CHANNEL = "PLATFORM_ASSISTANT";

    private PlatformConversationChannel() {}
}
