package com.aliyun.autowonder.conversation.dto;

import lombok.Data;

@Data
public class PlatformTurnRequest {
    private String content;
    /** 前端生成的幂等键，刷新重发时服务端据此去重。 */
    private String clientMessageId;
}
