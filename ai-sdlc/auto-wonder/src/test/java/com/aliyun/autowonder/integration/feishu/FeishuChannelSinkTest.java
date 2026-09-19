package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuChannelSinkTest {
    @Test void repliesThroughOriginalBindingAndFailsClosedOnRoutingMismatch() throws Exception {
        var f = new FeishuTestSupport(); var binding = f.create();
        var context = new FeishuSourceContext(binding.getId(), "cli_test", "oc_chat", "om_message", "ou_user", "");
        var conv = new AgentConversationDO(); conv.setId(10L); conv.setTenantId(1L); conv.setAgentId(7L); conv.setChannelConversationId(context.conversationId());
        var turn = new AgentConversationTurnDO(); turn.setConversationId(10L); turn.setSourceContext(f.json.writeValueAsString(context));
        var turns = mock(AgentConversationTurnDao.class); when(turns.findByExternalMsgId(1L, context.externalId())).thenReturn(turn);
        var api = mock(FeishuApiClient.class); var sink = new FeishuChannelSink(turns, f.dao, api, f.json);
        sink.deliverReply(conv, "hello", context.externalId());
        verify(api).reply(eq(binding), eq("om_message"), eq("hello"), eq(context.externalId()));
        clearInvocations(api);
        conv.setTenantId(2L);
        assertThrows(IllegalStateException.class, () -> sink.deliverReply(conv, "hello", context.externalId()));
        conv.setTenantId(1L); f.jdbc.update("UPDATE feishu_robot_binding SET agent_id=8");
        assertThrows(IllegalStateException.class, () -> sink.deliverReply(conv, "hello", context.externalId()));
        verifyNoInteractions(api);
    }
}
