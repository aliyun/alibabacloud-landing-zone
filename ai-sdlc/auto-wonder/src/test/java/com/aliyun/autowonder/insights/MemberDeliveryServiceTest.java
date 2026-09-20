package com.aliyun.autowonder.insights;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberDeliveryServiceTest {
    @Test void includesZeroMembersAndUnassignedTasksWithoutLosingTotals() {
        var dao = mock(MemberDeliveryDao.class);
        var zero = new MemberDeliveryService.Member(); zero.setMemberId(1L); zero.setMemberName("zero");
        when(dao.members(9L)).thenReturn(List.of(zero));
        var count = new MemberDeliveryService.Counts(); count.setTotal(2); count.setCompleted(1); count.setRequirements(1);
        when(dao.counts(eq(9L), any(), any())).thenReturn(List.of(count));
        var report = new MemberDeliveryService(dao).get(9L, "2026-01-01", "2026-01-07");
        assertEquals(2, report.members().size());
        assertEquals(0, report.members().get(0).getTotal());
        assertEquals("未归属成员", report.members().get(1).getMemberName());
        assertEquals(2, report.summary().getTotal());
        assertEquals(1, report.weekRequirements());
        verify(dao).counts(eq(9L), eq(Date.from(java.time.Instant.parse("2025-12-31T16:00:00Z"))), eq(Date.from(java.time.Instant.parse("2026-01-07T16:00:00Z"))));
    }
    @Test void rejectsInvalidRangesBeforeDatabaseAccess() {
        var dao = mock(MemberDeliveryDao.class); var service = new MemberDeliveryService(dao);
        assertThrows(RuntimeException.class, () -> service.get(1, "wrong", "2026-01-01"));
        assertThrows(RuntimeException.class, () -> service.get(1, "2026-01-02", "2026-01-01"));
        assertThrows(RuntimeException.class, () -> service.get(1, "2020-01-01", "2026-01-01"));
        verifyNoInteractions(dao);
    }
}
