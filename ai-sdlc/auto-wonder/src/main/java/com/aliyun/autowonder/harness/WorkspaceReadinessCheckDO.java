package com.aliyun.autowonder.harness;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class WorkspaceReadinessCheckDO {
    private Long id;
    private Long tenantId;
    private Long workspaceId;
    private Long onboardingId;
    private Integer configVersion;
    private String status;
    private String evidenceJson;
    private Date checkedAt;
    private Long creatorId;
    private Date gmtCreate;
}
