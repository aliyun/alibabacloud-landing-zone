package com.aliyun.autowonder.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.websocket.CloseReason;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    public static final int EXECUTOR_REPLACED_CLOSE_CODE = 4001;
    public static final String EXECUTOR_REPLACED_REASON = "executor connection replaced";

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final ConcurrentHashMap<Long, ExecutorSession> byExecutorId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionIdToExecutorId = new ConcurrentHashMap<>();

    public void register(ExecutorSession es) {
        es.markReplacementRecoveryPending();
        ExecutorSession prev = byExecutorId.put(es.getExecutorId(), es);
        if (prev != null && prev != es) {
            sessionIdToExecutorId.remove(prev.getSession().getId());
            closeReplacedSession(prev);
            log.info("session replaced executorId={} oldSessionId={}", es.getExecutorId(), prev.getSession().getId());
        }
        sessionIdToExecutorId.put(es.getSession().getId(), es.getExecutorId());
        log.info("session register executorId={} sessionId={}", es.getExecutorId(), es.getSession().getId());
    }

    public ExecutorSession findByExecutorId(long executorId) {
        return byExecutorId.get(executorId);
    }

    public boolean isCurrent(ExecutorSession session) {
        return session != null && byExecutorId.get(session.getExecutorId()) == session;
    }

    public ExecutorSession findBySessionId(String sessionId) {
        Long exId = sessionIdToExecutorId.get(sessionId);
        return exId == null ? null : byExecutorId.get(exId);
    }

    public ExecutorSession removeBySessionId(String sessionId) {
        Long exId = sessionIdToExecutorId.remove(sessionId);
        if (exId == null) {
            return null;
        }
        ExecutorSession current = byExecutorId.get(exId);
        if (current != null && sessionId.equals(current.getSession().getId())
                && byExecutorId.remove(exId, current)) {
            log.info("session removed executorId={} sessionId={}", exId, sessionId);
            return current;
        }
        return null;
    }


    private void closeReplacedSession(ExecutorSession replaced) {
        try {
            replaced.getSession().close(new CloseReason(
                    CloseReason.CloseCodes.getCloseCode(EXECUTOR_REPLACED_CLOSE_CODE),
                    EXECUTOR_REPLACED_REASON));
        } catch (IOException closeError) {
            log.warn("failed to close replaced executor session executorId={} sessionId={}",
                    replaced.getExecutorId(), replaced.getSession().getId(), closeError);
        }
    }
}
