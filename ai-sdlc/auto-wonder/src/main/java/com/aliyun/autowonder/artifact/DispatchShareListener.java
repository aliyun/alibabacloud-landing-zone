package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.DispatchSucceededEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The event reduces latency; the durable poll also handles restarts and missed/late events. */
@Component
public class DispatchShareListener {
    private final ArtifactShareRequestService requests;

    public DispatchShareListener(ArtifactShareRequestService requests) {
        this.requests = requests;
    }

    @Async
    @EventListener
    public void onDispatchSucceeded(DispatchSucceededEvent event) {
        requests.processPending(event.getDispatchId());
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void retryPending() {
        requests.processPending(null);
    }
}
