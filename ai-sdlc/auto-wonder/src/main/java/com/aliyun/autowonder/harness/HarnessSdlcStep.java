package com.aliyun.autowonder.harness;

import java.util.List;

/** 模板中的一条 SDLC 步骤，字段与 {@code CreateStepRequest} 对齐。 */
public record HarnessSdlcStep(int order, String code, String name, String kind, String instruction,
        String checklistJson, String gatePolicyJson, boolean required, Integer timeoutSeconds,
        Integer retryBudget, String handlerType, String handlerRoleRef, String statusOnEnterCode,
        String onSuccess, String onFail) {
}
