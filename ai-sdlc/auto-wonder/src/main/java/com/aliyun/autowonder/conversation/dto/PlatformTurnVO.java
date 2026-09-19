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
    private String status;
    private String error;
    private Date gmtCreate;
}
