package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchAssignmentListener;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the scanner delivery path reaches DispatchAssignmentListener through Spring's real
 * transaction-event machinery. The listener is @TransactionalEventListener(AFTER_COMMIT) with
 * fallbackExecution=false, so an event published outside a transaction is silently dropped —
 * exactly the failure mode a mocked ApplicationEventPublisher cannot expose.
 */
class WorkitemScheduledStartDeliveryIntegrationTest {

    @Test
    void scannerDeliveryTriggersAssignmentListenerAfterCommit() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        DispatchService dispatchService = mock(DispatchService.class);
        DispatchDO queued = new DispatchDO();
        queued.setId(77L);
        when(dispatchService.enqueueAssignment(100L, 500L, 300031L, 40013L, 4, 0L)).thenReturn(queued);
        context.getBeanFactory().registerSingleton("assignmentListener",
                new DispatchAssignmentListener(dispatchService));
        context.refresh();

        try {
            WorkitemDao workitemDao = mock(WorkitemDao.class);
            RedisManager redis = mock(RedisManager.class);
            when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
            WorkitemDO due = new WorkitemDO();
            due.setId(500L);
            due.setTenantId(100L);
            due.setAssigneeType("AGENT");
            due.setAssigneeRef(40013L);
            due.setSdlcId(30003L);
            due.setCurrentStepId(300031L);
            due.setVersion(3);
            due.setScheduledStartAt(Date.from(Instant.parse("2026-08-26T09:30:00Z")));
            when(workitemDao.findScheduledDue(any(Date.class), anyInt())).thenReturn(List.of(due));
            when(workitemDao.fireScheduledStartAt(500L, 100L, 3)).thenReturn(1);

            DriverManagerDataSource h2 = new DriverManagerDataSource(
                    "jdbc:h2:mem:scheduled-delivery-test;DB_CLOSE_DELAY=-1", "sa", "");
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(h2));
            WorkitemScheduledStartConfig config = new WorkitemScheduledStartConfig();
            config.setScannerEnabled(false);
            config.setScanBatchSize(50);
            config.setLockTtlSeconds(30);

            WorkitemScheduledStartScanner scanner = new WorkitemScheduledStartScanner(workitemDao,
                    redis, context, config, transaction,
                    Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC));

            scanner.scanDue();

            verify(dispatchService).enqueueAssignment(100L, 500L, 300031L, 40013L, 4, 0L);
            verify(dispatchService).runPending(77L);
        } finally {
            context.close();
        }
    }
}
