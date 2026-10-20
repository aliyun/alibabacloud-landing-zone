package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.workitem.WorkitemDeletedEvent;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClarificationAttachmentCleanupTest {

    private final ClarificationAttachmentService service =
            mock(ClarificationAttachmentService.class);

    @Test
    void listenerDeletesWorkitemAttachmentsAfterCommit() {
        new ClarificationAttachmentCleanupListener(service)
                .onWorkitemDeleted(new WorkitemDeletedEvent(1L, 10011L));

        verify(service).deleteForWorkitem(1L, 10011L);
    }

    @Test
    void listenerSwallowsCleanupFailures() {
        doThrow(new RuntimeException("db down")).when(service).deleteForWorkitem(1L, 10011L);

        new ClarificationAttachmentCleanupListener(service)
                .onWorkitemDeleted(new WorkitemDeletedEvent(1L, 10011L));

        verify(service).deleteForWorkitem(1L, 10011L);
    }

    @Test
    void scheduledTaskCleansOrphansInBatches() {
        when(service.cleanupOrphans(any(Date.class), eq(200))).thenReturn(3, 0);
        ClarificationAttachmentCleanupTask task = new ClarificationAttachmentCleanupTask(service);

        task.cleanupOrphans();
        task.cleanupOrphans();

        verify(service, times(2)).cleanupOrphans(any(Date.class), eq(200));
    }

    @Test
    void scheduledTaskSwallowsFailures() {
        when(service.cleanupOrphans(any(Date.class), eq(200)))
                .thenThrow(new RuntimeException("oss down"));

        new ClarificationAttachmentCleanupTask(service).cleanupOrphans();

        verify(service).cleanupOrphans(any(Date.class), eq(200));
    }
}
