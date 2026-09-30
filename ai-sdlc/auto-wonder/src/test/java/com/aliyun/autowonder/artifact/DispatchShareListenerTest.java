package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.DispatchSucceededEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.event.DefaultEventListenerFactory;
import static org.mockito.Mockito.*;

class DispatchShareListenerTest {
    @Test
    void terminalEventWorksWithoutTransactionAndPollRecoversMissedEvents() {
        ArtifactShareRequestService requests = mock(ArtifactShareRequestService.class);
        try (var context = new GenericApplicationContext()) {
            context.registerBean(EventListenerMethodProcessor.class);
            context.registerBean(DefaultEventListenerFactory.class);
            context.registerBean(DispatchShareListener.class, () -> new DispatchShareListener(requests));
            context.refresh();
            context.publishEvent(new DispatchSucceededEvent(100L, 99L, 6L, 7L));
            verify(requests).processPending(6L);
            context.getBean(DispatchShareListener.class).retryPending();
            verify(requests).processPending(null);
        }
    }
}
