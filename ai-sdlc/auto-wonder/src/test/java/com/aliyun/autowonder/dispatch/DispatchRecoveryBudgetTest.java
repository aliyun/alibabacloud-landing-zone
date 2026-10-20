package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchRecoveryBudgetTest extends DispatchRecoveryServiceTest {
    @Test void pausesAfterThreeConsecutiveInventoryRecoveries() {
        var current = chain(3);
        assertNull(service.recoverAbandonedExecution(current, "session"));
        assertEquals("PAUSED", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=100", String.class));
        assertEquals("RUNTIME_RECOVERY_LIMIT_EXCEEDED", jdbc.queryForObject("SELECT error FROM dispatch WHERE id=100", String.class));
        verify(dao, never()).insert(any());
    }

    @Test void permitsThirdRecoveryDespiteHighGlobalAttemptCount() {
        var current = chain(2);
        when(dao.findMaxAttempt(1L, 10L, 77L)).thenReturn(10000);
        var next = service.recoverAbandonedExecution(current, "session");
        assertNotNull(next);
        assertEquals(10001, next.getAttempt());
    }

    @Test void manualContinuationStartsANewRecoveryBudget() {
        var current = chain(3);
        current.setCreatorId(123L);
        assertNotNull(service.recoverAbandonedExecution(current, "session"));
    }

    @Test void unrelatedFailureDoesNotConsumeInventoryRecoveryBudget() {
        var current = chain(3);
        dao.findById(99L).setError("OTHER_FAILURE");
        assertNotNull(service.recoverAbandonedExecution(current, "session"));
    }

    @Test void durableCompletionWithoutResultAckIsPausedNotReexecutedOrSucceeded() {
        orphanInventory("RUNNING", true, Set.of());
        completionEvidence();
        assertNull(service.recoverAbandonedExecution(dao.findById(100L), "session"));
        assertEquals("PAUSED", dao.findById(100L).getStatus());
        assertEquals("COMPLETION_DELIVERY_UNCONFIRMED", dao.findById(100L).getError());
        verify(dao, never()).insert(any());
    }

    private DispatchDO chain(int length) {
        orphanInventory("RUNNING", true, Set.of());
        var current = dao.findById(100L);
        doReturn(current).when(dao).findById(100L);
        var child = current;
        for (int i = 1; i <= length; i++) {
            long parentId = 100L - i;
            child.setResumeFromDispatchId(parentId);
            child.setIdempotencyKey("continue:" + parentId);
            child.setCreatorId(0L);
            var parent = new DispatchDO();
            parent.setId(parentId); parent.setTenantId(1L); parent.setWorkitemId(10L);
            parent.setAgentId(20L); parent.setSdlcStepId(77L);
            parent.setStatus("CANCELED"); parent.setError("RUNTIME_INVENTORY_LOST");
            doReturn(parent).when(dao).findById(parentId);
            child = parent;
        }
        return current;
    }
}
