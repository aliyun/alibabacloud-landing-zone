package com.aliyun.autowonder.harness;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class HarnessRunDO {
    private Long id;
    private Long tenantId;
    private Long workspaceId;
    private Long onboardingId;
    private Long snapshotId;
    private String runType;
    private String status;
    private String resultJson;
    private String failureJson;
    private Integer version;
    private Long creatorId;
    private Date gmtCreate;
    private Date gmtModified;
}
