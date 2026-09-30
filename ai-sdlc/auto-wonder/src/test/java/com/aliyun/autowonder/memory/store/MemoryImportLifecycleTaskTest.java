package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MemoryImportLifecycleTaskTest {
    @Test
    void springCanCreateMemoryImportLifecycleTask() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MemoryImportDao.class, () -> mock(MemoryImportDao.class));
            context.register(MemoryImportLifecycleTask.class);
            context.refresh();

            org.junit.jupiter.api.Assertions.assertNotNull(
                    context.getBean(MemoryImportLifecycleTask.class));
        }
    }

    @Test
    void marksSourcesUnseenForThirtyDaysAsStale() {
        MemoryImportDao dao = mock(MemoryImportDao.class);
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        new MemoryImportLifecycleTask(dao, Clock.fixed(now, ZoneOffset.UTC)).markStaleSources();
        verify(dao).markStaleBefore(Date.from(Instant.parse("2026-08-18T00:00:00Z")));
    }
}
