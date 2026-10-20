package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class ClarificationTurnVO {
    private Long id;
    private String direction;
    private String content;
    private String status;
    private String error;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.aliyun.autowonder.conversation.ConversationTurnDisplay display;
    private List<ClarificationAttachmentVO> attachments;
    private Date gmtCreate;
}
