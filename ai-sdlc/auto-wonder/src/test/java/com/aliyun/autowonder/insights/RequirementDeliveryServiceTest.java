package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.insights.dto.RequirementDeliveryVO;
import com.aliyun.autowonder.user.UserSettingService;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequirementDeliveryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KEY = RequirementDeliveryService.TARGET_SETTING_KEY;
    private static final LocalDate START = LocalDate.of(2026, 1, 5);
    private static final LocalDate END = LocalDate.of(2026, 1, 7);

    private RequirementDeliveryDao dao;
    private UserSettingService settings;
    private RequirementDeliveryService service;

    @BeforeEach
    void setUp() {
        dao = mock(RequirementDeliveryDao.class);
        settings = mock(UserSettingService.class);
        service = new RequirementDeliveryService(dao, settings);
    }

    private RequirementDeliveryVO get(LocalDate start, LocalDate end) {
        return service.get(9L, 7L, start, end);
    }

    private void currentThenPrevious(List<RequirementDeliveryService.DeliveredRequirement> current,
                                     List<RequirementDeliveryService.DeliveredRequirement> previous) {
        when(dao.listDeliveredRequirements(eq(9L), any(Date.class), any(Date.class)))
                .thenReturn(current).thenReturn(previous);
    }

    private static Date at(String localDateTime) {
        return Date.from(LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant());
    }

    private static RequirementDeliveryService.DeliveredRequirement row(long id, Date deliveredAt, Date startedAt) {
        var row = new RequirementDeliveryService.DeliveredRequirement();
        row.setWorkitemId(id);
        row.setTitle("需求 " + id);
        row.setDeliveredAt(deliveredAt);
        row.setDeliveryStartedAt(startedAt);
        return row;
    }

    private static RequirementDeliveryService.DeliveredRequirement delivered(long id, String deliveredAt) {
        return row(id, at(deliveredAt), null);
    }

    private static UserSettingVO storedTarget(String valueJson) {
        var vo = new UserSettingVO();
        vo.setKey(KEY);
        vo.setValueJson(valueJson);
        return vo;
    }

    @Test
    void rejectsInvalidRangesBeforeAnyDatabaseOrSettingAccess() {
        LocalDate today = LocalDate.now(ZONE);
        assertThrows(IllegalArgumentException.class, () -> service.get(9L, 7L, null, today));
        assertThrows(IllegalArgumentException.class, () -> service.get(9L, 7L, today, null));
        assertThrows(IllegalArgumentException.class, () -> service.get(9L, 7L, today, today.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> service.get(9L, 7L, today.plusDays(1), today.plusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.get(9L, 7L, today.minusDays(367), today));
        verifyNoInteractions(dao, settings);
    }

    @Test
    void comparesAgainstImmediatelyPrecedingEqualLengthPeriodWithHalfOpenBoundaries() {
        currentThenPrevious(List.of(
                row(10L, at("2026-01-05T10:00:00"), at("2026-01-01T02:00:00")),
                delivered(11L, "2026-01-06T09:00:00")), List.of(delivered(1L, "2026-01-02T09:00:00")));

        RequirementDeliveryVO vo = get(START, END);

        assertEquals("2026-01-05", vo.getStartDate());
        assertEquals("2026-01-07", vo.getEndDate());
        assertEquals("Asia/Shanghai", vo.getTimezone());
        assertEquals(LocalDate.now(ZONE).toString(), vo.getDataThrough());
        assertEquals(2L, vo.getSummary().getDeliveredCount());
        assertEquals("2026-01-02", vo.getSummary().getPrevious().getStartDate());
        assertEquals("2026-01-04", vo.getSummary().getPrevious().getEndDate());
        assertEquals(1L, vo.getSummary().getPrevious().getDeliveredCount());
        assertEquals(100.0, vo.getSummary().getChangePercent());
        verify(dao).listDeliveredRequirements(9L, Date.from(Instant.parse("2026-01-04T16:00:00Z")),
                Date.from(Instant.parse("2026-01-07T16:00:00Z")));
        verify(dao).listDeliveredRequirements(9L, Date.from(Instant.parse("2026-01-01T16:00:00Z")),
                Date.from(Instant.parse("2026-01-04T16:00:00Z")));
    }

    @Test
    void reportsNullChangePercentWhenPreviousPeriodHasNoComparableBase() {
        currentThenPrevious(List.of(delivered(10L, "2026-01-05T10:00:00")), List.of());

        RequirementDeliveryVO.Summary summary = get(START, END).getSummary();

        assertNull(summary.getChangePercent());
        assertEquals(0L, summary.getPrevious().getDeliveredCount());
    }

    @Test
    void roundsChangePercentUpDownAndFlatToOneDecimal() {
        currentThenPrevious(List.of(delivered(10L, "2026-01-05T10:00:00"), delivered(11L, "2026-01-05T11:00:00"),
                        delivered(12L, "2026-01-06T10:00:00"), delivered(13L, "2026-01-07T10:00:00")),
                List.of(delivered(1L, "2026-01-02T09:00:00"), delivered(2L, "2026-01-03T09:00:00"),
                        delivered(3L, "2026-01-03T10:00:00")));
        assertEquals(33.3, get(START, END).getSummary().getChangePercent());

        currentThenPrevious(List.of(delivered(10L, "2026-01-05T10:00:00"), delivered(11L, "2026-01-05T11:00:00")),
                List.of(delivered(1L, "2026-01-02T09:00:00")));
        assertEquals(100.0, get(START, END).getSummary().getChangePercent());

        currentThenPrevious(List.of(delivered(10L, "2026-01-05T10:00:00"), delivered(11L, "2026-01-05T11:00:00"),
                        delivered(12L, "2026-01-05T12:00:00")),
                List.of(delivered(1L, "2026-01-02T09:00:00"), delivered(2L, "2026-01-02T10:00:00")));
        assertEquals(50.0, get(START, END).getSummary().getChangePercent());
    }

    @Test
    void computesAverageMedianGoalRatioAndClassifiesMissingTimelines() {
        when(settings.get(7L, KEY)).thenReturn(storedTarget("24"));
        currentThenPrevious(List.of(
                row(10L, at("2026-01-05T10:00:00"), at("2026-01-05T00:00:00")),
                row(11L, at("2026-01-05T10:00:00"), at("2026-01-04T04:00:00")),
                delivered(12L, "2026-01-06T10:00:00"),
                row(13L, at("2026-01-07T10:00:00"), at("2026-01-08T10:00:00"))), List.of());

        RequirementDeliveryVO vo = get(START, END);
        RequirementDeliveryVO.Summary summary = vo.getSummary();

        assertEquals(24L, vo.getTargetHours());
        assertEquals(4L, summary.getDeliveredCount());
        assertEquals(2, summary.getValidSampleCount());
        assertEquals(2, summary.getMissingSampleCount());
        assertEquals(72000L, summary.getAverageDurationSeconds());
        assertEquals(72000L, summary.getMedianDurationSeconds());
        assertEquals(1, summary.getMetTargetCount());
        assertEquals(50.0, summary.getGoalRatio());

        RequirementDeliveryVO.Sample met = vo.getSamples().get(0);
        assertEquals(10L, met.getWorkitemId());
        assertEquals("需求 10", met.getTitle());
        assertEquals("2026-01-05T02:00:00Z", met.getDeliveredAt());
        assertEquals("2026-01-04T16:00:00Z", met.getDeliveryStartedAt());
        assertEquals(36000L, met.getDurationSeconds());
        assertEquals(Boolean.TRUE, met.getMetTarget());
        assertNull(met.getMissingReason());

        RequirementDeliveryVO.Sample missingStart = vo.getSamples().get(2);
        assertNull(missingStart.getDeliveryStartedAt());
        assertNull(missingStart.getDurationSeconds());
        assertNull(missingStart.getMetTarget());
        assertEquals("MISSING_DELIVERY_START", missingStart.getMissingReason());

        RequirementDeliveryVO.Sample invalid = vo.getSamples().get(3);
        assertNull(invalid.getDurationSeconds());
        assertNull(invalid.getMetTarget());
        assertEquals("INVALID_TIMELINE", invalid.getMissingReason());

        RequirementDeliveryVO.Week week = vo.getWeeks().get(0);
        assertEquals(4L, week.getDeliveredCount());
        assertEquals(72000L, week.getAverageDurationSeconds());
        assertEquals(1, week.getMetTargetCount());
        assertEquals(50.0, week.getGoalRatio());
    }

    @Test
    void keepsDurationsButOmitsGoalMetricsWhenTargetIsUnset() {
        when(settings.get(7L, KEY)).thenReturn(storedTarget(null));
        currentThenPrevious(List.of(row(10L, at("2026-01-05T10:00:00"), at("2026-01-05T00:00:00"))), List.of());

        RequirementDeliveryVO vo = get(START, END);

        assertNull(vo.getTargetHours());
        assertEquals(36000L, vo.getSummary().getAverageDurationSeconds());
        assertEquals(36000L, vo.getSummary().getMedianDurationSeconds());
        assertNull(vo.getSummary().getMetTargetCount());
        assertNull(vo.getSummary().getGoalRatio());
        assertNull(vo.getSamples().get(0).getMetTarget());
        assertNull(vo.getSamples().get(0).getMissingReason());
    }

    @Test
    void treatsAbsentMalformedOrOutOfRangeStoredTargetAsUnset() {
        for (String stored : List.of("null", "\"24\"", "0", "-3", "12.5", "10001", "true", "{bad",
                "24.000000000000000001", "0.999999999999999999",
                "10000.000000000000000001", "9223372036854775808", "1e100")) {
            when(settings.get(7L, KEY)).thenReturn(storedTarget(stored));
            currentThenPrevious(List.of(row(10L, at("2026-01-05T10:00:00"), at("2026-01-05T00:00:00"))),
                    List.of());
            RequirementDeliveryVO vo = get(START, END);
            assertNull(vo.getTargetHours(), stored);
            assertNull(vo.getSummary().getGoalRatio(), stored);
            assertNull(vo.getSummary().getMetTargetCount(), stored);
        }
        when(settings.get(7L, KEY)).thenReturn(null);
        currentThenPrevious(List.of(), List.of());
        assertNull(get(START, END).getTargetHours());
    }

    @Test
    void acceptsExactIntegerTargetsIncludingDecimalAndExponentNotation() {
        for (String stored : List.of("1", "24.0", "2.4e1", "10000")) {
            when(settings.get(7L, KEY)).thenReturn(storedTarget(stored));
            currentThenPrevious(List.of(), List.of());
            long expected = switch (stored) {
                case "1" -> 1L;
                case "10000" -> 10000L;
                default -> 24L;
            };
            assertEquals(expected, get(START, END).getTargetHours(), stored);
        }
    }

    @Test
    void ignoresStoredTargetWhenNoAuthenticatedUser() {
        currentThenPrevious(List.of(row(10L, at("2026-01-05T10:00:00"), at("2026-01-05T00:00:00"))), List.of());

        RequirementDeliveryVO vo = service.get(9L, null, START, END);

        assertNull(vo.getTargetHours());
        assertEquals(36000L, vo.getSummary().getAverageDurationSeconds());
        verify(settings, never()).get(anyLong(), anyString());
    }

    @Test
    void bucketsSamplesIntoIsoWeeksAcrossYearBoundaryWithPartialEdges() {
        when(settings.get(7L, KEY)).thenReturn(storedTarget("24"));
        currentThenPrevious(List.of(delivered(10L, "2025-12-31T09:00:00"), delivered(11L, "2026-01-04T23:00:00"),
                delivered(12L, "2026-01-05T00:00:00"), delivered(13L, "2026-01-06T23:59:59")),
                List.of());

        RequirementDeliveryVO vo = get(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 6));

        assertEquals(2, vo.getWeeks().size());
        RequirementDeliveryVO.Week first = vo.getWeeks().get(0);
        assertEquals("2025-12-29", first.getWeekStart());
        assertEquals("2026-01-04", first.getWeekEnd());
        assertEquals("2025-12-31", first.getCoveredFrom());
        assertEquals("2026-01-04", first.getCoveredTo());
        assertTrue(first.isPartial());
        assertEquals(2L, first.getDeliveredCount());
        RequirementDeliveryVO.Week second = vo.getWeeks().get(1);
        assertEquals("2026-01-05", second.getWeekStart());
        assertEquals("2026-01-11", second.getWeekEnd());
        assertEquals("2026-01-05", second.getCoveredFrom());
        assertEquals("2026-01-06", second.getCoveredTo());
        assertTrue(second.isPartial());
        assertEquals(2L, second.getDeliveredCount());
        // 两条样本都没有交付开始时间（delivered() 助手起点为 null），计入缺失样本而非有效样本。
        assertEquals(0, second.getValidSampleCount());
        assertEquals(2, second.getMissingSampleCount());
    }

    @Test
    void marksAlignedFullWeekNotPartialAndReportsNoSampleGoalRatioAsNull() {
        when(settings.get(7L, KEY)).thenReturn(storedTarget("24"));
        currentThenPrevious(List.of(), List.of());

        RequirementDeliveryVO vo = get(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 11));

        assertEquals(1, vo.getWeeks().size());
        RequirementDeliveryVO.Week week = vo.getWeeks().get(0);
        assertEquals("2026-01-05", week.getWeekStart());
        assertEquals("2026-01-11", week.getWeekEnd());
        assertEquals("2026-01-05", week.getCoveredFrom());
        assertEquals("2026-01-11", week.getCoveredTo());
        assertFalse(week.isPartial());
        assertEquals(0L, week.getDeliveredCount());
        assertEquals(0L, vo.getSummary().getDeliveredCount());
        assertNull(week.getAverageDurationSeconds());
        assertNull(week.getMedianDurationSeconds());
        assertEquals(0, week.getMetTargetCount());
        assertNull(week.getGoalRatio());
        assertTrue(vo.getSamples().isEmpty());
    }

    @Test
    void keepsSampleWithoutBucketWeekOutOfWeekAggregates() {
        currentThenPrevious(List.of(delivered(10L, "2026-03-01T10:00:00")), List.of());

        RequirementDeliveryVO vo = get(START, END);

        assertEquals(1L, vo.getSummary().getDeliveredCount());
        assertEquals(1, vo.getSamples().size());
        assertEquals(1, vo.getWeeks().size());
        assertEquals(0L, vo.getWeeks().get(0).getDeliveredCount());
        assertEquals(0, vo.getWeeks().get(0).getValidSampleCount());
    }

    @Test
    void treatsStartedEqualToDeliveredAsZeroDurationMetSample() {
        when(settings.get(7L, KEY)).thenReturn(storedTarget("24"));
        currentThenPrevious(List.of(row(10L, at("2026-01-05T10:00:00"), at("2026-01-05T10:00:00"))), List.of());

        RequirementDeliveryVO vo = get(START, END);

        RequirementDeliveryVO.Sample sample = vo.getSamples().get(0);
        assertEquals(0L, sample.getDurationSeconds());
        assertEquals(Boolean.TRUE, sample.getMetTarget());
        assertNull(sample.getMissingReason());
        assertEquals(1, vo.getSummary().getValidSampleCount());
        assertEquals(0L, vo.getSummary().getAverageDurationSeconds());
        assertEquals(0L, vo.getSummary().getMedianDurationSeconds());
        assertEquals(100.0, vo.getSummary().getGoalRatio());
    }
}
