package com.aliyun.autowonder.integration.feishu;

public record FeishuSourceContext(Long bindingId, String appId, String chatId, String messageId,
                                  String senderOpenId, String threadId) {
    public String externalId() { return "FEISHU:" + bindingId + ":" + messageId; }
    public String conversationId() {
        return bindingId + ":" + chatId + (threadId == null || threadId.isBlank() ? "" : ":" + threadId);
    }
}
