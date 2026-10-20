package com.aliyun.autowonder.conversation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationChannelSinkRegistryTest {

    private ConversationChannelSink sink(String channel) {
        return new ConversationChannelSink() {
            @Override
            public String channel() {
                return channel;
            }

            @Override
            public void deliverReply(AgentConversationDO conv, String replyMarkdown,
                    String sourceExternalMsgId) {
            }
        };
    }

    @Test
    void findReturnsRegisteredSink() {
        ConversationChannelSink dingtalk = sink("DINGTALK");
        ConversationChannelSinkRegistry registry =
                new ConversationChannelSinkRegistry(List.of(dingtalk));

        assertSame(dingtalk, registry.find("DINGTALK"));
    }

    @Test
    void findReturnsNullForChannelWithoutSink() {
        ConversationChannelSinkRegistry registry =
                new ConversationChannelSinkRegistry(List.of(sink("DINGTALK")));

        assertNull(registry.find("WORKITEM_CLARIFICATION"));
    }
}
