package com.aliyun.autowonder.debuglog;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DebugLogDO {
    private Long id;
    private Date gmtCreate;
    private Date gmtModified;
    private Long tenantId;
    private String sourceType;
    private Long sourceId;
    private Long dispatchId;
    private Long agentId;
    private Long agentVersionId;
    private Integer runNo;
    private String dispatchStatus;
    private String objectKey;
    private Long sizeBytes;
    private String sha256;
    private Boolean truncated;
    private String uploadChannel;
    private String status;
    private String errorMessage;
}
