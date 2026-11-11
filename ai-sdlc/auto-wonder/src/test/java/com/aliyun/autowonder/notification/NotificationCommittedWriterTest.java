package com.aliyun.autowonder.notification;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 独立提交器必须在 REQUIRES_NEW 事务里插入，方法返回时该行已提交。 */
class NotificationCommittedWriterTest {

    private final NotificationDao dao = mock(NotificationDao.class);
    private final NotificationCommittedWriter writer = new NotificationCommittedWriter(dao);

    @Test
    void insertsAndReturnsTheSameRow() {
        NotificationDO row = new NotificationDO();
        row.setId(9L);

        assertSame(row, writer.insert(row));
        verify(dao).insert(row);
    }

    @Test
    void declaresAnIndependentTransactionBoundary() throws Exception {
        Method insert = NotificationCommittedWriter.class.getMethod("insert", NotificationDO.class);
        Transactional transactional = insert.getAnnotation(Transactional.class);

        assertNotNull(transactional, "insert must run in its own transaction");
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }
}
