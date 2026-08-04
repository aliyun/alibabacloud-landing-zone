package com.aliyun.autowonder.guidance;

import com.aliyun.autowonder.dispatch.DispatchService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts a side interaction only after its guidance row is visible to ACK handlers. */
@Component
public class GuidanceDispatchQueuedListener {
    private final DispatchService dispatchService;

    public GuidanceDispatchQueuedListener(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(GuidanceDispatchQueuedEvent event) {
        dispatchService.runPending(event.dispatchId());
    }
}
