package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.configuration.ThreadPoolManager;
import com.aliyun.autowonder.dispatch.DispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class DispatchDrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(DispatchDrainScheduler.class);

    private final DispatchService dispatchService;
    private final Executor executor;
    private final Set<Long> scheduledAgents = ConcurrentHashMap.newKeySet();

    @Autowired
    public DispatchDrainScheduler(DispatchService dispatchService) {
        this(dispatchService, ThreadPoolManager.asyncTaskThreadPool);
    }

    DispatchDrainScheduler(DispatchService dispatchService, Executor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    public void request(long agentId) {
        if (!scheduledAgents.add(agentId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    dispatchService.drainPending(agentId);
                } catch (RuntimeException e) {
                    log.warn("dispatch drain failed agentId={}", agentId, e);
                } finally {
                    scheduledAgents.remove(agentId);
                }
            });
        } catch (RejectedExecutionException e) {
            scheduledAgents.remove(agentId);
            log.warn("dispatch drain rejected agentId={}", agentId, e);
        }
    }
}
