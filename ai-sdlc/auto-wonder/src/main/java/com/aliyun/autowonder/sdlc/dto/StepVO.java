package com.aliyun.autowonder.sdlc.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StepVO {
    private Long id;
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
}
