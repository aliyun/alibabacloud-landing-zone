package com.aliyun.autowonder.conversation;

public interface ConversationTransport {
    /**
     * 下发一轮对话到指定 executor。
     * @param systemPrompt 当前在线 AgentVersion 渲染的完整身份提示词，每轮非空
     * @param dispatchAttempt 当前投递序号(1-based)，用于 Runtime 回传 attempt 校验
     */
    void send(AgentConversationDO conv, Long turnId, String content, String systemPrompt,
            Integer dispatchAttempt);

    /**
     * 通知 Runtime 终止指定轮次。Runtime 应立即停止生成，并以
     * CONVERSATION_TURN_ACK(status=CANCELED, replyMarkdown=已产出的部分内容) 收尾。
     */
    void sendCancel(AgentConversationDO conv, Long turnId);

    /**
     * 把用户对挂起 ACP 问答卡片的回答路由回执行器。真正的路由键是 requestId，
     * conversationId 与 turnId 供执行器侧日志定位。
     *
     * @param action     accept / decline / cancel
     * @param answerJson accept 时的答案 JSON 对象；decline 与 cancel 传 null
     */
    void sendElicitationReply(AgentConversationDO conv, long turnId, String requestId,
            String action, String answerJson);

    /** 带外命令探针：要求执行器独立抓取该会话的可用斜杠命令，与轮次无关。 */
    void sendCommandsProbe(AgentConversationDO conv);
}
