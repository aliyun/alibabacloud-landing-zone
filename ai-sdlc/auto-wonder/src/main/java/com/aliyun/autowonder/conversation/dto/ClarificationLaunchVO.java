package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;

/** MCP 起始澄清会话的结果：会话身份 + 首条消息是否已投递。 */
@Data
@Builder
public class ClarificationLaunchVO {
    private Long conversationId;
    private Long agentId;
    private String agentName;
    private String status;
    private boolean executorOnline;
    private boolean turnSubmitted;
}
