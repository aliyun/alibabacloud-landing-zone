package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformImChannelConfigVO {
    private String provider;
    private boolean enabled;
    private String appKey;
    private String robotCode;
    private String baseUrl;
    private boolean secretConfigured;
    private boolean ready;
}
