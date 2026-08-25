package com.aliyun.autowonder.sdlc.dto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateStepRequest {
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

    /** 请求体是否显式携带 timeoutSeconds（含显式 null，用于恢复未配置） */
    @Setter(AccessLevel.NONE)
    private boolean timeoutSecondsPresent;
    /** 请求体是否显式携带 retryBudget（含显式 null，用于恢复未配置） */
    @Setter(AccessLevel.NONE)
    private boolean retryBudgetPresent;

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.timeoutSecondsPresent = true;
    }

    public void setRetryBudget(Integer retryBudget) {
        this.retryBudget = retryBudget;
        this.retryBudgetPresent = true;
    }
}
