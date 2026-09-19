package com.aliyun.autowonder.conversation.dto;

import lombok.Data;

@Data
public class PlatformConversationRequest {
    private Long agentId;
    /** 可选：不填则由服务端按首条消息自动生成。 */
    private String title;
}
