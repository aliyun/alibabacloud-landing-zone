package com.aliyun.autowonder.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.util.List;
import java.util.Map;

import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.guidance.GuidanceService;

@ServerEndpoint("/ws/executor")
public class ExecutorWsEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ExecutorWsEndpoint.class);
    private static final int MAX_TEXT_MESSAGE_BYTES = 256 * 1024;

    private volatile ExecutorSession executorSession;

    @OnOpen
    public void onOpen(Session session) {
        configureMessageLimits(session);
        Map<String, List<String>> params = session.getRequestParameterMap();
        String token = firstParam(params, "token");
        String executorIdStr = firstParam(params, "executorId");
        int capacity = PresenceManager.normalizeCapacity(
                firstParam(params, "maxConcurrentDispatches"));

        if (token == null || executorIdStr == null) {
            log.info("executor auth failed reason=missing_params");
            closeQuietly(session, "missing token or executorId");
            return;
        }

        long executorId;
        try {
            executorId = Long.parseLong(executorIdStr);
        } catch (NumberFormatException e) {
            log.info("executor auth failed executorId={} reason=invalid_format", executorIdStr);
            closeQuietly(session, "invalid executorId");
            return;
        }

        ExecutorWsAuthenticator authenticator = WsSpringContext.getBean(ExecutorWsAuthenticator.class);
        ExecutorWsAuthenticator.AuthResult auth = authenticator.authenticate(executorId, token);
        if (!auth.isSuccess()) {
            log.info("executor auth failed executorId={} reason=auth_rejected", executorId);
            closeQuietly(session, "authentication failed");
            return;
        }

        executorSession = new ExecutorSession(auth.getExecutorId(), auth.getAgentId(),
                auth.getTenantId(), capacity, session);

        SessionRegistry registry = WsSpringContext.getBean(SessionRegistry.class);
        registry.register(executorSession);

        try {
            WsSpringContext.getBean(GuidanceService.class)
                    .redeliverUnacknowledged(auth.getTenantId(), auth.getExecutorId());
        } catch (RuntimeException ex) {
            log.warn("redeliver unacknowledged guidance failed executorId={}", auth.getExecutorId(), ex);
        }

        PresenceManager presence = WsSpringContext.getBean(PresenceManager.class);
        presence.register(auth.getExecutorId(), auth.getAgentId(), capacity);
        presence.announceSession(auth.getExecutorId(), session.getId());

        String clientIp = null;
        try {
            clientIp = ClientIpResolver.resolve(session);
            ExecutorService service = WsSpringContext.getBean(ExecutorService.class);
            service.recordLastConnectIp(auth.getExecutorId(), auth.getTenantId(), clientIp);
        } catch (RuntimeException ex) {
            log.warn("record executor ip failed executorId={}", auth.getExecutorId(), ex);
        }

        log.info("executor connected executorId={} agentId={} tenantId={} capacity={} ip={}",
                auth.getExecutorId(), auth.getAgentId(), auth.getTenantId(), capacity, clientIp);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        if (executorSession == null) {
            return;
        }
        SessionRegistry registry = WsSpringContext.getBean(SessionRegistry.class);
        if (!registry.isCurrent(executorSession)) {
            log.info("ws frame ignored from replaced session executorId={} sessionId={}",
                    executorSession.getExecutorId(), session.getId());
            return;
        }
        log.info("ws recv executorId={} size={}", executorSession.getExecutorId(), message.length());
        InboundFrameRouter router = WsSpringContext.getBean(InboundFrameRouter.class);
        routeSafely(executorSession, message, router);
    }

    static void routeSafely(ExecutorSession executorSession, String message,
            InboundFrameRouter router) {
        try {
            router.route(executorSession, message);
        } catch (RuntimeException error) {
            // An unhandled endpoint exception makes the WebSocket container close the
            // whole session with protocol code 1002. Leave retryable results
            // unacknowledged so the Runtime's durable outbox can replay them without
            // turning one bad frame into a reconnect storm.
            log.error("inbound frame handling failed executorId={} size={}",
                    executorSession.getExecutorId(), message == null ? 0 : message.length(), error);
        }
    }

    @OnClose
    public void onClose(Session session) {
        if (executorSession == null) {
            return;
        }
        SessionRegistry registry = WsSpringContext.getBean(SessionRegistry.class);
        ExecutorSession removed = registry.removeBySessionId(session.getId());
        if (removed != null) {
            PresenceManager presence = WsSpringContext.getBean(PresenceManager.class);
            if (presence.isCurrentSession(removed.getExecutorId(), session.getId())) {
                presence.unregister(removed.getExecutorId(), removed.getAgentId());
            } else {
                log.info("skip presence unregister for replaced session executorId={} sessionId={}",
                        removed.getExecutorId(), session.getId());
            }
        }

        log.info("executor disconnected executorId={}", executorSession.getExecutorId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        long exId = executorSession != null ? executorSession.getExecutorId() : -1;
        log.warn("WS error executorId={}", exId, error);
    }

    private static String firstParam(Map<String, List<String>> params, String key) {
        List<String> vals = params.get(key);
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : null;
    }

    static void configureMessageLimits(Session session) {
        session.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
    }

    private static void closeQuietly(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (Exception ignore) {}
    }
}
