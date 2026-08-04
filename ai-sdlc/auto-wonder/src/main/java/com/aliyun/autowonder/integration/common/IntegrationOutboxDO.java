package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class IntegrationOutboxDO {
    private Long id;
    private Long tenantId;
    private String provider;
    private Long bindingId;
    private Long workitemId;
    private String eventType;
    private String payloadJson;
    private String status;
    private Integer retryCount;
    private Date nextRetryAt;
    private String lastError;
    private Date gmtCreate;
    private Date gmtModified;
}
