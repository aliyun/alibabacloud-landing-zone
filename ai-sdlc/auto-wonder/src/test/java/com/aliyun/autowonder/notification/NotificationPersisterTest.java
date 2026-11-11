package com.aliyun.autowonder.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 写入入口必须按事务边界分流：真正的事务里随事务走，AFTER_COMMIT 回调（无同步）里独立提交，
 * 否则实时发布会读到尚未提交的行（E2E F6）。
 */
class NotificationPersisterTest {

    private final NotificationDao dao = mock(NotificationDao.class);
    private final NotificationCommittedWriter writer = mock(NotificationCommittedWriter.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void insertsInTheSurroundingTransactionWhenSynchronizationIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        NotificationDO row = new NotificationDO();

        assertSame(row, new NotificationPersister(dao, writer).persist(row));

        verify(dao).insert(row);
        verifyNoInteractions(writer);
    }

    @Test
    void commitsIndependentlyWhenThereIsNoTransactionSynchronization() {
        NotificationDO row = new NotificationDO();
        when(writer.insert(any())).thenReturn(row);

        assertSame(row, new NotificationPersister(dao, writer).persist(row));

        verify(writer).insert(row);
        verify(dao, never()).insert(any());
    }

    @Test
    void fallsBackToADirectInsertWithoutACommittedWriter() {
        NotificationDO row = new NotificationDO();

        assertSame(row, new NotificationPersister(dao).persist(row));

        verify(dao).insert(row);
    }
}
