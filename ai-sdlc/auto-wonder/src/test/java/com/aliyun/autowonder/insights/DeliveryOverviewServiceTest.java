package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.insights.dto.CreatedWorkitemVO;
import com.aliyun.autowonder.insights.dto.DeliveryOverviewVO;
import com.aliyun.autowonder.insights.dto.PendingWorkitemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryOverviewServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate START = LocalDate.of(2026, 1, 5);
    private static final LocalDate END = LocalDate.of(2026, 1, 7);

    private DeliveryOverviewDao dao;
    private DeliveryOverviewService service;

    @BeforeEach
    void setUp() {
        dao = mock(DeliveryOverviewDao.class);
        service = new DeliveryOverviewService(dao);
    }

    private static Date at(String localDateTime) {
        return Date.from(LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant());
    }

    private static DeliveryOverviewService.CreatedWorkitem created(long id, Date createdAt) {
        var row = new DeliveryOverviewService.CreatedWorkitem();
        row.setId(id);
        row.setCreatedAt(createdAt);
        return row;
    }

    private static DeliveryOverviewService.CreatedWorkitemRow createdRow(long id, String statusName, String category,
                                                                        int priority, Date createdAt) {
        var row = new DeliveryOverviewService.CreatedWorkitemRow();
        row.setId(id);
        row.setTitle("工单 " + id);
        row.setStatusName(statusName);
        row.setStatusCategory(category);
        row.setPriority(priority);
        row.setCreatedAt(createdAt);
        return row;
    }

    private static DeliveryOverviewService.PendingWorkitemRow pendingRow(long id, String category, int priority,
                                                                         Date gmtModified) {
        var row = new DeliveryOverviewService.PendingWorkitemRow();
        row.setId(id);
        row.setTitle("工单 " + id);
        row.setStatusName("状态 " + id);
        row.setStatusCategory(category);
        row.setPriority(priority);
        row.setGmtModified(gmtModified);
        return row;
    }

    private void currentThenPrevious(long current, long previous) {
        when(dao.countDeliveredWorkitems(eq(9L), any(Date.class), any(Date.class)))
                .thenReturn(current).thenReturn(previous);
    }

    @Test
    void rejectsInvalidRangesBeforeAnyDatabaseAccess() {
        LocalDate today = LocalDate.now(ZONE);
        for (LocalDate[] invalid : List.of(
                new LocalDate[]{null, today},
                new LocalDate[]{today, null},
                new LocalDate[]{today, today.minusDays(1)},
                new LocalDate[]{today.plusDays(1), today.plusDays(1)},
                new LocalDate[]{today.minusDays(367), today})) {
            KeyedIllegalArgumentException ex = assertThrows(KeyedIllegalArgumentException.class,
                    () -> service.overview(9L, invalid[0], invalid[1]));
            assertEquals("insights.invalid_date_range", ex.getMessageKey());
        }
        verifyNoInteractions(dao);
    }

    @Test
    void acceptsRangeEndingTodayAndMaxSpan() {
        LocalDate today = LocalDate.now(ZONE);
        when(dao.countDeliveredWorkitems(eq(9L), any(Date.class), any(Date.class))).thenReturn(0L);

        DeliveryOverviewVO exact = service.overview(9L, today, today);
        assertEquals(1, exact.getDaysInRange());

        DeliveryOverviewVO maxSpan = service.overview(9L, today.minusDays(366), today);
        assertEquals(367, maxSpan.getDaysInRange());
    }

    @Test
    void usesHalfOpenShanghaiBoundariesAndImmediatelyPrecedingEqualLengthPeriod() {
        when(dao.listCreatedWorkitems(9L, at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00")))
                .thenReturn(List.of(created(10L, at("2026-01-05T10:00:00"))));
        currentThenPrevious(2L, 1L);

        DeliveryOverviewVO vo = service.overview(9L, START, END);

        assertEquals("2026-01-05", vo.getStartDate());
        assertEquals("2026-01-07", vo.getEndDate());
        assertEquals("Asia/Shanghai", vo.getTimezone());
        assertEquals(LocalDate.now(ZONE).toString(), vo.getDataThrough());
        assertEquals(3, vo.getDaysInRange());
        assertEquals(1L, vo.getCreated().getCount());
        assertEquals(2L, vo.getDelivered().getCount());
        assertEquals("2026-01-02", vo.getDelivered().getPrevious().getStartDate());
        assertEquals("2026-01-04", vo.getDelivered().getPrevious().getEndDate());
        assertEquals(1L, vo.getDelivered().getPrevious().getCount());

        verify(dao).listCreatedWorkitems(9L, Date.from(Instant.parse("2026-01-04T16:00:00Z")),
                Date.from(Instant.parse("2026-01-07T16:00:00Z")));
        verify(dao).countDeliveredWorkitems(9L, Date.from(Instant.parse("2026-01-04T16:00:00Z")),
                Date.from(Instant.parse("2026-01-07T16:00:00Z")));
        verify(dao).countDeliveredWorkitems(9L, Date.from(Instant.parse("2026-01-01T16:00:00Z")),
                Date.from(Instant.parse("2026-01-04T16:00:00Z")));
    }

    @Test
    void zeroFillsDailyTrendAndBucketsByShanghaiNaturalDay() {
        when(dao.listCreatedWorkitems(eq(9L), any(Date.class), any(Date.class))).thenReturn(List.of(
                created(10L, at("2026-01-05T00:30:00")),
                created(11L, at("2026-01-05T10:00:00")),
                // UTC 前一天 17:30 = 上海次日 01:30，必须落入 01-06 桶
                created(12L, Date.from(Instant.parse("2026-01-05T17:30:00Z")))));
        currentThenPrevious(0L, 0L);

        DeliveryOverviewVO.Created created = service.overview(9L, START, END).getCreated();

        assertEquals(3L, created.getCount());
        assertEquals(3, created.getDaily().size());
        assertEquals(List.of("2026-01-05", "2026-01-06", "2026-01-07"),
                created.getDaily().stream().map(DeliveryOverviewVO.DailyCount::getDate).toList());
        assertEquals(List.of(2L, 1L, 0L),
                created.getDaily().stream().map(DeliveryOverviewVO.DailyCount::getCount).toList());
    }

    @Test
    void roundsDailyAverageToOneDecimal() {
        // 三天区间：23/3 = 7.666… → 7.7，1/3 = 0.333… → 0.3
        when(dao.countDeliveredWorkitems(eq(9L), any(Date.class), any(Date.class))).thenReturn(23L, 0L);
        assertEquals(7.7, service.overview(9L, START, END).getDelivered().getDailyAverage());

        when(dao.countDeliveredWorkitems(eq(9L), any(Date.class), any(Date.class))).thenReturn(1L, 0L);
        assertEquals(0.3, service.overview(9L, START, END).getDelivered().getDailyAverage());
    }

    @Test
    void roundsChangePercentAndReportsNullWithoutComparableBase() {
        currentThenPrevious(4L, 3L);
        assertEquals(33.3, service.overview(9L, START, END).getDelivered().getChangePercent());

        currentThenPrevious(2L, 1L);
        assertEquals(100.0, service.overview(9L, START, END).getDelivered().getChangePercent());

        currentThenPrevious(3L, 2L);
        assertEquals(50.0, service.overview(9L, START, END).getDelivered().getChangePercent());

        currentThenPrevious(2L, 3L);
        assertEquals(-33.3, service.overview(9L, START, END).getDelivered().getChangePercent());

        currentThenPrevious(3L, 3L);
        assertEquals(0.0, service.overview(9L, START, END).getDelivered().getChangePercent());

        currentThenPrevious(5L, 0L);
        assertNull(service.overview(9L, START, END).getDelivered().getChangePercent());
    }

    @Test
    void pendingNormalizesPagingAndForwardsOffsetLimit() {
        when(dao.countPendingWorkitems(9L, 42L)).thenReturn(3L);
        when(dao.listPendingWorkitems(eq(9L), eq(42L), anyLong(), anyInt()))
                .thenReturn(List.of());

        for (Integer[] paging : List.<Integer[]>of(new Integer[]{null, null}, new Integer[]{0, 0},
                new Integer[]{-5, -1}, new Integer[]{1, 0})) {
            PageResult<PendingWorkitemVO> page = service.pending(9L, 42L, paging[0], paging[1]);
            assertEquals(1, page.getPageNum());
            assertEquals(20, page.getPageSize());
            assertEquals(3L, page.getTotal());
        }
        verify(dao, org.mockito.Mockito.times(4)).listPendingWorkitems(9L, 42L, 0, 20);

        PageResult<PendingWorkitemVO> capped = service.pending(9L, 42L, 2, 500);
        assertEquals(2, capped.getPageNum());
        assertEquals(200, capped.getPageSize());
        verify(dao).listPendingWorkitems(9L, 42L, 200, 200);

        PageResult<PendingWorkitemVO> explicit = service.pending(9L, 42L, 3, 50);
        assertEquals(3, explicit.getPageNum());
        assertEquals(50, explicit.getPageSize());
        verify(dao).listPendingWorkitems(9L, 42L, 100, 50);

        // N1：超大 page 下 offset 必须按 long 计算，否则溢出为负值传给 SQL
        PageResult<PendingWorkitemVO> huge = service.pending(9L, 42L, Integer.MAX_VALUE, 200);
        assertEquals(Integer.MAX_VALUE, huge.getPageNum());
        assertEquals(200, huge.getPageSize());
        verify(dao).listPendingWorkitems(9L, 42L, 429496729200L, 200);
    }

    @Test
    void pendingMapsRowsWithReasonDerivedFromUnifiedCategory() {
        when(dao.countPendingWorkitems(9L, 42L)).thenReturn(3L);
        when(dao.listPendingWorkitems(9L, 42L, 0, 20)).thenReturn(List.of(
                pendingRow(11L, "PENDING_DECISION", 0, at("2026-01-06T09:00:00")),
                pendingRow(12L, "NEW", 2, at("2026-01-05T09:00:00")),
                pendingRow(13L, "IN_PROGRESS", 1, at("2026-01-04T09:00:00"))));

        PageResult<PendingWorkitemVO> page = service.pending(9L, 42L, null, null);

        assertEquals(3, page.getList().size());
        PendingWorkitemVO decision = page.getList().get(0);
        assertEquals(11L, decision.getId());
        assertEquals("工单 11", decision.getTitle());
        assertEquals("状态 11", decision.getStatusName());
        assertEquals("PENDING_DECISION", decision.getStatusCategory());
        assertEquals(0, decision.getPriority());
        assertEquals(DeliveryOverviewService.REASON_AWAITING_DECISION, decision.getReason());
        assertEquals("2026-01-06T01:00:00Z", decision.getGmtModified());

        assertEquals(DeliveryOverviewService.REASON_AWAITING_HANDLING, page.getList().get(1).getReason());
        assertEquals(DeliveryOverviewService.REASON_IN_PROGRESS, page.getList().get(2).getReason());
    }

    @Test
    void pendingKeepsEmptyPageShape() {
        when(dao.countPendingWorkitems(9L, 42L)).thenReturn(0L);
        when(dao.listPendingWorkitems(9L, 42L, 20, 20)).thenReturn(List.of());

        PageResult<PendingWorkitemVO> page = service.pending(9L, 42L, 2, 20);

        assertTrue(page.getList().isEmpty());
        assertEquals(0L, page.getTotal());
        assertEquals(2, page.getPageNum());
        assertEquals(20, page.getPageSize());
    }

    @Test
    void createdRejectsInvalidRangesBeforeAnyDatabaseAccess() {
        LocalDate today = LocalDate.now(ZONE);
        for (LocalDate[] invalid : List.of(
                new LocalDate[]{null, today},
                new LocalDate[]{today, null},
                new LocalDate[]{today, today.minusDays(1)},
                new LocalDate[]{today.plusDays(1), today.plusDays(1)},
                new LocalDate[]{today.minusDays(367), today})) {
            KeyedIllegalArgumentException ex = assertThrows(KeyedIllegalArgumentException.class,
                    () -> service.created(9L, invalid[0], invalid[1], 1, 20));
            assertEquals("insights.invalid_date_range", ex.getMessageKey());
        }
        verifyNoInteractions(dao);
    }

    @Test
    void createdNormalizesPagingForwardsHalfOpenBoundsAndSurvivesHugePageNumbers() {
        when(dao.countCreatedWorkitems(9L, at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00"))).thenReturn(7L);
        when(dao.listCreatedWorkitemsPage(eq(9L), any(Date.class), any(Date.class), anyLong(), anyInt()))
                .thenReturn(List.of());

        for (Integer[] paging : List.<Integer[]>of(new Integer[]{null, null}, new Integer[]{0, 0},
                new Integer[]{-5, -1}, new Integer[]{1, 0})) {
            PageResult<CreatedWorkitemVO> page = service.created(9L, START, END, paging[0], paging[1]);
            assertEquals(1, page.getPageNum());
            assertEquals(20, page.getPageSize());
            assertEquals(7L, page.getTotal());
        }
        // 半开区间 [2026-01-05, 2026-01-08) 在上海时区下的 UTC 边界
        verify(dao, org.mockito.Mockito.times(4)).listCreatedWorkitemsPage(9L,
                at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00"), 0L, 20);

        PageResult<CreatedWorkitemVO> capped = service.created(9L, START, END, 2, 500);
        assertEquals(2, capped.getPageNum());
        assertEquals(200, capped.getPageSize());
        verify(dao).listCreatedWorkitemsPage(9L, at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00"), 200L, 200);

        // N1 同类：超大 page 下 offset 必须按 long 计算，否则溢出为负值传给 SQL
        PageResult<CreatedWorkitemVO> huge = service.created(9L, START, END, Integer.MAX_VALUE, 200);
        assertEquals(Integer.MAX_VALUE, huge.getPageNum());
        verify(dao).listCreatedWorkitemsPage(9L, at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00"),
                429496729200L, 200);
    }

    @Test
    void createdMapsRowsWithIsoCreatedAtAndKeepsEmptyPageShape() {
        when(dao.countCreatedWorkitems(9L, at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00"))).thenReturn(2L);
        when(dao.listCreatedWorkitemsPage(9L, at("2026-01-05T00:00:00"), at("2026-01-08T00:00:00"), 0L, 20))
                .thenReturn(List.of(
                        createdRow(21L, "待处理", "NEW", 0, at("2026-01-06T09:00:00")),
                        createdRow(22L, null, "IN_PROGRESS", 2, at("2026-01-05T09:00:00"))));

        PageResult<CreatedWorkitemVO> page = service.created(9L, START, END, null, null);

        assertEquals(2L, page.getTotal());
        assertEquals(1, page.getPageNum());
        assertEquals(20, page.getPageSize());
        assertEquals(2, page.getList().size());
        CreatedWorkitemVO first = page.getList().get(0);
        assertEquals(21L, first.getId());
        assertEquals("工单 21", first.getTitle());
        assertEquals("待处理", first.getStatusName());
        assertEquals("NEW", first.getStatusCategory());
        assertEquals(0, first.getPriority());
        assertEquals("2026-01-06T01:00:00Z", first.getCreatedAt());
        CreatedWorkitemVO second = page.getList().get(1);
        assertNull(second.getStatusName());
        assertEquals("2026-01-05T01:00:00Z", second.getCreatedAt());

        PageResult<CreatedWorkitemVO> empty = service.created(9L, START, END, 2, 20);
        assertTrue(empty.getList().isEmpty());
        assertEquals(2, empty.getPageNum());
        assertEquals(20, empty.getPageSize());
    }
}
