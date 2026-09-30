package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemServiceRestartHandlerTest {

    WorkitemDao workitemDao;
    ApplicationEventPublisher eventPublisher;
    DeliveryRestartStore restartStore;
    WorkitemServiceRestartHandler handler;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        restartStore = mock(DeliveryRestartStore.class);
        handler = new WorkitemServiceRestartHandler(workitemDao, eventPublisher);
        handler.bindRestartStore(restartStore);
    }

    @Test
    void reopensClosedDeliveryAndPublishesRestartEventWithRoundKey() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        when(workitemDao.findById(500L)).thenReturn(w);
        when(restartStore.allocateRound(100L, 500L, 7L, "HUMAN", "token-1", null, 300031L, 40013L)).thenReturn(2);

        handler.restartAgentDelivery(500L, 100L, 40013L, 300031L, "token-1", null, 7L,
                AssignmentActor.human(7L, "张三"));

        verify(restartStore).recordClosedReopen(100L, 500L, 7L, "HUMAN");
        ArgumentCaptor<WorkitemDeliveryRestartedEvent> captor =
                ArgumentCaptor.forClass(WorkitemDeliveryRestartedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkitemDeliveryRestartedEvent event = captor.getValue();
        assertEquals(100L, event.getTenantId());
        assertEquals(500L, event.getWorkitemId());
        assertEquals(300031L, event.getSdlcStepId());
        assertEquals(40013L, event.getAgentId());
        assertEquals(7L, event.getUserId());
        assertEquals("restart:2:500", event.getIdempotencyKey());
    }

    @Test
    void systemOperatorUsesSystemAuditIdentity() {
        when(workitemDao.findById(500L)).thenReturn(new WorkitemDO());
        when(restartStore.allocateRound(100L, 500L, 0L, "SYSTEM", null, null, 300031L, 40013L)).thenReturn(1);

        handler.restartAgentDelivery(500L, 100L, 40013L, 300031L, null, null, 0L, null);

        verify(restartStore).recordClosedReopen(100L, 500L, 0L, "SYSTEM");
        verify(eventPublisher).publishEvent(any(WorkitemDeliveryRestartedEvent.class));
    }

    @Test
    void futureScheduleDefersRestartEventUntilScannerFires() {
        when(workitemDao.findById(500L)).thenReturn(new WorkitemDO());
        Date future = new Date(System.currentTimeMillis() + 3600_000L);
        when(restartStore.allocateRound(100L, 500L, 7L, "HUMAN", null, future, 300031L, 40013L)).thenReturn(1);

        handler.restartAgentDelivery(500L, 100L, 40013L, 300031L, null, future, 7L,
                AssignmentActor.human(7L, "张三"));

        verify(restartStore).recordClosedReopen(100L, 500L, 7L, "HUMAN");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void missingStoreStillPublishesRoundOneRestartEvent() {
        WorkitemServiceRestartHandler bare = new WorkitemServiceRestartHandler(workitemDao, eventPublisher);
        when(workitemDao.findById(500L)).thenReturn(new WorkitemDO());

        bare.restartAgentDelivery(500L, 100L, 40013L, 300031L, null, null, 7L, null);

        ArgumentCaptor<WorkitemDeliveryRestartedEvent> captor =
                ArgumentCaptor.forClass(WorkitemDeliveryRestartedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("restart:1:500", captor.getValue().getIdempotencyKey());
    }

    @Test
    void firePendingRestartNowPublishesRoundKeyWhenRoundPending() {
        when(restartStore.pendingScheduledRound(100L, 500L)).thenReturn(3);

        assertTrue(handler.firePendingRestartNow(100L, 500L, 40013L, 300031L, 7L));

        ArgumentCaptor<WorkitemDeliveryRestartedEvent> captor =
                ArgumentCaptor.forClass(WorkitemDeliveryRestartedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("restart:3:500", captor.getValue().getIdempotencyKey());
        assertEquals(40013L, captor.getValue().getAgentId());
        assertEquals(300031L, captor.getValue().getSdlcStepId());
    }

    @Test
    void firePendingRestartNowFallsBackWhenNoRoundPending() {
        when(restartStore.pendingScheduledRound(100L, 500L)).thenReturn(null);

        assertFalse(handler.firePendingRestartNow(100L, 500L, 40013L, 300031L, 7L));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void firePendingRestartNowWithoutStoreFallsBack() {
        WorkitemServiceRestartHandler bare = new WorkitemServiceRestartHandler(workitemDao, eventPublisher);

        assertFalse(bare.firePendingRestartNow(100L, 500L, 40013L, 300031L, 7L));

        verify(eventPublisher, never()).publishEvent(any());
    }
}
