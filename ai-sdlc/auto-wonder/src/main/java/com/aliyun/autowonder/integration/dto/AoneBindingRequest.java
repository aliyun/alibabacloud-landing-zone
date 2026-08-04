package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AoneBindingRequest {
    private String baseUrl;
    private String clientKey;
    private String accessSecret;
    private String regionId;
    private String externalProjectId;
    private String externalProjectName;
    private String writebackStaffId;
    private Integer pollIntervalSeconds;
    private Boolean enabled;
}
