package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.ConversationChannelSink;
import com.aliyun.autowonder.conversation.ImDeliveryFailedException;
import com.aliyun.autowonder.conversation.ImDeliveryRetryableException;
import com.aliyun.autowonder.conversation.ImDeliveryTargetChangedException;
import com.aliyun.autowonder.conversation.ImDeliveryUnknownException;
import com.aliyun.autowonder.im.ImDeliveryFence;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Component
public class DingTalkChannelSink implements ConversationChannelSink {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannelSink.class);
    private static final String MODE_LEGACY = "LEGACY";
    private static final String MODE_SHARED = "SHARED";
    private static final String STATUS_ENABLED = "ENABLED";

    private final DingtalkRobotBindingDao bindingDao;
    private final DingTalkBindingService bindingService;
    private final DingTalkOutboundSender sender;
    private final DingTalkMessageReactionService reactionService;
    private final AgentConversationTurnDao turnDao;
    private final ObjectMapper json;
    private final PlatformImChannelConfigService imConfigs;
    private final ImDeliveryFence fence;

    public DingTalkChannelSink(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, DingTalkOutboundSender sender,
            DingTalkMessageReactionService reactionService, AgentConversationTurnDao turnDao) {
        this(bindingDao, bindingService, sender, reactionService, turnDao, null, null, null);
    }

    public DingTalkChannelSink(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, DingTalkOutboundSender sender,
            DingTalkMessageReactionService reactionService, AgentConversationTurnDao turnDao,
            ObjectMapper json, PlatformImChannelConfigService imConfigs) {
        this(bindingDao, bindingService, sender, reactionService, turnDao, json, imConfigs, null);
    }

    @Autowired
    public DingTalkChannelSink(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, DingTalkOutboundSender sender,
            DingTalkMessageReactionService reactionService, AgentConversationTurnDao turnDao,
            ObjectMapper json, PlatformImChannelConfigService imConfigs, ImDeliveryFence fence) {
        this.bindingDao = bindingDao;
        this.bindingService = bindingService;
        this.sender = sender;
        this.reactionService = reactionService;
        this.turnDao = turnDao;
        this.json = json;
        this.imConfigs = imConfigs;
        this.fence = fence;
    }

    @Override
    public String channel() {
        return "DINGTALK";
    }

    @Override
    public void deliverReply(AgentConversationDO conv, String replyMarkdown,
            String sourceExternalMsgId) {
        DingtalkRobotBindingDO[] target = new DingtalkRobotBindingDO[1];
        try {
            if (fence == null) {
                deliverInWindow(conv, replyMarkdown, sourceExternalMsgId, false, target);
            } else {
                fence.run(() -> {
                    deliverInWindow(conv, replyMarkdown, sourceExternalMsgId, true, target);
                    return null;
                });
            }
            writeHealth(conv, target[0], null);
        } catch (ImDeliveryTargetChangedException e) {
            throw e;
        } catch (RuntimeException e) {
            // The fence transaction rolls back on failure, so health diagnostics are written
            // outside it or the operator would never see why the reply failed.
            writeHealth(conv, target[0], messageOf(e));
            throw classify(e);
        }
    }

    private void writeHealth(AgentConversationDO conv, DingtalkRobotBindingDO binding,
            String error) {
        if (binding == null) {
            return;
        }
        try {
            bindingService.markHealth(conv.getTenantId(), binding.getId(),
                    error == null ? new Date() : null, error);
        } catch (RuntimeException e) {
            log.warn("dingtalk binding health write failed bindingId={} errorType={}",
                    binding.getId(), e.getClass().getSimpleName());
        }
    }

    private void deliverInWindow(AgentConversationDO conv, String replyMarkdown,
            String sourceExternalMsgId, boolean fenced, DingtalkRobotBindingDO[] target) {
        AgentConversationTurnDO sourceTurn = isBlank(sourceExternalMsgId) ? null
                : turnDao.findByExternalMsgId(conv.getTenantId(), sourceExternalMsgId);
        DingTalkSourceContext ctx = parseSourceContext(sourceTurn, sourceExternalMsgId);
        ImRouteSnapshot snapshot = parseSnapshot(ctx, sourceExternalMsgId);
        DingtalkRobotBindingDO binding = findBinding(conv, ctx, snapshot, fenced);
        if (binding == null) {
            throw new ImDeliveryTargetChangedException(
                    "no DingTalk binding for agent " + conv.getAgentId());
        }
        target[0] = binding;
        PlatformImChannelConfigDO sharedConfig = snapshot != null && snapshot.shared()
                && imConfigs != null
                ? (fenced ? imConfigs.lockGroupChatForDelivery("DINGTALK")
                        : imConfigs.findGroupChatById(snapshot.platformConfigId()))
                : null;
        if (snapshot != null) {
            String invalid = targetInvalid(conv, ctx, binding, snapshot, sharedConfig);
            if (invalid != null) {
                throw new ImDeliveryTargetChangedException(invalid);
            }
        } else if (isShared(binding)) {
            throw new ImDeliveryTargetChangedException("共享群聊绑定缺少路由快照，未投递");
        }
        boolean shared = snapshot != null && snapshot.shared();
        String mention = mentionFor(ctx);
        String outgoing = mention == null ? replyMarkdown : mention + "\n\n" + replyMarkdown;
        // DingTalk at.atUserIds / msgParam.atUserIds requires the recipient's userId. In corporate
        // internal group callbacks the inbound payload carries senderStaffId (e.g. "220791"),
        // which is the sender's staff id and is the id DingTalk expects to trigger a system
        // "@" notification. senderId is a LWCP conversation id and must NOT be used.
        List<String> atUserIds = ctx != null && !isBlank(ctx.getSenderStaffId())
                ? List.of(ctx.getSenderStaffId())
                : List.of();
        long nowMs = System.currentTimeMillis();
        try {
            String secret = bindingService.decryptSecret(binding);
            if (shared) {
                deliverShared(binding, sharedConfig, secret, conv, ctx, snapshot, outgoing,
                        atUserIds, nowMs);
            } else {
                deliver(binding, secret, conv, ctx, outgoing, atUserIds, nowMs);
                reactionService.recallThinking(binding, conv.getChannelConversationId(),
                        sourceExternalMsgId);
            }
        } catch (ImDeliveryTargetChangedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    private void deliver(DingtalkRobotBindingDO binding, String secret, AgentConversationDO conv,
            DingTalkSourceContext ctx, String outgoing, List<String> atUserIds, long nowMs) {
        if (hasFreshSessionWebhook(ctx, nowMs)) {
            try {
                deliverBySessionWebhook(ctx, outgoing, atUserIds);
                return;
            } catch (RuntimeException e) {
                if (isAmbiguousWebhookFailure(e)) {
                    // The webhook may have delivered and DingTalk has no idempotency key, so the
                    // outcome must stay UNKNOWN even for a 429 that classify() maps to retryable.
                    throw new ImDeliveryUnknownException(
                            "dingtalk sessionWebhook reply outcome unknown: " + messageOf(e), e);
                }
                log.warn("dingtalk sessionWebhook send failed, fallback to proactive API robotCode={} conversationId={} msgId={} webhookPresent=true errorType={}",
                        binding.getRobotCode(), conv.getChannelConversationId(),
                        ctx.getMsgId(), e.getClass().getSimpleName());
            }
        }
        deliverProactive(binding, secret, conv, ctx, outgoing, atUserIds, nowMs);
    }

    private void deliverShared(DingtalkRobotBindingDO binding,
            PlatformImChannelConfigDO sharedConfig, String secret,
            AgentConversationDO conv, DingTalkSourceContext ctx, ImRouteSnapshot snapshot,
            String outgoing, List<String> atUserIds, long nowMs) {
        if (hasFreshSessionWebhook(ctx, nowMs)) {
            try {
                deliverBySessionWebhook(ctx, outgoing, atUserIds);
                return;
            } catch (RuntimeException e) {
                if (isAmbiguousWebhookFailure(e)) {
                    // Never resend through the group API after an ambiguous webhook outcome.
                    throw new ImDeliveryUnknownException(
                            "dingtalk shared sessionWebhook reply outcome unknown: "
                                    + messageOf(e), e);
                }
                log.warn("dingtalk shared sessionWebhook send failed, fallback to group robotCode={} bindingId={} errorType={}",
                        sharedConfig == null ? binding.getRobotCode() : sharedConfig.getRobotCode(),
                        snapshot.bindingId(), e.getClass().getSimpleName());
            }
        }
        // Replies must target the confirmed group, never the internal binding-prefixed
        // conversation id used to keep shared conversations apart.
        sendGroup(binding, sharedConfig, secret, snapshot.groupId(), outgoing, atUserIds, nowMs);
    }

    private void deliverBySessionWebhook(DingTalkSourceContext ctx, String outgoing,
            List<String> atUserIds) {
        if (atUserIds == null || atUserIds.isEmpty()) {
            sender.sendSessionWebhookMarkdown(ctx.getSessionWebhook(), outgoing, atUserIds);
        } else {
            sender.sendSessionWebhookText(ctx.getSessionWebhook(), outgoing, atUserIds);
        }
    }

    private void deliverProactive(DingtalkRobotBindingDO binding, String secret,
            AgentConversationDO conv, DingTalkSourceContext ctx, String outgoing,
            List<String> atUserIds, long nowMs) {
        if (ctx != null && "1".equals(ctx.getConversationType())) {
            // 1:1 proactive fallback (sessionWebhook absent/expired):
            // /v1.0/robot/oToMessages/batchSend requires the recipient's open-platform userId,
            // which is NOT in the inbound callback (senderId is a LWCP conversation id,
            // senderStaffId is a staff number) and cannot be resolved without a contacts-scope
            // credential. Fail cleanly rather than silently send to an invalid id.
            throw new IllegalStateException(
                    "missing open-platform userId for DingTalk single chat reply");
        }
        if (isBlank(conv.getChannelConversationId())) {
            throw new IllegalStateException("DingTalk group reply requires channelConversationId");
        }
        sendGroup(binding, null, secret, conv.getChannelConversationId(), outgoing, atUserIds, nowMs);
    }

    private void sendGroup(DingtalkRobotBindingDO binding,
            PlatformImChannelConfigDO sharedConfig, String secret, String openConversationId,
            String outgoing, List<String> atUserIds, long nowMs) {
        // A shared binding follows the platform configuration in place: its row keeps only the
        // creation-time identity, so sending with that snapshot would fail after the app changed.
        String appKey = sharedConfig == null ? binding.getAppKey() : sharedConfig.getAppKey();
        String baseUrl = sharedConfig != null && !isBlank(sharedConfig.getBaseUrl())
                ? sharedConfig.getBaseUrl() : binding.getBaseUrl();
        String robotCode = sharedConfig == null ? binding.getRobotCode() : sharedConfig.getRobotCode();
        if (atUserIds == null || atUserIds.isEmpty()) {
            sender.sendGroupMarkdown(appKey, secret, baseUrl, robotCode, openConversationId,
                    outgoing, atUserIds, nowMs);
        } else {
            sender.sendGroupText(appKey, secret, baseUrl, robotCode, openConversationId,
                    outgoing, atUserIds, nowMs);
        }
    }

    private ImRouteSnapshot parseSnapshot(DingTalkSourceContext ctx, String sourceExternalMsgId) {
        if (ctx == null || isBlank(ctx.getRouteSnapshot())) {
            return null;
        }
        ImRouteSnapshot snapshot = json == null ? null
                : ImRouteSnapshot.parse(json, ctx.getRouteSnapshot());
        if (snapshot == null) {
            throw new ImDeliveryTargetChangedException("钉钉路由快照无效，未投递");
        }
        return snapshot;
    }

    private String targetInvalid(AgentConversationDO conv, DingTalkSourceContext ctx,
            DingtalkRobotBindingDO binding, ImRouteSnapshot snapshot,
            PlatformImChannelConfigDO sharedConfig) {
        if (!Objects.equals(binding.getId(), snapshot.bindingId())) {
            return "钉钉绑定已变更";
        }
        if (!Objects.equals(binding.getTenantId(), snapshot.tenantId())
                || !Objects.equals(binding.getTenantId(), conv.getTenantId())) {
            return "钉钉绑定空间已变更";
        }
        if (!Objects.equals(binding.getAgentId(), snapshot.agentId())
                || !Objects.equals(binding.getAgentId(), conv.getAgentId())) {
            return "钉钉绑定数字人已变更";
        }
        if (!STATUS_ENABLED.equals(binding.getStatus())) {
            return "钉钉绑定已停用";
        }
        if (routeVersion(binding) != snapshot.bindingRouteVersion()) {
            return "钉钉绑定路由已变更";
        }
        if (snapshot.shared()) {
            if (!MODE_SHARED.equals(binding.getBindingMode())) {
                return "钉钉绑定模式已变更";
            }
            if (!Objects.equals(binding.getPlatformConfigId(), snapshot.platformConfigId())) {
                return "共享群聊配置已变更";
            }
            if (!Objects.equals(binding.getGroupId(), snapshot.groupId())) {
                return "共享群聊目标群已变更";
            }
            return configInvalid(snapshot, sharedConfig);
        }
        if (MODE_SHARED.equals(binding.getBindingMode())) {
            return "钉钉绑定模式已变更";
        }
        if (!MODE_LEGACY.equals(binding.getBindingMode())
                && binding.getBindingMode() != null) {
            return "钉钉绑定模式无效";
        }
        if (ctx != null && !isBlank(ctx.getRobotCode())
                && !ctx.getRobotCode().equals(binding.getRobotCode())) {
            return "钉钉机器人已变更";
        }
        return null;
    }

    private String configInvalid(ImRouteSnapshot snapshot, PlatformImChannelConfigDO config) {
        if (config == null) {
            return "共享群聊机器人配置已删除";
        }
        if (!Objects.equals(config.getId(), snapshot.platformConfigId())) {
            return "共享群聊配置已变更";
        }
        if (!"DINGTALK".equals(config.getProvider())) {
            return "共享群聊渠道已变更";
        }
        if (!Integer.valueOf(1).equals(config.getEnabled())) {
            return "共享群聊机器人已停用";
        }
        if (routeVersion(config) != snapshot.configRouteVersion()) {
            return "共享群聊机器人路由已变更";
        }
        return null;
    }

    private RuntimeException classify(RuntimeException failure) {
        if (failure instanceof ImDeliveryFailedException
                || failure instanceof ImDeliveryRetryableException
                || failure instanceof ImDeliveryUnknownException
                || failure instanceof ImDeliveryTargetChangedException) {
            return failure;
        }
        if (failure instanceof DingTalkHttpException http) {
            int status = http.getStatus();
            if (status == 429) {
                return new ImDeliveryRetryableException(http.getMessage(), http);
            }
            if (status >= 400 && status < 500 && status != 408) {
                return new ImDeliveryFailedException(http.getMessage(), http);
            }
            return new ImDeliveryUnknownException(http.getMessage(), http);
        }
        if (hasIoCause(failure)) {
            return new ImDeliveryUnknownException(messageOf(failure), failure);
        }
        String message = messageOf(failure);
        if (message.contains("共享群聊机器人配置已变更")) {
            return new ImDeliveryTargetChangedException(message, failure);
        }
        if (message.contains("共享群聊机器人凭据不可用")
                || message.contains("access token response is incomplete")) {
            return new ImDeliveryFailedException(message, failure);
        }
        return new ImDeliveryUnknownException(message, failure);
    }

    private boolean isAmbiguousWebhookFailure(RuntimeException failure) {
        if (failure instanceof DingTalkHttpException http) {
            int status = http.getStatus();
            return status == 408 || status == 429 || status >= 500;
        }
        if (hasIoCause(failure)) {
            return true;
        }
        String message = messageOf(failure);
        return message.contains("DingTalk request transport failed");
    }

    private boolean hasIoCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private String messageOf(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
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

    private DingtalkRobotBindingDO findBinding(AgentConversationDO conv, DingTalkSourceContext ctx,
            ImRouteSnapshot snapshot, boolean fenced) {
        if (snapshot != null) {
            return fenced
                    ? bindingDao.findByIdForUpdate(conv.getTenantId(), snapshot.bindingId())
                    : bindingDao.findById(conv.getTenantId(), snapshot.bindingId());
        }
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
                && STATUS_ENABLED.equals(binding.getStatus())
                && (agentId == null || agentId.equals(binding.getAgentId()));
    }

    private boolean isShared(DingtalkRobotBindingDO binding) {
        return binding != null && MODE_SHARED.equals(binding.getBindingMode());
    }

    private static long routeVersion(DingtalkRobotBindingDO binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
