package com.aliyun.autowonder.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BrowserRealtimeSubscriberManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserRealtimeSubscriberManager.class);

    private static final ConcurrentHashMap<Session, PrincipalInfo> SESSION_PRINCIPALS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Session, Set<String>> SESSION_CHANNELS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<Session>> CHANNEL_SESSIONS = new ConcurrentHashMap<>();

    public void recordPrincipal(Session session, long tenantId, long userId) {
        SESSION_PRINCIPALS.put(session, new PrincipalInfo(tenantId, userId));
    }

    public PrincipalInfo getPrincipal(Session session) {
        return SESSION_PRINCIPALS.get(session);
    }

    public boolean addSubscription(Session session, String channel) {
        SESSION_CHANNELS.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).add(channel);
        CHANNEL_SESSIONS.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.debug("subscription added sessionId={} channel={}", session.getId(), channel);
        return true;
    }

    public void removeSubscription(Session session, String channel) {
        Set<String> channels = SESSION_CHANNELS.get(session);
        if (channels != null) {
            channels.remove(channel);
        }
        Set<Session> sessions = CHANNEL_SESSIONS.get(channel);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                CHANNEL_SESSIONS.remove(channel);
            }
        }
    }

    public Set<Session> getChannelSubscribers(String channel) {
        Set<Session> sessions = CHANNEL_SESSIONS.get(channel);
        if (sessions == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(sessions);
    }

    public void removeSession(Session session) {
        SESSION_PRINCIPALS.remove(session);
        Set<String> channels = SESSION_CHANNELS.remove(session);
        if (channels != null) {
            for (String channel : channels) {
                Set<Session> sessions = CHANNEL_SESSIONS.get(channel);
                if (sessions != null) {
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        CHANNEL_SESSIONS.remove(channel);
                    }
                }
            }
        }
    }

    public void deliverToChannel(String channel, String frameJson) {
        Set<Session> subscribers = CHANNEL_SESSIONS.get(channel);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Session session : subscribers) {
            if (!session.isOpen()) {
                removeSession(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.getBasicRemote().sendText(frameJson);
                }
            } catch (Exception e) {
                log.warn("subscriber delivery failed channel={} sessionId={}: {}",
                        channel, session.getId(), e.getMessage());
                removeSession(session);
            }
        }
    }

    public static class PrincipalInfo {
        private final long tenantId;
        private final long userId;

        public PrincipalInfo(long tenantId, long userId) {
            this.tenantId = tenantId;
            this.userId = userId;
        }

        public long getTenantId() {
            return tenantId;
        }

        public long getUserId() {
            return userId;
        }
    }
}
