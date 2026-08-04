package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InAppWorkitemAssignedListenerTest {

    private NotifyService notifyService;
    private InAppWorkitemAssignedListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        listener = new InAppWorkitemAssignedListener(notifyService);
    }

    @Test
    void onAssignedSendsNotifyEventWithCorrectFields() {
        WorkitemHumanAssignedEvent event = new WorkitemHumanAssignedEvent(
                1L, 42L, "Fix login bug", 100L, 99L,
                "HUMAN", 5L, "Alice", "req-1");

        listener.onAssigned(event);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        NotifyEvent sent = captor.getValue();
        assertEquals(1L, sent.getTenantId());
        assertEquals("WORKITEM_ASSIGNED", sent.getType());
        assertEquals("有新工单指派给你", sent.getTitle());
        assertEquals("Fix login bug", sent.getContent());
        assertEquals("/workitems/42", sent.getLink());
        assertEquals("WORKITEM", sent.getRefType());
        assertEquals(42L, sent.getRefId());
        assertEquals(1, sent.getRecipientIds().size());
        assertEquals(99L, sent.getRecipientIds().get(0));
    }

    @Test
    void onAssignedDoesNotPropagateNotifyServiceException() {
        doThrow(new RuntimeException("db error")).when(notifyService).notify(any());
        WorkitemHumanAssignedEvent event = new WorkitemHumanAssignedEvent(
                1L, 42L, "title", 100L, 99L,
                "HUMAN", 5L, "Alice", "req-1");

        assertDoesNotThrow(() -> listener.onAssigned(event));
    }

    @Test
    void onAssignedDoesNotCheckDingtalkIdentityOrChannel() {
        WorkitemHumanAssignedEvent event = new WorkitemHumanAssignedEvent(
                1L, 42L, "title", 100L, 99L,
                "HUMAN", 5L, "Alice", "req-1");

        listener.onAssigned(event);

        verify(notifyService).notify(any());
    }
}
