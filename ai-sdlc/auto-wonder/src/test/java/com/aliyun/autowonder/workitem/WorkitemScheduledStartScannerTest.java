package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.WorkitemAssignedEvent;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemScheduledStartScannerTest {

    WorkitemDao workitemDao;
    RedisManager redis;
    ApplicationEventPublisher eventPublisher;
    WorkitemScheduledStartConfig config;
    WorkitemScheduledStartScanner scanner;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        redis = mock(RedisManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        config = new WorkitemScheduledStartConfig();
        config.setScannerEnabled(true);
        config.setScanBatchSize(50);
        config.setLockTtlSeconds(30);
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:scanner-test;DB_CLOSE_DELAY=-1", "sa", "");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(h2));
        scanner = new WorkitemScheduledStartScanner(workitemDao, redis, eventPublisher, config,
                transaction, Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC));
    }

    private WorkitemDO scheduledWorkitem() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(100L);
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(40013L);
        w.setSdlcId(30003L);
        w.setCurrentStepId(300031L);
        w.setVersion(3);
        w.setScheduledStartAt(Date.from(Instant.parse("2026-08-26T09:30:00Z")));
        return w;
    }

    @Test
    void scanSkipsWorkWhenLockNotAcquired() {
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);

        scanner.scan();

        verify(workitemDao, never()).findScheduledDue(any(Date.class), anyInt());
        verify(redis, never()).releaseLock(anyString(), anyString());
    }

    @Test
    void scanReleasesLockAfterScanning() {
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(workitemDao.findScheduledDue(any(Date.class), eq(50))).thenReturn(List.of());

        scanner.scan();

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(redis).tryAcquireLock(eq("workitem:scheduled-start:scanner:lock"), owner.capture(), eq(30_000L));
        verify(redis).releaseLock("workitem:scheduled-start:scanner:lock", owner.getValue());
    }

    @Test
    void scanDuePublishesAssignedEventWhenCasClearSucceeds() {
        WorkitemDO w = scheduledWorkitem();
        when(workitemDao.findScheduledDue(Date.from(Instant.parse("2026-08-26T10:00:00Z")), 50))
                .thenReturn(List.of(w));
        when(workitemDao.fireScheduledStartAt(500L, 100L, 3)).thenReturn(1);

        scanner.scanDue();

        ArgumentCaptor<WorkitemAssignedEvent> captor = ArgumentCaptor.forClass(WorkitemAssignedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkitemAssignedEvent event = captor.getValue();
        assertEquals(100L, event.getTenantId());
        assertEquals(500L, event.getWorkitemId());
        assertEquals(300031L, event.getSdlcStepId());
        assertEquals(40013L, event.getAgentId());
        assertEquals(4, event.getAssignmentVersion());
        assertEquals(0L, event.getUserId());
    }

    @Test
    void scanDueSkipsWorkitemWhenCasClearLosesRace() {
        WorkitemDO w = scheduledWorkitem();
        when(workitemDao.findScheduledDue(any(Date.class), anyInt())).thenReturn(List.of(w));
        when(workitemDao.fireScheduledStartAt(500L, 100L, 3)).thenReturn(0);

        scanner.scanDue();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void dispatchDueIgnoresWorkitemNoLongerAgentAssigned() {
        WorkitemDO w = scheduledWorkitem();
        w.setAssigneeType("HUMAN");

        scanner.dispatchDue(w);

        verify(workitemDao, never()).fireScheduledStartAt(anyLong(), anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void dispatchDueIgnoresWorkitemWithoutScheduleOrVersion() {
        WorkitemDO noSchedule = scheduledWorkitem();
        noSchedule.setScheduledStartAt(null);
        WorkitemDO noVersion = scheduledWorkitem();
        noVersion.setVersion(null);
        WorkitemDO noAgent = scheduledWorkitem();
        noAgent.setAssigneeRef(null);

        scanner.dispatchDue(noSchedule);
        scanner.dispatchDue(noVersion);
        scanner.dispatchDue(noAgent);

        verify(workitemDao, never()).fireScheduledStartAt(anyLong(), anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
