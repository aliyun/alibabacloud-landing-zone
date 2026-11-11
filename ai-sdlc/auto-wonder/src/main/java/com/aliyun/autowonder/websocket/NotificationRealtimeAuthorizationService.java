package com.aliyun.autowonder.websocket;

import org.springframework.stereotype.Service;

/**
 * 通知主题按「工作空间 + 收件人」隔离：只允许 principal 自己订阅本人当前工作空间的 channel，
 * 不信任浏览器自报的归属，也不向整个工作空间广播通知内容。
 */
@Service
public class NotificationRealtimeAuthorizationService implements RealtimeChannelAuthorizationService {

    static final String PREFIX = "notification:";

    @Override
    public boolean supports(String channel) {
        return channel != null && channel.startsWith(PREFIX);
    }

    @Override
    public boolean authorize(long workspaceId, long userId, String channel) {
        if (!supports(channel) || workspaceId <= 0 || userId <= 0) {
            return false;
        }
        String subject = channel.substring(PREFIX.length());
        int separator = subject.indexOf(':');
        if (separator <= 0 || separator == subject.length() - 1) {
            return false;
        }
        final long channelWorkspaceId;
        final long channelUserId;
        try {
            channelWorkspaceId = Long.parseLong(subject.substring(0, separator));
            channelUserId = Long.parseLong(subject.substring(separator + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        return channelWorkspaceId == workspaceId && channelUserId == userId;
    }
}
