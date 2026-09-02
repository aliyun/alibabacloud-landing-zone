package com.aliyun.autowonder.websocket;

/** Authorizes one browser-visible realtime channel family. */
public interface RealtimeChannelAuthorizationService {
    boolean supports(String channel);
    boolean authorize(long workspaceId, long userId, String channel);
}
