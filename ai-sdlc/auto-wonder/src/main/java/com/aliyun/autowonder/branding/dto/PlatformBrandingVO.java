package com.aliyun.autowonder.branding.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PlatformBrandingVO {
    private String platformName;
    private String logoUrl;
    private String themeKey;
    private String primaryColor;
    private String domain;
    private String mcpBaseUrl;
    private String recommendedRuntimeVersion;
    private String deploymentVersion;
    private boolean communityEdition;
    private boolean canManage;
}
