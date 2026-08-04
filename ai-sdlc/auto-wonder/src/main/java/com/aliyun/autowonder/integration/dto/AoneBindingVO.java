package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AoneBindingVO {
    private Long id;
    private String provider;
    private String externalProjectId;
    private String externalProjectName;
    private String baseUrl;
    private String clientKey;
    private String credentialMasked;
    private String regionId;
    private String writebackStaffId;
    private Integer pollIntervalSeconds;
    private Boolean enabled;
    private Date lastSuccessAt;
    private String lastError;
    private Boolean reusedExistingBinding;
    private Boolean statusTemplateSynced;
}
