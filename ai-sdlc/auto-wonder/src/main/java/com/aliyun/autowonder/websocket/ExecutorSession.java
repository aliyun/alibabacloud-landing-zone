package com.aliyun.autowonder.websocket;

import lombok.Getter;
import javax.websocket.Session;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class ExecutorSession {
    private final long executorId;
    private final long agentId;
    private final long tenantId;
    private final int maxConcurrentDispatches;
    private final Session session;
    private final AtomicBoolean replacementRecoveryPending = new AtomicBoolean();

    public ExecutorSession(long executorId, long agentId, long tenantId, Session session) {
        this(executorId, agentId, tenantId, 1, session);
    }

    public ExecutorSession(long executorId, long agentId, long tenantId,
            int maxConcurrentDispatches, Session session) {
        this.executorId = executorId;
        this.agentId = agentId;
        this.tenantId = tenantId;
        this.maxConcurrentDispatches = PresenceManager.normalizeCapacity(
                String.valueOf(maxConcurrentDispatches));
        this.session = session;
    }

    /** Serialize every outbound frame for this WebSocket session. */
    public void sendText(String message) throws IOException {
        synchronized (session) {
            session.getBasicRemote().sendText(message);
        }
    }

    void markReplacementRecoveryPending() {
        replacementRecoveryPending.set(true);
    }

    public boolean consumeReplacementRecoveryPending() {
        return replacementRecoveryPending.compareAndSet(true, false);
    }
}
