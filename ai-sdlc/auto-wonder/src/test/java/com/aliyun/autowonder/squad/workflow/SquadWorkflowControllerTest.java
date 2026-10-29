package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.squad.workflow.dto.SquadWorkflowGenerationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图谱接口只做上下文取值与透传；输入哈希过期时把最新视图塞回失败响应。 */
class SquadWorkflowControllerTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long SQUAD_ID = 5L;
    private static final long GENERATION_ID = 321L;

    private SquadWorkflowService workflowService;
    private SquadWorkflowController controller;

    @BeforeEach
    void setUp() {
        workflowService = mock(SquadWorkflowService.class);
        controller = new SquadWorkflowController(workflowService);
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void currentDelegatesWithWorkspaceScope() {
        SquadWorkflowView view = view(SquadWorkflowView.READY);
        when(workflowService.getCurrent(WORKSPACE_ID, SQUAD_ID)).thenReturn(view);

        Result<SquadWorkflowView> result = controller.current(SQUAD_ID);

        assertTrue(result.isSuccess());
        assertSame(view, result.getData());
        assertEquals(ErrorCode.SUCCESS.getCode(), result.getCode());
    }

    @Test
    void generationDelegatesWithWorkspaceScope() {
        SquadWorkflowView view = view(SquadWorkflowView.GENERATING);
        when(workflowService.getGeneration(WORKSPACE_ID, SQUAD_ID, GENERATION_ID)).thenReturn(view);

        Result<SquadWorkflowView> result = controller.generation(SQUAD_ID, GENERATION_ID);

        assertSame(view, result.getData());
        verify(workflowService).getGeneration(WORKSPACE_ID, SQUAD_ID, GENERATION_ID);
    }

    @Test
    void generateForwardsTheExpectedHashAndRetryFlag() {
        SquadWorkflowView view = view(SquadWorkflowView.GENERATING);
        when(workflowService.ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, "hash-1", true))
                .thenReturn(view);

        Result<SquadWorkflowView> result = controller.generate(SQUAD_ID,
                new SquadWorkflowGenerationRequest("hash-1", true));

        assertSame(view, result.getData());
        verify(workflowService).ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, "hash-1", true);
    }

    @Test
    void generateWithoutABodySkipsTheHashCheckAndDoesNotRetry() {
        SquadWorkflowView view = view(SquadWorkflowView.EMPTY);
        when(workflowService.ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, null, false))
                .thenReturn(view);

        Result<SquadWorkflowView> result = controller.generate(SQUAD_ID, null);

        assertSame(view, result.getData());
        verify(workflowService).ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, null, false);
    }

    @Test
    void generateTreatsAnExplicitNullRetryAsNoRetry() {
        when(workflowService.ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, "hash-1", false))
                .thenReturn(view(SquadWorkflowView.GENERATING));

        controller.generate(SQUAD_ID, new SquadWorkflowGenerationRequest("hash-1", null));

        verify(workflowService).ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, "hash-1", false);
    }

    @Test
    void staleInputReturnsAKeyedFailureCarryingTheCurrentView() {
        SquadWorkflowView current = view(SquadWorkflowView.READY);
        when(workflowService.ensureGeneration(WORKSPACE_ID, SQUAD_ID, USER_ID, "old-hash", false))
                .thenThrow(new SquadWorkflowStaleInputException(current));

        Result<SquadWorkflowView> result = controller.generate(SQUAD_ID,
                new SquadWorkflowGenerationRequest("old-hash", false));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.SQUAD_WORKFLOW_STALE_INPUT.getCode(), result.getCode());
        assertSame(current, result.getData());
    }

    @Test
    void aMissingWorkspaceContextIsRejectedBeforeCallingTheService() {
        AutoWonderContext.get().setCurrentWorkspaceId(null);

        BizException failure = assertThrows(BizException.class, () -> controller.current(SQUAD_ID));

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), failure.getCode());
    }

    @Test
    void aMissingUserContextIsRejectedBeforeGenerating() {
        AutoWonderContext.get().setUserId(null);

        BizException failure = assertThrows(BizException.class,
                () -> controller.generate(SQUAD_ID, new SquadWorkflowGenerationRequest(null, false)));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), failure.getCode());
    }

    private SquadWorkflowView view(String status) {
        return new SquadWorkflowView(status, "hash-" + status, WorkflowInput.ANALYSIS_VERSION,
                null, null, null, null, null);
    }
}
