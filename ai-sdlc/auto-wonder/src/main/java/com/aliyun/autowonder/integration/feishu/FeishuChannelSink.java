package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.im.ImDeliveryFence;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

@Component
public class FeishuChannelSink implements ConversationChannelSink {

    private static final Logger LOG = LoggerFactory.getLogger(FeishuChannelSink.class);
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String MODE_SHARED = "SHARED";
    static final String DELIVERY_FAILED =
            "飞书回复失败，请检查应用权限、网络及原消息是否仍可回复";
    private static final Set<Integer> AUTH_BUSINESS_CODES =
            Set.of(-1, 10003, 10014, 99991661, 99991663, 99991668);

    private final AgentConversationTurnDao turns;
    private final FeishuBindingDao bindings;
    private final FeishuApiClient api;
    private final ObjectMapper json;
    private final PlatformImChannelConfigService imConfigs;
    private final ImDeliveryFence fence;

    public FeishuChannelSink(AgentConversationTurnDao turns, FeishuBindingDao bindings,
            FeishuApiClient api, ObjectMapper json) {
        this(turns, bindings, api, json, null, null);
    }

    public FeishuChannelSink(AgentConversationTurnDao turns, FeishuBindingDao bindings,
            FeishuApiClient api, ObjectMapper json, PlatformImChannelConfigService imConfigs) {
        this(turns, bindings, api, json, imConfigs, null);
    }

    @Autowired
    public FeishuChannelSink(AgentConversationTurnDao turns, FeishuBindingDao bindings,
            FeishuApiClient api, ObjectMapper json, PlatformImChannelConfigService imConfigs,
            ImDeliveryFence fence) {
        this.turns = turns;
        this.bindings = bindings;
        this.api = api;
        this.json = json;
        this.imConfigs = imConfigs;
        this.fence = fence;
    }

    @Override
    public String channel() {
        return "FEISHU";
    }

    @Override
    public void deliverReply(AgentConversationDO conv, String reply, String sourceExternalMsgId) {
        FeishuBinding[] target = new FeishuBinding[1];
        try {
            if (fence == null) {
                deliverInWindow(conv, reply, sourceExternalMsgId, false, target);
            } else {
                fence.run(() -> {
                    deliverInWindow(conv, reply, sourceExternalMsgId, true, target);
                    return null;
                });
            }
            writeHealth(target[0], null);
        } catch (ImDeliveryTargetChangedException e) {
            throw e;
        } catch (RuntimeException e) {
            // The fence transaction rolls back on failure, so the health write happens outside it.
            writeHealth(target[0], DELIVERY_FAILED);
            throw classify(e);
        }
    }

    private void writeHealth(FeishuBinding binding, String error) {
        if (binding == null) {
            return;
        }
        try {
            bindings.health(binding, error);
        } catch (RuntimeException e) {
            LOG.warn("feishu binding health write failed bindingId={} errorType={}",
                    binding.getId(), e.getClass().getSimpleName());
        }
    }

    private void deliverInWindow(AgentConversationDO conv, String reply,
            String sourceExternalMsgId, boolean fenced, FeishuBinding[] target) {
        var turn = turns.findByExternalMsgId(conv.getTenantId(), sourceExternalMsgId);
        if (turn == null || !conv.getId().equals(turn.getConversationId())) {
            throw new ImDeliveryTargetChangedException("飞书回复缺少原消息上下文");
        }
        final FeishuSourceContext source;
        try {
            source = json.readValue(turn.getSourceContext(), FeishuSourceContext.class);
        } catch (Exception e) {
            throw new ImDeliveryTargetChangedException("飞书消息上下文无效");
        }
        if (source == null || !source.externalId().equals(sourceExternalMsgId)
                || !source.conversationId().equals(conv.getChannelConversationId())) {
            throw new ImDeliveryTargetChangedException("飞书消息上下文不匹配");
        }
        var binding = fenced
                ? bindings.findForUpdate(conv.getTenantId(), source.bindingId())
                : bindings.find(conv.getTenantId(), source.bindingId());
        target[0] = binding;
        ImRouteSnapshot snapshot = source.route();
        PlatformImChannelConfigDO config = null;
        if (snapshot != null && snapshot.shared() && imConfigs != null) {
            // The locked read is the send fence: an identity change waits for this send to end.
            config = fenced ? imConfigs.lockGroupChatForDelivery("FEISHU")
                    : imConfigs.findGroupChatById(snapshot.platformConfigId());
        }
        String invalid = targetInvalid(conv, source, binding, config);
        if (invalid != null) {
            throw new ImDeliveryTargetChangedException(invalid);
        }
        try {
            if (snapshot != null && snapshot.shared()) {
                api.replyForConfig(config, source.messageId(), reply, sourceExternalMsgId);
            } else {
                api.reply(binding, source.messageId(), reply, sourceExternalMsgId);
            }
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    private String targetInvalid(AgentConversationDO conv, FeishuSourceContext source,
            FeishuBinding binding, PlatformImChannelConfigDO config) {
        if (binding == null) {
            return "飞书绑定已删除";
        }
        if (!Objects.equals(binding.getTenantId(), conv.getTenantId())) {
            return "飞书绑定空间已变更";
        }
        if (!STATUS_ENABLED.equals(binding.getStatus())) {
            return "飞书绑定已停用";
        }
        if (!Objects.equals(binding.getAgentId(), conv.getAgentId())) {
            return "飞书绑定数字人已变更";
        }
        ImRouteSnapshot snapshot = source.route();
        if (snapshot == null) {
            // Upgrade-era rows have no snapshot and keep the historical binding rule.
            if (isShared(binding)) {
                return "共享群聊绑定缺少路由快照，未投递";
            }
            if (!Objects.equals(binding.getAppId(), source.appId())) {
                return "飞书应用已变更";
            }
            return null;
        }
        if (!Objects.equals(binding.getId(), snapshot.bindingId())
                || !Objects.equals(binding.getTenantId(), snapshot.tenantId())
                || !Objects.equals(binding.getAgentId(), snapshot.agentId())) {
            return "飞书绑定已变更";
        }
        if (routeVersion(binding) != snapshot.bindingRouteVersion()) {
            return "飞书绑定路由已变更";
        }
        if (snapshot.shared()) {
            if (!isShared(binding)) {
                return "飞书绑定模式已变更";
            }
            if (!Objects.equals(binding.getPlatformConfigId(), snapshot.platformConfigId())) {
                return "共享群聊配置已变更";
            }
            if (!Objects.equals(binding.getGroupId(), snapshot.groupId())
                    || !Objects.equals(source.chatId(), snapshot.groupId())) {
                return "共享群聊目标群已变更";
            }
            if (!Objects.equals(binding.getAppId(), source.appId())) {
                return "飞书应用已变更";
            }
            return configInvalid(binding, snapshot, config);
        }
        if (isShared(binding)) {
            return "飞书绑定模式已变更";
        }
        if (!Objects.equals(binding.getAppId(), source.appId())) {
            return "飞书应用已变更";
        }
        return null;
    }

    private String configInvalid(FeishuBinding binding, ImRouteSnapshot snapshot,
            PlatformImChannelConfigDO config) {
        if (config == null) {
            return "共享群聊飞书机器人配置已删除";
        }
        if (!Objects.equals(config.getId(), snapshot.platformConfigId())) {
            return "共享群聊配置已变更";
        }
        if (!"FEISHU".equals(config.getProvider())) {
            return "共享群聊渠道已变更";
        }
        if (!Integer.valueOf(1).equals(config.getEnabled())) {
            return "共享群聊飞书机器人已停用";
        }
        if (routeVersion(config) != snapshot.configRouteVersion()) {
            return "共享群聊飞书机器人路由已变更";
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
        if (failure instanceof IllegalArgumentException) {
            return new ImDeliveryFailedException(messageOf(failure), failure);
        }
        if (failure instanceof FeishuApiException apiFailure) {
            if (apiFailure.isNetworkFailure()) {
                return new ImDeliveryUnknownException(messageOf(failure), failure);
            }
            Integer status = apiFailure.getHttpStatus();
            if (status != null) {
                if (status == 429) {
                    return new ImDeliveryRetryableException(messageOf(failure), failure);
                }
                if (status == 408 || status >= 500) {
                    return new ImDeliveryUnknownException(messageOf(failure), failure);
                }
                if (status >= 400) {
                    return new ImDeliveryFailedException(messageOf(failure), failure);
                }
            }
            Integer code = apiFailure.getBusinessCode();
            if (code != null && AUTH_BUSINESS_CODES.contains(code)) {
                return new ImDeliveryFailedException(messageOf(failure), failure);
            }
            if (code != null) {
                // A deterministic business rejection was definitely not delivered.
                return new ImDeliveryRetryableException(messageOf(failure), failure);
            }
            return new ImDeliveryUnknownException(messageOf(failure), failure);
        }
        String message = messageOf(failure);
        if (message.contains("共享群聊飞书机器人配置已变更")) {
            return new ImDeliveryTargetChangedException(message, failure);
        }
        if (message.contains("飞书返回无效访问凭证")
                || message.contains("飞书机器人未启用或无法读取机器人信息")
                || message.contains("无法读取飞书凭据")
                || message.contains("凭据不可用")) {
            return new ImDeliveryFailedException(message, failure);
        }
        return new ImDeliveryUnknownException(message, failure);
    }

    private String messageOf(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static boolean isShared(FeishuBinding binding) {
        return binding != null && MODE_SHARED.equals(binding.getBindingMode());
    }

    private static long routeVersion(FeishuBinding binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }
}
