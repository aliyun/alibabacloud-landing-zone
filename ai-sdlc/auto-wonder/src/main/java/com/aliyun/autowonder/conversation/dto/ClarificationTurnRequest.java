package com.aliyun.autowonder.conversation.dto;

import lombok.Data;

@Data
public class ClarificationTurnRequest {
    private String content;
    private String clientMessageId;
}
