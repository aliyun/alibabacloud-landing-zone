package com.aliyun.autowonder.conversation.dto;

import lombok.Data;

import java.util.List;

@Data
public class ClarificationTurnRequest {
    private String content;
    private String clientMessageId;
    /** 已上传并等待随本条消息发送的附件；空表示纯文本消息。 */
    private List<Long> attachmentIds;
}
