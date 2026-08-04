package com.aliyun.autowonder.conversation;

public interface ConversationTransport {
    /**
     * 下发一轮对话到指定 executor。
     * @param systemPrompt 当前在线 AgentVersion 渲染的完整身份提示词，每轮非空
     * @param dispatchAttempt 当前投递序号(1-based)，用于 Runtime 回传 attempt 校验
     */
    void send(AgentConversationDO conv, Long turnId, String content, String systemPrompt,
            Integer dispatchAttempt);
}
