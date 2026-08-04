package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;

@Getter
@Setter
public class UpdateDingTalkChannelRequest {
    private boolean enabled;
    @Size(max = 128)
    private String appKey;
    @Size(max = 1024)
    private String appSecret;
    @Size(max = 128)
    private String robotCode;
    @Size(max = 512)
    private String baseUrl;
}
