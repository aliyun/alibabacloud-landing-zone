package com.aliyun.autowonder.branding.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdatePlatformBrandingRequest {
    private String platformName;
    private String themeKey;
    private String primaryColor;
    private String domain;
}
