package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalWorkitemImportRecordDO {
    private Long id;
    private Long tenantId;
    private String sourceSystem;
    private String externalWorkitemId;
    private Long workitemId;
    private String requestId;
    private String status;
    private String failureReason;
    private String sourceUrl;
    private String rawPayloadJson;
    private String extensionsJson;
    private String fieldMappingsJson;
    private Date gmtCreate;
    private Date gmtModified;
}
