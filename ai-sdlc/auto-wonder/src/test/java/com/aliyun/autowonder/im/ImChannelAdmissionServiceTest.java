package com.aliyun.autowonder.im;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImChannelAdmissionServiceTest {
    private final PlatformImChannelConfigDao configDao = mock(PlatformImChannelConfigDao.class);
    private final ImChannelAdmissionService service = new ImChannelAdmissionService(configDao);

    @Test
    void duplicateReceiptWinsOverEveryOtherGate() {
        when(configDao.lockSelection()).thenReturn(selection("FEISHU", 4L, Instant.parse("2026-10-22T08:00:00Z")));
        ImChannelAdmissionService.ReceiptWriter writer = mock(ImChannelAdmissionService.ReceiptWriter.class);
        when(writer.alreadyReceived(any())).thenReturn(true);

        ImChannelAdmissionService.Result result =
                service.admit("DINGTALK", null, writer);

        assertEquals(ImChannelAdmissionService.Decision.DUPLICATE, result.decision());
        assertEquals("duplicate", result.reason());
        assertNull(result.snapshot());
        verify(writer, never()).persist(any());
    }

    @Test
    void eventFromUnselectedProviderIsIgnored() {
        when(configDao.lockSelection()).thenReturn(selection("FEISHU", 4L, Instant.now()));
        ImChannelAdmissionService.ReceiptWriter writer = writer();

        ImChannelAdmissionService.Result result =
                service.admit("DINGTALK", Instant.now().plusSeconds(5), writer);

        assertEquals(ImChannelAdmissionService.Decision.IGNORED, result.decision());
        assertEquals("provider_not_selected", result.reason());
        verify(writer, never()).persist(any());
    }

    @Test
    void missingEventTimeIsIgnoredEvenForCurrentProvider() {
        when(configDao.lockSelection()).thenReturn(selection("DINGTALK", 4L, Instant.now()));
        ImChannelAdmissionService.ReceiptWriter writer = writer();

        assertEquals("invalid_event_time", service.admit("dingtalk", null, writer).reason());
        verify(writer, never()).persist(any());
    }

    @Test
    void eventOlderThanActivationIsStaleButZeroGenerationSkipsTheCheck() {
        Instant activatedAt = Instant.parse("2026-10-22T08:00:00Z");
        when(configDao.lockSelection()).thenReturn(selection("DINGTALK", 3L, activatedAt));
        ImChannelAdmissionService.ReceiptWriter writer = writer();

        assertEquals("stale_event",
                service.admit("DINGTALK", activatedAt.minusSeconds(1), writer).reason());
        verify(writer, never()).persist(any());

        when(configDao.lockSelection()).thenReturn(selection("DINGTALK", 0L, activatedAt));
        ImRouteSnapshot snapshot = ImRouteSnapshot.legacy("DINGTALK", 7L, 1L, 1L, 9L, 0L, 10L);
        when(writer.persist(any())).thenReturn(snapshot);

        assertTrue(service.admit("DINGTALK", activatedAt.minusSeconds(60), writer).accepted());
    }

    @Test
    void unadmittedPersistIsReportedAsIgnored() {
        when(configDao.lockSelection()).thenReturn(selection("DINGTALK", 1L, null));
        ImChannelAdmissionService.ReceiptWriter writer = writer();

        ImChannelAdmissionService.Result result = service.admit("DINGTALK", Instant.now(), writer);

        assertEquals(ImChannelAdmissionService.Decision.IGNORED, result.decision());
        assertEquals("not_admitted", result.reason());
    }

    @Test
    void acceptedEventExposesContextAndSnapshot() {
        Date activatedAt = Date.from(Instant.parse("2026-10-22T08:00:00Z"));
        ImSelectionDO row = new ImSelectionDO();
        row.setProvider("dingtalk");
        row.setGeneration(4L);
        row.setActivatedAt(activatedAt);
        when(configDao.lockSelection()).thenReturn(row);
        PlatformImChannelConfigDO groupConfig = new PlatformImChannelConfigDO();
        groupConfig.setEnabled(1);
        groupConfig.setRouteVersion(6L);
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(groupConfig);
        AtomicReference<ImChannelAdmissionService.Context> captured = new AtomicReference<>();
        ImRouteSnapshot snapshot = ImRouteSnapshot.shared("DINGTALK", 7L, 8L, 1L, 6L, 1L, 9L,
                "cid_group", 4L, 10L);
        ImChannelAdmissionService.ReceiptWriter writer = new ImChannelAdmissionService.ReceiptWriter() {
            @Override
            public boolean alreadyReceived(ImChannelAdmissionService.Context context) {
                return false;
            }

            @Override
            public ImRouteSnapshot persist(ImChannelAdmissionService.Context context) {
                captured.set(context);
                return snapshot;
            }
        };

        ImChannelAdmissionService.Result result = service.admit("DINGTALK",
                Instant.parse("2026-10-22T08:05:00Z"), writer);

        assertEquals(ImChannelAdmissionService.Decision.ACCEPTED, result.decision());
        assertEquals("accepted", result.reason());
        assertSame(snapshot, result.snapshot());
        ImChannelAdmissionService.Context context = captured.get();
        assertEquals("DINGTALK", context.provider());
        assertEquals("DINGTALK", context.selectedProvider());
        assertEquals(4L, context.generation());
        assertEquals(activatedAt.toInstant(), context.activatedAt());
        assertTrue(context.currentChannel());
        assertTrue(context.groupChatEnabled());
        assertEquals(6L, context.groupChatRouteVersion());
    }

    @Test
    void contextWithoutGroupChatConfigIsDisabledWithZeroRouteVersion() {
        ImChannelAdmissionService.Context context = new ImChannelAdmissionService.Context(
                "FEISHU", "DINGTALK", 0, null, null);

        assertFalse(context.currentChannel());
        assertFalse(context.groupChatEnabled());
        assertEquals(0L, context.groupChatRouteVersion());
        assertTrue(ImChannelAdmissionService.Result.of(
                ImChannelAdmissionService.Decision.IGNORED, "x").decision()
                == ImChannelAdmissionService.Decision.IGNORED);
    }

    @Test
    void missingSelectionRowRejectsAllInboundEvents() {
        when(configDao.lockSelection()).thenReturn(null);
        ImChannelAdmissionService.ReceiptWriter writer = writer();

        assertEquals("provider_not_selected", service.admit("DINGTALK", Instant.now(), writer).reason());
    }

    @Test
    void unparsableStoredProviderBehavesAsNoSelection() {
        ImSelectionDO row = new ImSelectionDO();
        row.setProvider("SLACK");
        row.setGeneration(1L);
        when(configDao.lockSelection()).thenReturn(row);
        ImChannelAdmissionService.ReceiptWriter writer = writer();

        assertEquals("provider_not_selected", service.admit("DINGTALK", Instant.now(), writer).reason());
    }

    @Test
    void managementCommandsFollowTheSelectedChannel() {
        when(configDao.lockSelection()).thenReturn(selection("DINGTALK", 2L, null));
        assertTrue(service.allowsManagement("dingtalk"));
        assertFalse(service.allowsManagement("FEISHU"));

        when(configDao.lockSelection()).thenReturn(null);
        assertFalse(service.allowsManagement("DINGTALK"));
    }

    private ImChannelAdmissionService.ReceiptWriter writer() {
        ImChannelAdmissionService.ReceiptWriter writer = mock(ImChannelAdmissionService.ReceiptWriter.class);
        when(writer.persist(any())).thenReturn(null);
        return writer;
    }

    private static ImSelectionDO selection(String provider, long generation, Instant activatedAt) {
        ImSelectionDO row = new ImSelectionDO();
        row.setProvider(provider);
        row.setGeneration(generation);
        row.setActivatedAt(activatedAt == null ? null : Date.from(activatedAt));
        return row;
    }
}
