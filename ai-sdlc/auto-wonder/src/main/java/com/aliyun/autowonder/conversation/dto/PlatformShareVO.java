package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class PlatformShareVO {
    private Long granteeUserId;
    private String permission;
    private Date gmtCreate;
}
