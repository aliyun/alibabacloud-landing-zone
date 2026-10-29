package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberDeliveryServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.now(ZONE);

    private static Date atStartOfDay(LocalDate date) {
        return Date.from(date.atStartOfDay(ZONE).toInstant());
    }

    private static MemberDeliveryService.TypeCounts row(Long memberId, String workType, long created, long total,
            long completed, long inProgress, long notStarted, long pendingDecision, long requirements) {
        var counts = new MemberDeliveryService.TypeCounts();
        counts.setMemberId(memberId);
        counts.setWorkType(workType);
        counts.setCreated(created);
        counts.setTotal(total); counts.setCompleted(completed); counts.setInProgress(inProgress);
        counts.setNotStarted(notStarted); counts.setPendingDecision(pendingDecision); counts.setRequirements(requirements);
        return counts;
    }

    @Test void includesZeroMembersAndUnassignedTasksWithoutLosingCounts() {
        var dao = mock(MemberDeliveryDao.class);
        var zero = new MemberDeliveryService.Member(); zero.setMemberId(1L); zero.setMemberName("zero");
        when(dao.members(9L)).thenReturn(List.of(zero));
        when(dao.counts(eq(9L), any(), any())).thenReturn(List.of(
                row(null, "REQ", 3, 4, 1, 1, 1, 1, 1), row(null, "TASK", 1, 2, 1, 0, 1, 0, 0)));
        var start = TODAY.minusDays(6);
        var report = new MemberDeliveryService(dao).get(9L, start.toString(), TODAY.toString());
        assertEquals(2, report.members().size());
        assertEquals(0, report.members().get(0).getTotal());
        assertEquals(6, report.members().get(0).getTotal() + report.members().get(1).getTotal());
        assertEquals("未归属成员", report.members().get(1).getMemberName());
        assertEquals(6, report.summary().getTotal());
        assertEquals(4, report.summary().getCreated());
        assertEquals(4, report.members().get(1).getCreated());
        assertEquals(2, report.summary().getCompleted());
        assertEquals(1, report.summary().getInProgress());
        assertEquals(2, report.summary().getNotStarted());
        assertEquals(1, report.summary().getPendingDecision());
        assertEquals(1, report.summary().getRequirements());
        assertEquals(start.toString(), report.startDate());
        assertEquals(TODAY.toString(), report.endDate());
        verify(dao).counts(eq(9L), eq(atStartOfDay(start)), eq(atStartOfDay(TODAY.plusDays(1))));
    }

    @Test void defaultsToTheLastThirtyDaysWhenParametersAreMissing() {
        var dao = mock(MemberDeliveryDao.class);
        when(dao.members(9L)).thenReturn(List.of());
        when(dao.counts(eq(9L), any(), any())).thenReturn(List.of());
        var report = new MemberDeliveryService(dao).get(9L, null, null);
        var start = TODAY.minusDays(29);
        assertEquals(start.toString(), report.startDate());
        assertEquals(TODAY.toString(), report.endDate());
        var startCaptor = ArgumentCaptor.forClass(Date.class);
        var endCaptor = ArgumentCaptor.forClass(Date.class);
        verify(dao).counts(eq(9L), startCaptor.capture(), endCaptor.capture());
        assertEquals(atStartOfDay(start), startCaptor.getValue());
        assertEquals(atStartOfDay(TODAY.plusDays(1)), endCaptor.getValue());
    }

    @Test void rejectsInvalidRangesBeforeDatabaseAccess() {
        var dao = mock(MemberDeliveryDao.class); var service = new MemberDeliveryService(dao);
        assertThrows(RuntimeException.class, () -> service.get(1, "wrong", TODAY.toString()));
        assertThrows(RuntimeException.class, () -> service.get(1, TODAY.toString(), TODAY.minusDays(1).toString()));
        assertThrows(RuntimeException.class, () -> service.get(1, TODAY.minusDays(6).toString(), TODAY.plusDays(1).toString()));
        assertThrows(RuntimeException.class, () -> service.get(1, TODAY.minusDays(181).toString(), TODAY.toString()));
        assertThrows(RuntimeException.class, () -> service.get(1, TODAY.minusDays(400).toString(), TODAY.toString()));
        verifyNoInteractions(dao);
    }

    @Test void acceptsTheEarliestAllowedDayAndRejectsOneDayEarlier() {
        var dao = mock(MemberDeliveryDao.class);
        when(dao.members(9L)).thenReturn(List.of());
        when(dao.counts(eq(9L), any(), any())).thenReturn(List.of());
        var service = new MemberDeliveryService(dao);
        assertEquals(TODAY.minusDays(180).toString(), service.get(9L, TODAY.minusDays(180).toString(), TODAY.toString()).startDate());
        verify(dao).counts(eq(9L), any(), any());
        assertThrows(RuntimeException.class, () -> service.get(9L, TODAY.minusDays(181).toString(), TODAY.toString()));
    }

    @Test void historicalOriginIsOptionalAndOnlySetOnMissingCurrentNonNullId() throws Exception {
        var dao=mock(MemberDeliveryDao.class);
        var authored=new MemberDeliveryService.Member(); authored.setMemberId(7L);authored.setMemberName("历史成员 #8");
        when(dao.members(9L)).thenReturn(List.of(authored));
        when(dao.counts(eq(9L),any(),any())).thenReturn(List.of(
                row(8L,"REQ",1,1,0,0,0,0,0), row(null,"TASK",0,2,0,0,0,0,0)));
        var report=new MemberDeliveryService(dao).get(9L,TODAY.minusDays(6).toString(),TODAY.toString());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var json=mapper.valueToTree(report.members());
        assertEquals("历史成员 #8",report.members().get(1).getMemberName());
        assertEquals(3,report.summary().getTotal()); assertEquals("未归属成员",report.members().get(2).getMemberName());
        assertEquals(1,report.summary().getCreated());
        assertEquals(1,json.get(1).path("created").asLong());
        assertFalse(json.get(0).has("memberNameKind")); assertFalse(json.get(2).has("memberNameKind"));
        assertEquals("HISTORICAL_MEMBER_FALLBACK",json.get(1).path("memberNameKind").asText());
        assertFalse(mapper.valueToTree(report).has("weekRequirements"));
        assertFalse(mapper.valueToTree(report.summary()).has("workType"));
        assertFalse(mapper.valueToTree(report.members().get(0)).has("workType"));
    }

    @Test void scopesTheSummaryToTheRequestedWorkTypeAndKeepsEveryTypeInMemberRows() {
        var dao = mock(MemberDeliveryDao.class);
        when(dao.members(9L)).thenAnswer(invocation -> {
            var fresh = new MemberDeliveryService.Member();
            fresh.setMemberId(7L);
            fresh.setMemberName("member");
            return List.of(fresh);
        });
        when(dao.counts(eq(9L), any(), any())).thenReturn(List.of(
                row(7L, "REQ", 2, 3, 2, 0, 1, 0, 2),
                row(7L, "BUG", 1, 2, 1, 1, 0, 0, 0),
                row(7L, "TASK", 3, 4, 1, 2, 1, 0, 0)));
        var service = new MemberDeliveryService(dao);
        var start = TODAY.minusDays(6).toString();

        var all = service.get(9L, start, TODAY.toString());
        assertEquals(9, all.summary().getTotal());
        assertEquals(6, all.summary().getCreated());
        assertEquals(4, all.summary().getCompleted());
        assertEquals(3, all.summary().getInProgress());
        assertEquals(2, all.summary().getRequirements());
        assertEquals(9, all.members().get(0).getTotal());
        assertEquals(6, all.members().get(0).getCreated());
        // summary 是成员行之和，created 也遵守同一恒等式。
        assertEquals(all.summary().getCreated(),
                all.members().stream().mapToLong(MemberDeliveryService.Member::getCreated).sum());

        var defects = service.get(9L, start, TODAY.toString(), "BUG");
        assertEquals(2, defects.summary().getTotal());
        assertEquals(1, defects.summary().getCreated());
        assertEquals(1, defects.summary().getCompleted());
        assertEquals(1, defects.summary().getInProgress());
        assertEquals(0, defects.summary().getNotStarted());
        assertEquals(0, defects.summary().getRequirements());
        // 类型筛选不影响 members 明细：表格始终是全部类型口径。
        assertEquals(9, defects.members().get(0).getTotal());
        assertEquals(6, defects.members().get(0).getCreated());
        assertEquals(4, defects.members().get(0).getCompleted());
        assertEquals(3, defects.members().get(0).getInProgress());

        var requirements = service.get(9L, start, TODAY.toString(), "REQ");
        assertEquals(3, requirements.summary().getTotal());
        assertEquals(2, requirements.summary().getCreated());
        assertEquals(2, requirements.summary().getRequirements());
        assertEquals(1, requirements.summary().getNotStarted());

        var tasks = service.get(9L, start, TODAY.toString(), "TASK");
        assertEquals(4, tasks.summary().getTotal());
        assertEquals(3, tasks.summary().getCreated());
        assertEquals(2, tasks.summary().getInProgress());
        assertEquals(1, tasks.summary().getNotStarted());

        assertEquals(all.summary().getTotal(),
                requirements.summary().getTotal() + defects.summary().getTotal() + tasks.summary().getTotal());
        assertEquals(all.summary().getCreated(),
                requirements.summary().getCreated() + defects.summary().getCreated() + tasks.summary().getCreated());
        assertTrue(all.summary().getCreated() <= all.summary().getTotal());
        assertEquals(all.summary().getCompleted(),
                requirements.summary().getCompleted() + defects.summary().getCompleted() + tasks.summary().getCompleted());
        assertEquals(all.summary().getInProgress(),
                requirements.summary().getInProgress() + defects.summary().getInProgress() + tasks.summary().getInProgress());
    }

    @Test void treatsAllBlankAndMissingWorkTypesAsUnfiltered() {
        var dao = mock(MemberDeliveryDao.class);
        when(dao.members(9L)).thenReturn(List.of());
        when(dao.counts(eq(9L), any(), any())).thenReturn(List.of(
                row(null, "REQ", 2, 2, 1, 0, 1, 0, 1), row(null, "BUG", 2, 3, 2, 0, 1, 0, 0)));
        var service = new MemberDeliveryService(dao);
        var start = TODAY.minusDays(6).toString();
        assertEquals(5, service.get(9L, start, TODAY.toString(), "ALL").summary().getTotal());
        assertEquals(4, service.get(9L, start, TODAY.toString(), "ALL").summary().getCreated());
        assertEquals(5, service.get(9L, start, TODAY.toString(), "  ").summary().getTotal());
        assertEquals(4, service.get(9L, start, TODAY.toString(), "  ").summary().getCreated());
        assertEquals(5, service.get(9L, start, TODAY.toString()).summary().getTotal());
        assertEquals(4, service.get(9L, start, TODAY.toString()).summary().getCreated());
    }

    @Test void rejectsUnknownWorkTypesBeforeDatabaseAccess() {
        var dao = mock(MemberDeliveryDao.class);
        var service = new MemberDeliveryService(dao);
        var start = TODAY.minusDays(6).toString();
        for (String invalid : List.of("EPIC", "req", "REQS", "REQ,BUG")) {
            var error = assertThrows(BizException.class,
                    () -> service.get(1, start, TODAY.toString(), invalid));
            assertEquals("10001", error.getCode());
        }
        verifyNoInteractions(dao);
    }
}
