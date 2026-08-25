package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class ExternalCollaborationVO {
    private String provider;
    private String externalProjectId;
    private String externalWorkitemId;
    private String externalUrl;
    private String sourceStatusId;
    private String sourceStatusName;
    private String sourceLifecycle;
    private ExternalPrincipalVO reporter;
    private ExternalPrincipalVO businessOwner;
    private List<ExternalPrincipalRelationVO> principalRelations = new ArrayList<>();
    private Date lastSyncAt;
    private String syncStatus;
    private String lastErrorCode;
    private String lastError;
}
