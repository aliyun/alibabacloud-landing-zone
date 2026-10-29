package com.aliyun.autowonder.squad.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 定时任务只做兜底与保留期清理，任何一轮失败都不能让后续调度停摆。 */
class SquadWorkflowRecoveryTaskTest {

    private SquadWorkflowService workflowService;
    private SquadWorkflowRecoveryTask task;

    @BeforeEach
    void setUp() {
        workflowService = mock(SquadWorkflowService.class);
        task = new SquadWorkflowRecoveryTask(workflowService);
    }

    @Test
    void recoverDelegatesToTheService() {
        task.recover();

        verify(workflowService).recover();
    }

    @Test
    void recoverSwallowsAFailingPass() {
        doThrow(new IllegalStateException("deadlock")).when(workflowService).recover();

        assertDoesNotThrow(task::recover);
    }

    @Test
    void cleanupDelegatesToTheService() {
        task.cleanup();

        verify(workflowService).cleanupExpired();
    }

    @Test
    void cleanupSwallowsAFailingPass() {
        doThrow(new IllegalStateException("lock wait timeout")).when(workflowService).cleanupExpired();

        assertDoesNotThrow(task::cleanup);
    }
}
