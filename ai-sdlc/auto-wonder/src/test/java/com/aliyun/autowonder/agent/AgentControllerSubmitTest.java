package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.agent.dto.SubmitReviewRequest;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.squad.SquadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Covers the optional request body added to POST /api/agents/{id}/submit. */
class AgentControllerSubmitTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    private AgentController controller(AgentService agentService) {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        return new AgentController(agentService, mock(SquadService.class));
    }

    @Test
    void submitWithoutBodyForwardsAnAbsentNote() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.submit(10L, 100L, 7L, null)).thenReturn(null);

        Result<?> result = controller(agentService).submit(10L, null);

        assertNotNull(result);
        verify(agentService).submit(10L, 100L, 7L, null);
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void submitForwardsTheOptionalNoteFromTheRequestBody() {
        AgentService agentService = mock(AgentService.class);
        SubmitReviewRequest request = new SubmitReviewRequest();
        request.setNote("补充性能预算");
        when(agentService.submit(10L, 100L, 7L, "补充性能预算")).thenReturn(null);

        Result<?> result = controller(agentService).submit(10L, request);

        assertNotNull(result);
        verify(agentService).submit(10L, 100L, 7L, "补充性能预算");
        verifyNoMoreInteractions(agentService);
    }
}
