package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class PlatformTurnVO {
    private Long id;
    private String direction;
    private String content;
    private java.util.List<com.aliyun.autowonder.conversation.PlatformConversationImageService.Image> images;
    private String status;
    private String error;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.aliyun.autowonder.conversation.ConversationTurnDisplay display;
    private Date gmtCreate;
}
