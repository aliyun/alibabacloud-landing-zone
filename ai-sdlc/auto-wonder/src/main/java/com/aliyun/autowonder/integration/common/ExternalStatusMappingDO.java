package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalStatusMappingDO {
    private Long id;
    private Long tenantId;
    private String provider;
    private Long bindingId;
    private String externalIssueTypeId;
    private String externalStatusId;
    private String externalStatusName;
    private String workType;
    private Long statusNodeId;
    private Integer enabled;
    private Date gmtCreate;
    private Date gmtModified;
}
