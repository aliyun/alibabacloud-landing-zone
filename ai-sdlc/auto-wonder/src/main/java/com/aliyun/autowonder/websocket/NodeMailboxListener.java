package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.websocket.CloseReason;

@Component
public class NodeMailboxListener {

    private static final Logger log = LoggerFactory.getLogger(NodeMailboxListener.class);

    private final SessionRegistry sessionRegistry;
    private final PresenceManager presenceManager;

    @Autowired
    public NodeMailboxListener(SessionRegistry sessionRegistry, PresenceManager presenceManager) {
        this.sessionRegistry = sessionRegistry;
        this.presenceManager = presenceManager;
    }

    public NodeMailboxListener(SessionRegistry sessionRegistry) {
        this(sessionRegistry, null);
    }

    public void onMessage(String channel, String message) {
        try {
            JSONObject json = JSON.parseObject(message);
            if (json == null) {
                return;
            }
            Long executorId = json.getLong("executorId");
            if (executorId == null) {
                return;
            }
            String type = json.getString("type");
            if ("SESSION_CLOSE".equals(type)) {
                closeLocalSession(executorId);
                return;
            }
            if ("SESSION_REPLACED".equals(type)) {
                closeReplacedLocalSession(executorId);
                return;
            }
            log.info("mailbox broadcast received executorId={}", executorId);
            ExecutorSession es = sessionRegistry.findByExecutorId(executorId);
            if (es == null || !es.getSession().isOpen()) {
                return;
            }
            es.sendText(message);
            log.info("mailbox delivered executorId={}", executorId);
        } catch (Exception e) {
            log.warn("mailbox delivery failed", e);
        }
    }

    private void closeLocalSession(long executorId) {
        ExecutorSession es = sessionRegistry.findByExecutorId(executorId);
        if (es == null) {
            return;
        }
        try {
            if (es.getSession().isOpen()) {
                es.getSession().close();
                log.info("session closed via broadcast SESSION_CLOSE executorId={}", executorId);
            }
            if (presenceManager != null) {
                presenceManager.unregister(executorId, es.getAgentId());
            }
        } catch (Exception e) {
            log.warn("failed to close session via broadcast executorId={}", executorId, e);
        }
    }

    private void closeReplacedLocalSession(long executorId) {
        ExecutorSession es = sessionRegistry.findByExecutorId(executorId);
        if (es == null || presenceManager == null) {
            return;
        }
        String currentSessionId = presenceManager.currentSessionId(executorId);
        if (currentSessionId == null || currentSessionId.equals(es.getSession().getId())) {
            return;
        }
        try {
            if (es.getSession().isOpen()) {
                es.getSession().close(new CloseReason(
                        CloseReason.CloseCodes.getCloseCode(
                                SessionRegistry.EXECUTOR_REPLACED_CLOSE_CODE),
                        SessionRegistry.EXECUTOR_REPLACED_REASON));
                log.info("replaced session closed via broadcast executorId={} oldSessionId={}",
                        executorId, es.getSession().getId());
            }
        } catch (Exception e) {
            log.warn("failed to close replaced session via broadcast executorId={}", executorId, e);
        }
    }
}
