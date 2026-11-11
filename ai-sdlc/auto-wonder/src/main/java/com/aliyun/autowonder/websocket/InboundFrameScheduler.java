package com.aliyun.autowonder.websocket;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.aliyun.autowonder.configuration.MdcAwareThreadPoolExecutor;
import com.aliyun.autowonder.configuration.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.websocket.CloseReason;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Preserve dispatch frame order without holding the WebSocket reader or unrelated ACKs. */
@Component
public class InboundFrameScheduler {
    private static final Logger log = LoggerFactory.getLogger(InboundFrameScheduler.class);
    private static final int MAX_PENDING = 256;
    private final SessionRegistry sessions;
    private final Executor results;
    private final Executor controls;
    private final Executor heartbeatTasks;
    private final int capacity;
    private final Map<Key, ArrayDeque<Pending>> queues = new HashMap<>();
    private int pendingCount;
    private int pendingHeartbeatCount;

    @Autowired
    public InboundFrameScheduler(SessionRegistry sessions) {
        this(sessions, pool("ws-result"), pool("ws-control"), ThreadPoolManager.asyncTaskThreadPool, MAX_PENDING);
    }

    InboundFrameScheduler(SessionRegistry sessions, Executor results, Executor controls,
            Executor heartbeatTasks, int capacity) {
        this.sessions = sessions;
        this.results = results;
        this.controls = controls;
        this.heartbeatTasks = heartbeatTasks;
        this.capacity = capacity;
    }

    private static ExecutorService pool(String name) {
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        return new MdcAwareThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_PENDING), new ThreadFactoryBuilder()
                .setNameFormat("autowonder-" + name + "-%d").setDaemon(true).build());
    }

    public boolean isCurrent(ExecutorSession session) {
        return sessions.isCurrent(session) && session.getSession().isOpen();
    }

    public void dispatch(ExecutorSession session, long dispatchId, String type, Runnable action) {
        submit(new Key(session.getTenantId(), dispatchId, false), session, type, action);
    }

    public void heartbeat(ExecutorSession session, Runnable action) {
        submit(new Key(session.getTenantId(), session.getExecutorId(), true), session, "HEARTBEAT", action);
    }

    private void submit(Key key, ExecutorSession session, String type, Runnable action) {
        if (!isCurrent(session)) return;
        Pending item = new Pending(session, type, action, System.nanoTime(), MDC.getCopyOfContextMap());
        boolean start = false;
        boolean full = false;
        synchronized (queues) {
            ArrayDeque<Pending> queue = queues.get(key);
            // Only the latest heartbeat side effects matter; liveness was already published.
            if (key.heartbeat() && queue != null && queue.size() > 1) {
                queue.removeLast();
                queue.addLast(item);
                return;
            }
            // Trace pressure must not consume admission for unrelated heartbeat side effects.
            if ((key.heartbeat() ? pendingHeartbeatCount : pendingCount) == capacity) {
                full = true;
            } else {
                if (queue == null) {
                    queue = new ArrayDeque<>();
                    queues.put(key, queue);
                    start = true;
                }
                queue.addLast(item);
                changePendingCount(key, 1);
            }
        }
        if (full) reject(session, key.heartbeat() ? 0 : key.id(), type);
        else if (start) schedule(key, item);
    }

    private void schedule(Key key, Pending item) {
        try {
            Executor executor = key.heartbeat() ? heartbeatTasks
                    : ("TASK_RESULT".equals(item.type()) ? results : controls);
            executor.execute(
                    MdcAwareThreadPoolExecutor.wrap(() -> run(key, item), item.context()));
        } catch (RejectedExecutionException unavailable) {
            List<Pending> rejected;
            synchronized (queues) {
                ArrayDeque<Pending> queue = queues.remove(key);
                rejected = queue == null ? List.of() : List.copyOf(queue);
                changePendingCount(key, -rejected.size());
            }
            rejected.stream().map(Pending::session).distinct()
                    .forEach(session -> reject(session, key.heartbeat() ? 0 : key.id(), item.type()));
        }
    }

    private void run(Key key, Pending item) {
        long started = System.nanoTime();
        try {
            if (isCurrent(item.session())) item.action().run();
        } catch (RuntimeException failure) {
            log.error("inbound frame handling failed executorId={} dispatchId={} type={}",
                    item.session().getExecutorId(), key.heartbeat() ? 0 : key.id(), item.type(), failure);
        } finally {
            long finished = System.nanoTime();
            if ("TASK_RESULT".equals(item.type()) || finished - started >= TimeUnit.SECONDS.toNanos(1)) {
                log.info("inbound frame finished executorId={} dispatchId={} type={} queueMs={} processingMs={}",
                        item.session().getExecutorId(), key.heartbeat() ? 0 : key.id(), item.type(),
                        TimeUnit.NANOSECONDS.toMillis(started - item.enqueued()),
                        TimeUnit.NANOSECONDS.toMillis(finished - started));
            }
            Pending next;
            synchronized (queues) {
                ArrayDeque<Pending> queue = queues.get(key);
                queue.removeFirst();
                changePendingCount(key, -1);
                next = queue.peekFirst();
                if (next == null) queues.remove(key);
            }
            if (next != null) schedule(key, next);
        }
    }

    private void changePendingCount(Key key, int delta) {
        if (key.heartbeat()) pendingHeartbeatCount += delta;
        else pendingCount += delta;
    }

    private void reject(ExecutorSession session, long dispatchId, String type) {
        // No receipt: both legacy and V2 Runtime keep unacknowledged results for replay.
        log.warn("inbound frame queue full executorId={} dispatchId={} type={}; closing session for replay",
                session.getExecutorId(), dispatchId, type);
        try {
            session.getSession().close(new CloseReason(CloseReason.CloseCodes.getCloseCode(1013),
                    "inbound processing busy; retry"));
        } catch (IOException failure) {
            log.debug("inbound overload close failed executorId={}", session.getExecutorId(), failure);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (results instanceof ExecutorService service) service.shutdown();
        if (controls instanceof ExecutorService service) service.shutdown();
    }

    private record Key(long tenantId, long id, boolean heartbeat) {}
    private record Pending(ExecutorSession session, String type, Runnable action, long enqueued,
                           Map<String, String> context) {}
}
