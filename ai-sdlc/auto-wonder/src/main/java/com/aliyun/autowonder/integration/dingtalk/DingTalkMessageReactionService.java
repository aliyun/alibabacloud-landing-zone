package com.aliyun.autowonder.integration.dingtalk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DingTalkMessageReactionService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkMessageReactionService.class);

    private final DingTalkBindingService bindingService;
    private final DingTalkOutboundSender sender;

    public DingTalkMessageReactionService(DingTalkBindingService bindingService,
            DingTalkOutboundSender sender) {
        this.bindingService = bindingService;
        this.sender = sender;
    }

    public void markThinking(DingtalkRobotBindingDO binding, String openConversationId,
            String openMsgId) {
        if (binding == null || isBlank(openConversationId) || isBlank(openMsgId)) {
            return;
        }
        try {
            String secret = bindingService.decryptSecret(binding);
            sender.replyThinkingEmotion(binding.getAppKey(), secret, binding.getBaseUrl(),
                    binding.getRobotCode(), openConversationId, openMsgId,
                    System.currentTimeMillis());
            log.info("dingtalk reaction thinking added robotCode={} conversationId={} msgId={}",
                    binding.getRobotCode(), openConversationId, openMsgId);
        } catch (Exception e) {
            log.warn("dingtalk reaction thinking add failed robotCode={} conversationId={} msgId={}: {}",
                    binding.getRobotCode(), openConversationId, openMsgId, e.getMessage());
        }
    }

    public void recallThinking(DingtalkRobotBindingDO binding, String openConversationId,
            String openMsgId) {
        if (binding == null || isBlank(openConversationId) || isBlank(openMsgId)) {
            return;
        }
        try {
            String secret = bindingService.decryptSecret(binding);
            sender.recallThinkingEmotion(binding.getAppKey(), secret, binding.getBaseUrl(),
                    binding.getRobotCode(), openConversationId, openMsgId,
                    System.currentTimeMillis());
            log.info("dingtalk reaction thinking recalled robotCode={} conversationId={} msgId={}",
                    binding.getRobotCode(), openConversationId, openMsgId);
        } catch (Exception e) {
            log.warn("dingtalk reaction thinking recall failed robotCode={} conversationId={} msgId={}: {}",
                    binding.getRobotCode(), openConversationId, openMsgId, e.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
