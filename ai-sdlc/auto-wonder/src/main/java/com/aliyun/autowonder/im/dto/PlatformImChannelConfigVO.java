package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformImChannelConfigVO {
    private String provider;
    private String purpose;
    private long routeVersion;
    private Integer version;
    private boolean enabled;
    private boolean selected;
    private String appKey;
    private String robotCode;
    private String baseUrl;
    private boolean secretConfigured;
    private boolean ready;
}
