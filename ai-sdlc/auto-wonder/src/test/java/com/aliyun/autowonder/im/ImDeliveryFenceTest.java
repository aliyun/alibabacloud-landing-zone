package com.aliyun.autowonder.im;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImDeliveryFenceTest {

    private final PlatformTransactionManager transactions =
            mock(PlatformTransactionManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ImDeliveryFence fence = new ImDeliveryFence(transactions, jdbc);

    @Test
    void wrapsTheSendWindowInATransactionAndRestoresTheLockWait() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(50);

        assertEquals("sent", fence.run(() -> "sent"));

        verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = " + ImDeliveryFence.LOCK_WAIT_SECONDS);
        verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 50");
        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        // The window commits independently of the caller's transaction.
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
        verify(transactions).commit(any());
        verify(transactions, never()).rollback(any());
    }

    @Test
    void restoresTheLockWaitAndRollsBackWhenTheSendFails() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(7);

        assertThrows(IllegalStateException.class, () -> fence.run(() -> {
            throw new IllegalStateException("send failed");
        }));

        verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 7");
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void keepsWorkingWhenTheSessionVariableIsUnavailable() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("no such variable"));

        assertEquals("sent", fence.run(() -> "sent"));

        // The bounds fall back to the database default, but the window still opens and closes.
        verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = " + ImDeliveryFence.LOCK_WAIT_SECONDS);
        verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 50");
        verify(transactions).commit(any());
    }

    @Test
    void keepsWorkingWhenTheDatabaseRejectsTheLockWaitSetting() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(50);
        doThrow(new DataAccessResourceFailureException("unsupported")).when(jdbc)
                .execute(anyString());

        assertEquals("sent", fence.run(() -> "sent"));

        verify(transactions).commit(any());
    }

    @Test
    void exposesTheBoundedLockWait() {
        assertTrue(ImDeliveryFence.LOCK_WAIT_SECONDS > 0);
    }
}
