package com.aliyun.autowonder.sdlc;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SdlcStepDO {
    private Long id;
    private Long tenantId;
    private Long sdlcId;
    private Integer stepOrder;
    private String name;
    private String kind;
    private String instructionMd;
    private String checklistJson;
    private String gatePolicyJson;
    private Boolean required;
    private Integer timeoutSeconds;
    private Integer retryBudget;
    private String code;
    private String handlerType;
    private String handlerRoleRef;
    private String statusOnEnterCode;
    private String onSuccess;
    private String onFail;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
