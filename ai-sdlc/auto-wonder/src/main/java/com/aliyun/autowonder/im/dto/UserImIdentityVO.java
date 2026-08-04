package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserImIdentityVO {
    private String provider;
    private String externalUserId;
    private boolean configured;
    private boolean platformReady;
    private boolean testAvailable;
}
