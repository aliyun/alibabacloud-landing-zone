package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalWorkitemImportRecordVO {
    private Long id;
    private String sourceSystem;
    private String externalWorkitemId;
    private Long workitemId;
    private String requestId;
    private String status;
    private String failureReason;
    private String sourceUrl;
    private Date gmtCreate;
    private Date gmtModified;
}
