package com.aliyun.autowonder.harness;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class WorkspaceOnboardingDO {
    private Long id;
    private Long tenantId;
    private Long workspaceId;
    private Long ownerUserId;
    private String templateKey;
    private String templateVersion;
    private String templateSha256;
    private String stage;
    private Boolean paused;
    private Integer configVersion;
    private Integer stepsTotal;
    private Boolean stepsEstimated;
    private Integer currentStepIndex;
    private String requestKey;
    private String draftJson;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}
