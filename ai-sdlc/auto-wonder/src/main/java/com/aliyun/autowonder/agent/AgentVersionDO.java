package com.aliyun.autowonder.agent;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AgentVersionDO {
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Integer versionNo;
    private String status;
    private String roleName;
    private String roleCode;
    private String businessBackground;
    private String responsibilities;
    private Long sdlcId;
    private String identityJson;
    private Long reviewerId;
    private String reviewComment;
    private Date reviewedAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
