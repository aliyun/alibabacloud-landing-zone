package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DingTalkInboundService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkInboundService.class);

    private final DingtalkRobotBindingDao bindingDao;
    private final DingTalkBindingService bindingService;
    private final AgentConversationService conversationService;
    private final DingTalkMessageReactionService reactionService;

    public DingTalkInboundService(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, AgentConversationService conversationService,
            DingTalkMessageReactionService reactionService) {
        this.bindingDao = bindingDao;
        this.bindingService = bindingService;
        this.conversationService = conversationService;
        this.reactionService = reactionService;
    }

    /** 已验签后调用:robotCode 直路由到 agent,提交一轮对话。 */
    public void handle(InboundBotMessage msg) {
        DingtalkRobotBindingDO binding = bindingDao.findByRobotCodeGlobal(msg.robotCode());
        if (binding == null || !"ENABLED".equals(binding.getStatus())) {
            log.info("dingtalk inbound ignored: no enabled binding robotCode={}", msg.robotCode());
            return;
        }
        if (conversationService.hasExternalMessage(binding.getTenantId(), msg.msgId())) {
            log.info("dingtalk inbound duplicate ignored robotCode={} msgId={}",
                    msg.robotCode(), msg.msgId());
            return;
        }
        if (!msg.isTextMessage()) {
            log.info("dingtalk inbound unsupported msgType ignored robotCode={} msgId={} msgType={}",
                    msg.robotCode(), msg.msgId(), msg.msgType());
            return;
        }
        try {
            reactionService.markThinking(binding, msg.conversationId(), msg.msgId());
        } catch (Exception e) {
            log.warn("dingtalk inbound thinking reaction failed robotCode={} msgId={}: {}",
                    msg.robotCode(), msg.msgId(), e.getMessage());
        }
        conversationService.submitTurn(binding.getTenantId(), binding.getAgentId(), "DINGTALK",
                msg.conversationId(), msg.textContent(), msg.msgId(),
                DingTalkSourceContext.from(msg).toJson());
    }

    /** 供回调层解密 secret 做验签(按 robotCode 定位绑定)。 */
    public DingtalkRobotBindingDO bindingForVerification(String robotCode) {
        return bindingDao.findByRobotCodeGlobal(robotCode);
    }

    public String secretOf(DingtalkRobotBindingDO binding) {
        return bindingService.decryptSecret(binding);
    }
}
