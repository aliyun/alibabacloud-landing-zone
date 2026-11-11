package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.workitem.WorkitemCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkitemDuplicateAfterSaveListenerTest {

    private WorkitemDuplicateService service;
    private WorkitemDuplicateAfterSaveListener listener;

    @BeforeEach
    void setUp() {
        service = mock(WorkitemDuplicateService.class);
        listener = new WorkitemDuplicateAfterSaveListener(service);
    }

    @Test
    void createdWorkitemIsQueuedAfterCommit() {
        listener.onCreated(new WorkitemCreatedEvent(5L, 9L, "title", 7L));

        verify(service).enqueueAfterSave(5L, 7L, 9L, "title");
    }

    @Test
    void changedTitleIsQueued() {
        listener.onContentUpdated(new WorkitemContentUpdatedEvent(5L, 9L, "new title", "body", 7L, true));

        verify(service).enqueueAfterSave(5L, 7L, 9L, "new title");
    }

    @Test
    void bodyOnlyEditNeverCallsTheModel() {
        listener.onContentUpdated(new WorkitemContentUpdatedEvent(5L, 9L, "same title", "body", 7L, false));

        verify(service, never()).enqueueAfterSave(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void enqueueFailureIsSwallowedSoTheSaveStaysSuccessful() {
        doThrow(new IllegalStateException("db down"))
                .when(service).enqueueAfterSave(eq(5L), eq(7L), eq(9L), eq("title"));

        listener.onCreated(new WorkitemCreatedEvent(5L, 9L, "title", 7L));

        verify(service).enqueueAfterSave(5L, 7L, 9L, "title");
    }
}
