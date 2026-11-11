package com.aliyun.autowonder.workspace.guide;

import com.aliyun.autowonder.workspace.event.WorkspaceCreatedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkspaceGuideRegistrationListenerTest {

    private final WorkspaceGuideService guideService = mock(WorkspaceGuideService.class);
    private final WorkspaceGuideRegistrationListener listener =
            new WorkspaceGuideRegistrationListener(guideService);

    @Test
    void registersEligibilityForTheWorkspaceCreator() {
        listener.onWorkspaceCreated(new WorkspaceCreatedEvent(10L, "新空间", 7L));

        verify(guideService).registerEligibility(10L, 7L);
    }

    @Test
    void swallowsRegistrationFailuresSoWorkspaceCreationIsNotReportedAsFailed() {
        doThrow(new IllegalStateException("db down")).when(guideService).registerEligibility(10L, 7L);

        assertDoesNotThrow(() -> listener.onWorkspaceCreated(new WorkspaceCreatedEvent(10L, "新空间", 7L)));
    }
}
