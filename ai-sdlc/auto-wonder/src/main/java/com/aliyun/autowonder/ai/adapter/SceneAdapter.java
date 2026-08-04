package com.aliyun.autowonder.ai.adapter;

import com.aliyun.autowonder.ai.AiSessionDO;

public interface SceneAdapter {

    String scene();

    String buildSystemPrompt(AiSessionDO session);

    String buildUserPrompt(AiSessionDO session, String userInput);

    String validateResult(String resultJson);

    void persistConfirmedResult(AiSessionDO session, String resultJson);
}
