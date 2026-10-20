package com.aliyun.autowonder.ai.adapter;

import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

public interface SceneAdapter {

    String scene();

    String buildSystemPrompt(AiSessionDO session);

    String buildUserPrompt(AiSessionDO session, String userInput);

    String validateResult(String resultJson);

    default BizException validateForConfirmation(String resultJson) {
        String diagnostic = validateResult(resultJson);
        return diagnostic == null ? null : new BizException(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, diagnostic);
    }

    void persistConfirmedResult(AiSessionDO session, String resultJson);
}
