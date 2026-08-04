package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.ConversationChannelSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Date;
import java.util.List;

@Component
public class DingTalkChannelSink implements ConversationChannelSink {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannelSink.class);

    private final DingtalkRobotBindingDao bindingDao;
    private final DingTalkBindingService bindingService;
    private final DingTalkOutboundSender sender;
    private final DingTalkMessageReactionService reactionService;
    private final AgentConversationTurnDao turnDao;

    public DingTalkChannelSink(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, DingTalkOutboundSender sender,
            DingTalkMessageReactionService reactionService, AgentConversationTurnDao turnDao) {
        this.bindingDao = bindingDao;
        this.bindingService = bindingService;
        this.sender = sender;
        this.reactionService = reactionService;
        this.turnDao = turnDao;
    }

    @Override
    public String channel() {
        return "DINGTALK";
    }

    @Override
    public void deliverReply(AgentConversationDO conv, String replyMarkdown,
            String sourceExternalMsgId) {
        AgentConversationTurnDO sourceTurn = isBlank(sourceExternalMsgId) ? null
                : turnDao.findByExternalMsgId(conv.getTenantId(), sourceExternalMsgId);
        DingTalkSourceContext ctx = parseSourceContext(sourceTurn, sourceExternalMsgId);
        DingtalkRobotBindingDO binding = findBinding(conv, ctx);
        if (binding == null) {
            throw new IllegalStateException("no DingTalk binding for agent " + conv.getAgentId());
        }
        String secret = bindingService.decryptSecret(binding);
        String mention = mentionFor(ctx);
        String outgoing = mention == null ? replyMarkdown : mention + "\n\n" + replyMarkdown;
        // DingTalk at.atUserIds / msgParam.atUserIds requires the recipient's userId. In corporate
        // internal group callbacks the inbound payload carries senderStaffId (e.g. "220791"),
        // which is the sender's staff/user id and is the id DingTalk expects to trigger a system
        // "@" notification. senderId is a LWCP conversation id ("$:LWCP_v1:$...") and must NOT be
        // used. When senderStaffId is present, put it in atUserIds so the replied user actually
        // gets the system "@"; otherwise leave it empty.
        List<String> atUserIds = ctx != null && !isBlank(ctx.getSenderStaffId())
                ? List.of(ctx.getSenderStaffId())
                : List.of();
        long nowMs = System.currentTimeMillis();
        try {
            deliver(binding, secret, conv, ctx, outgoing, atUserIds, nowMs);
            reactionService.recallThinking(binding, conv.getChannelConversationId(),
                    sourceExternalMsgId);
            bindingService.markHealth(conv.getTenantId(), binding.getId(), new Date(), null);
        } catch (Exception e) {
            bindingService.markHealth(conv.getTenantId(), binding.getId(), null, e.getMessage());
            throw e;
        }
    }

    private void deliver(DingtalkRobotBindingDO binding, String secret, AgentConversationDO conv,
            DingTalkSourceContext ctx, String outgoing, List<String> atUserIds, long nowMs) {
        if (hasFreshSessionWebhook(ctx, nowMs)) {
            try {
                if (atUserIds == null || atUserIds.isEmpty()) {
                    sender.sendSessionWebhookMarkdown(ctx.getSessionWebhook(), outgoing, atUserIds);
                } else {
                    sender.sendSessionWebhookText(ctx.getSessionWebhook(), outgoing, atUserIds);
                }
                return;
            } catch (Exception e) {
                log.warn("dingtalk sessionWebhook send failed, fallback to proactive API robotCode={} conversationId={} msgId={} webhookPresent=true errorType={}",
                        binding.getRobotCode(), conv.getChannelConversationId(),
                        ctx.getMsgId(), e.getClass().getSimpleName());
            }
        }
        deliverProactive(binding, secret, conv, ctx, outgoing, atUserIds, nowMs);
    }

    private void deliverProactive(DingtalkRobotBindingDO binding, String secret,
            AgentConversationDO conv, DingTalkSourceContext ctx, String outgoing,
            List<String> atUserIds, long nowMs) {
        if (ctx != null && "1".equals(ctx.getConversationType())) {
            // 1:1 proactive fallback (sessionWebhook absent/expired):
            // /v1.0/robot/oToMessages/batchSend requires the recipient's open-platform userId,
            // which is NOT in the inbound callback (senderId is a LWCP conversation id,
            // senderStaffId is a staff number) and cannot be resolved without a contacts-scope
            // credential. Fail cleanly rather than silently send to an invalid id. A fresh
            // sessionWebhook (the common 1:1 path within its 1h validity) is handled above.
            throw new IllegalStateException(
                    "missing open-platform userId for DingTalk single chat reply");
        }
        if (isBlank(conv.getChannelConversationId())) {
            throw new IllegalStateException("DingTalk group reply requires channelConversationId");
        }
        if (atUserIds == null || atUserIds.isEmpty()) {
            sender.sendGroupMarkdown(binding.getAppKey(), secret, binding.getBaseUrl(),
                    binding.getRobotCode(), conv.getChannelConversationId(), outgoing, atUserIds, nowMs);
        } else {
            sender.sendGroupText(binding.getAppKey(), secret, binding.getBaseUrl(),
                    binding.getRobotCode(), conv.getChannelConversationId(), outgoing, atUserIds, nowMs);
        }
    }

    private DingTalkSourceContext parseSourceContext(AgentConversationTurnDO sourceTurn,
            String sourceExternalMsgId) {
        if (sourceTurn == null) {
            return null;
        }
        try {
            return DingTalkSourceContext.parse(sourceTurn.getSourceContext());
        } catch (Exception e) {
            log.warn("dingtalk source context parse failed, continue without context msgId={}: {}",
                    sourceExternalMsgId, e.getMessage());
            return null;
        }
    }

    private String mentionFor(DingTalkSourceContext ctx) {
        if (ctx == null) {
            return null;
        }
        // DingTalk markdown @ usually requires the body to carry an identifier matching the
        // atUserIds entry so the system "@" renders inline. Prefer senderStaffId (the staff/user
        // id from the inbound callback, e.g. "220791") to match atUserIds and trigger the system
        // "@"; fall back to the nick when senderStaffId is absent. Never use senderId — it is a
        // LWCP conversation id ("$:LWCP_v1:$...") and would render as a literal token.
        if (!isBlank(ctx.getSenderStaffId())) {
            return "@" + ctx.getSenderStaffId();
        }
        if (!isBlank(ctx.getSenderNick())) {
            return "@" + ctx.getSenderNick();
        }
        return null;
    }

    private boolean hasFreshSessionWebhook(DingTalkSourceContext ctx, long nowMs) {
        return ctx != null
                && !isBlank(ctx.getSessionWebhook())
                && ctx.getSessionWebhookExpiredTime() != null
                && ctx.getSessionWebhookExpiredTime() > nowMs + 5_000;
    }

    private DingtalkRobotBindingDO findBinding(AgentConversationDO conv, DingTalkSourceContext ctx) {
        if (ctx != null && !isBlank(ctx.getRobotCode())) {
            DingtalkRobotBindingDO byRobot =
                    bindingDao.findByRobotCode(conv.getTenantId(), ctx.getRobotCode());
            if (isEnabledBindingForAgent(byRobot, conv.getAgentId())) {
                return byRobot;
            }
        }
        return findBindingForAgent(conv.getTenantId(), conv.getAgentId());
    }

    private DingtalkRobotBindingDO findBindingForAgent(Long tenantId, Long agentId) {
        for (DingtalkRobotBindingDO b : bindingDao.listByTenant(tenantId)) {
            if (isEnabledBindingForAgent(b, agentId)) {
                return b;
            }
        }
        return null;
    }

    private boolean isEnabledBindingForAgent(DingtalkRobotBindingDO binding, Long agentId) {
        return binding != null
                && "ENABLED".equals(binding.getStatus())
                && (agentId == null || agentId.equals(binding.getAgentId()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
