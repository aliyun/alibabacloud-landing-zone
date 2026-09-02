package com.aliyun.autowonder.websocket;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class BrowserRealtimeAuthorizationService {
    private final List<RealtimeChannelAuthorizationService> delegates;
    public BrowserRealtimeAuthorizationService(List<RealtimeChannelAuthorizationService> delegates) { this.delegates = delegates; }
    public boolean authorize(long workspaceId, long userId, String channel) {
        return delegates.stream().filter(delegate -> delegate.supports(channel))
                .anyMatch(delegate -> delegate.authorize(workspaceId, userId, channel));
    }
}
