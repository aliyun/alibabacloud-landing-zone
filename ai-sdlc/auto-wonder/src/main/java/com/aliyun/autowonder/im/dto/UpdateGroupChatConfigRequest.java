package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Getter
@Setter
public class UpdateGroupChatConfigRequest {
    @Size(max = 128)
    private String appKey;
    @Size(max = 128)
    private String appId;
    @Size(max = 1024)
    private String appSecret;
    @Size(max = 128)
    private String robotCode;
    @Size(max = 512)
    private String verificationToken;
    @Size(max = 512)
    private String encryptKey;
    private boolean clearEncryptKey;
    private boolean enabled;
    private Integer version;
}
