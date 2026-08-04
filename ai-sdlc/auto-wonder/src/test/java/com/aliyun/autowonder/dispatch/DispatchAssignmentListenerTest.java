package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class DispatchAssignmentListenerTest {

    private DispatchService dispatchService;
    private DispatchAssignmentListener listener;

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        listener = new DispatchAssignmentListener(dispatchService);
    }

    @Test
    void enqueuesAndRunsWhenStepAndAgentPresent() {
        DispatchDO d = new DispatchDO();
        d.setId(9001L);
        when(dispatchService.enqueueAssignment(7L, 100L, 5L, 42L, 8, 3L)).thenReturn(d);

        listener.onWorkitemAssigned(new WorkitemAssignedEvent(7L, 100L, 5L, 42L, 8, 3L));

        verify(dispatchService).enqueueAssignment(7L, 100L, 5L, 42L, 8, 3L);
        verify(dispatchService).runPending(9001L);
    }

    @Test
    void skipsWhenStepIdNull() {
        listener.onWorkitemAssigned(new WorkitemAssignedEvent(7L, 100L, null, 42L, 8, 3L));
        verifyNoInteractions(dispatchService);
    }

    @Test
    void skipsWhenAgentIdNull() {
        listener.onWorkitemAssigned(new WorkitemAssignedEvent(7L, 100L, 5L, null, 8, 3L));
        verifyNoInteractions(dispatchService);
    }
}
