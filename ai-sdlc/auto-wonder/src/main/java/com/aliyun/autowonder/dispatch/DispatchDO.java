package com.aliyun.autowonder.dispatch;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class DispatchDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long sdlcStepId;
    private Long agentId;
    private Long agentVersionId;
    private Long executorId;
    private String packageOssRef;
    private String status;
    private Integer attempt;
    private String idempotencyKey;
    private String resultSummary;
    private String error;
    private Long resumeFromDispatchId;
    private Long deliverySourceDispatchId;
    private String resumeMode;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
