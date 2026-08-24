package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalWorkitemLinkDO {
    private Long id;
    private Long tenantId;
    private String provider;
    private Long bindingId;
    private String externalProjectId;
    private String externalWorkitemId;
    private String externalWorkType;
    private Long workitemId;
    private String externalUrl;
    private String sourceStatusId;
    private String sourceStatusName;
    private String sourceLifecycle;
    private Long reporterPrincipalId;
    private Long businessOwnerPrincipalId;
    private String principalRelationsJson;
    private Date remoteUpdatedAt;
    private String remoteVersionHash;
    private String lastSyncDirection;
    private Date lastSyncAt;
    private String syncStatus;
    private String lastErrorCode;
    private String lastError;
    private Date gmtCreate;
    private Date gmtModified;
}
