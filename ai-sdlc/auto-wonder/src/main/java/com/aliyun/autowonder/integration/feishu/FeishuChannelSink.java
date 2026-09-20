package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class FeishuChannelSink implements ConversationChannelSink {
    private final AgentConversationTurnDao turns;
    private final FeishuBindingDao bindings;
    private final FeishuApiClient api;
    private final ObjectMapper json;
    public FeishuChannelSink(AgentConversationTurnDao turns, FeishuBindingDao bindings, FeishuApiClient api, ObjectMapper json) {
        this.turns = turns; this.bindings = bindings; this.api = api; this.json = json;
    }
    @Override public String channel() { return "FEISHU"; }
    @Override public void deliverReply(AgentConversationDO conv, String reply, String sourceExternalMsgId) {
        var turn = turns.findByExternalMsgId(conv.getTenantId(), sourceExternalMsgId);
        if (turn == null || !conv.getId().equals(turn.getConversationId())) throw new IllegalStateException("飞书回复缺少原消息上下文");
        final FeishuSourceContext source;
        try { source = json.readValue(turn.getSourceContext(), FeishuSourceContext.class); }
        catch (Exception e) { throw new IllegalStateException("飞书消息上下文无效"); }
        if (source == null || !source.externalId().equals(sourceExternalMsgId) || !source.conversationId().equals(conv.getChannelConversationId())) throw new IllegalStateException("飞书消息上下文不匹配");
        var binding = bindings.find(conv.getTenantId(), source.bindingId());
        if (binding == null || !"ENABLED".equals(binding.getStatus()) || !binding.getAgentId().equals(conv.getAgentId()) || !binding.getAppId().equals(source.appId())) {
            throw new IllegalStateException("飞书绑定已停用、删除或变更");
        }
        try {
            api.reply(binding, source.messageId(), reply, sourceExternalMsgId);
            bindings.health(binding, null);
        } catch (Exception e) {
            bindings.health(binding, "飞书回复失败，请检查应用权限、网络及原消息是否仍可回复");
            throw e;
        }
    }
}
