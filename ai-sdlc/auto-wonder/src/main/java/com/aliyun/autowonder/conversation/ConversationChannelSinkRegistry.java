package com.aliyun.autowonder.conversation;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConversationChannelSinkRegistry {

    private final Map<String, ConversationChannelSink> byChannel = new HashMap<>();

    public ConversationChannelSinkRegistry(List<ConversationChannelSink> sinks) {
        for (ConversationChannelSink s : sinks) {
            byChannel.put(s.channel(), s);
        }
    }

    public ConversationChannelSink resolve(String channel) {
        ConversationChannelSink sink = byChannel.get(channel);
        if (sink == null) {
            throw new IllegalStateException("no sink for channel: " + channel);
        }
        return sink;
    }
}
