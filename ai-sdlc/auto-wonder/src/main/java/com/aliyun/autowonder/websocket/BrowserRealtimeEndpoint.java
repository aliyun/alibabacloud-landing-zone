package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws")
public class BrowserRealtimeEndpoint {

    private static final Logger log = LoggerFactory.getLogger(BrowserRealtimeEndpoint.class);
    private static final ConcurrentHashMap<Session, Long> SESSION_TENANTS = new ConcurrentHashMap<>();

    private volatile boolean authenticated = false;

    @OnOpen
    public void onOpen(Session session) {
        log.info("browser realtime connected sessionId={}", session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JSONObject frame = JSON.parseObject(message);
            if (frame == null) {
                closeQuietly(session, "invalid frame");
                return;
            }
            String type = frame.getString("type");

            if (!authenticated) {
                if (!"auth".equals(type)) {
                    closeQuietly(session, "auth required");
                    return;
                }
                handleAuth(frame, session);
                return;
            }

            switch (type != null ? type : "") {
                case "subscribe":
                    handleSubscribe(frame, session);
                    break;
                case "unsubscribe":
                    handleUnsubscribe(frame, session);
                    break;
                default:
                    log.debug("browser realtime unknown frame type sessionId={} type={}", session.getId(), type);
                    break;
            }
        } catch (Exception e) {
            log.warn("browser realtime message failed sessionId={}", session.getId(), e);
        }
    }

    private void handleAuth(JSONObject frame, Session session) {
        String token = frame.getString("token");
        if (token == null || token.isBlank()) {
            closeQuietly(session, "missing token");
            return;
        }
        JwtService jwtService = WsSpringContext.getBean(JwtService.class);
        TokenPayload payload = jwtService.parse(token);
        if (payload.getCurrentWorkspaceId() == null) {
            closeQuietly(session, "missing tenant");
            return;
        }
        long tenantId = payload.getCurrentWorkspaceId();
        long userId = payload.getUserId() != null ? payload.getUserId() : 0L;
        SESSION_TENANTS.put(session, tenantId);
        authenticated = true;

        BrowserRealtimeSubscriberManager subscriberManager =
                WsSpringContext.getBean(BrowserRealtimeSubscriberManager.class);
        subscriberManager.recordPrincipal(session, tenantId, userId);

        log.info("browser realtime authenticated sessionId={} tenantId={} userId={}",
                session.getId(), tenantId, userId);
    }

    private void handleSubscribe(JSONObject frame, Session session) {
        String channel = frame.getString("channel");
        if (channel == null || channel.isBlank()) {
            return;
        }
        BrowserRealtimeSubscriberManager subscriberManager =
                WsSpringContext.getBean(BrowserRealtimeSubscriberManager.class);
        ConversationRealtimeAuthorizationService authService =
                WsSpringContext.getBean(ConversationRealtimeAuthorizationService.class);

        BrowserRealtimeSubscriberManager.PrincipalInfo principal = subscriberManager.getPrincipal(session);
        if (principal == null) {
            return;
        }
        if (!authService.authorize(principal.getTenantId(), principal.getUserId(), channel)) {
            log.warn("conversation subscription denied sessionId={} tenantId={} userId={} channel={}",
                    session.getId(), principal.getTenantId(), principal.getUserId(), channel);
            return;
        }
        subscriberManager.addSubscription(session, channel);
    }

    private void handleUnsubscribe(JSONObject frame, Session session) {
        String channel = frame.getString("channel");
        if (channel == null || channel.isBlank()) {
            return;
        }
        BrowserRealtimeSubscriberManager subscriberManager =
                WsSpringContext.getBean(BrowserRealtimeSubscriberManager.class);
        subscriberManager.removeSubscription(session, channel);
    }

    @OnClose
    public void onClose(Session session) {
        SESSION_TENANTS.remove(session);
        BrowserRealtimeSubscriberManager subscriberManager =
                WsSpringContext.safeGetBean(BrowserRealtimeSubscriberManager.class);
        if (subscriberManager != null) {
            subscriberManager.removeSession(session);
        }
        log.info("browser realtime disconnected sessionId={}", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            SESSION_TENANTS.remove(session);
            BrowserRealtimeSubscriberManager subscriberManager =
                    WsSpringContext.safeGetBean(BrowserRealtimeSubscriberManager.class);
            if (subscriberManager != null) {
                subscriberManager.removeSession(session);
            }
            log.warn("browser realtime error sessionId={}", session.getId(), error);
        } else {
            log.warn("browser realtime error", error);
        }
    }

    static void broadcast(long tenantId, String frameJson) {
        for (Session session : SESSION_TENANTS.keySet()) {
            if (!session.isOpen()) {
                SESSION_TENANTS.remove(session);
                continue;
            }
            Long sessionTenantId = SESSION_TENANTS.get(session);
            if (sessionTenantId == null || sessionTenantId != tenantId) {
                continue;
            }
            try {
                synchronized (session) {
                    session.getBasicRemote().sendText(frameJson);
                }
            } catch (Exception e) {
                SESSION_TENANTS.remove(session);
                log.warn("browser realtime send failed sessionId={}", session.getId(), e);
            }
        }
    }

    private static void closeQuietly(Session session, String reason) {
        try {
            SESSION_TENANTS.remove(session);
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (Exception ignore) {}
    }
}
