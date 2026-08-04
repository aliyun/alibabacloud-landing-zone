package com.aliyun.autowonder.dispatch;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DispatchCheckpointDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long dispatchId;
    private Long agentId;
    private Long checkpointSeq;
    private String provider;
    private String providerSessionId;
    private String runtimeId;
    private Long executorId;
    private String activeStepId;
    private String ossRef;
    private String sha256;
    private Long sizeBytes;
    private Date gmtCreate;
}
