package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalProjectBindingDO {
    private Long id;
    private Long tenantId;
    private String provider;
    private String externalProjectId;
    private String externalProjectName;
    private String baseUrl;
    private String clientKey;
    private String credentialRef;
    private String regionId;
    private String writebackStaffId;
    private Integer pollIntervalSeconds;
    private Integer enabled;
    private Date lastSuccessAt;
    private String lastError;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
